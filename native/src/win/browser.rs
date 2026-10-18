//! One page: its WebView2 composition controller, the visual it draws into, and the capture of that visual.

use super::{hresult, State, WINDOW_CLASS};
use crate::api::{self, BrowserId, BrowserOptions, EventKind, Key, MouseButton, Pointer, MOD_ALT, MOD_CTRL, MOD_GUI,
    MOD_SHIFT};
use crate::{keys, script};
use serde_json::json;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use webview2_com::Microsoft::Web::WebView2::Win32::*;
use webview2_com::*;
use windows::core::{Interface, BOOL, HSTRING, PCWSTR, PWSTR};
use windows::Foundation::TypedEventHandler;
use windows::Graphics::Capture::{Direct3D11CaptureFramePool, GraphicsCaptureItem, GraphicsCaptureSession};
use windows::Graphics::DirectX::DirectXPixelFormat;
use windows::UI::Composition::{ContainerVisual, Visual};
use windows::Win32::Foundation::*;
use windows::Win32::Graphics::Direct3D11::*;
use windows::Win32::Graphics::Dxgi::Common::*;
use windows::Win32::Graphics::Dxgi::*;
use windows::Win32::System::LibraryLoader::GetModuleHandleW;
use windows::Win32::System::WinRT::Direct3D11::IDirect3DDxgiInterfaceAccess;
use windows::Win32::UI::WindowsAndMessaging::*;

pub struct Browser {
    id: BrowserId,
    window: HWND,
    root: ContainerVisual,
    composition: ICoreWebView2CompositionController,
    controller: ICoreWebView2Controller,
    webview: ICoreWebView2,
    capture: Option<Capture>,
    targets: Arc<Mutex<Targets>>,
    /// Mouse buttons held, as WebView2's virtual keys.
    buttons: i32,
}

struct Capture {
    pool: Direct3D11CaptureFramePool,
    session: GraphicsCaptureSession,
    _item: GraphicsCaptureItem,
}

impl Drop for Capture {
    fn drop(&mut self) {
        self.session.Close().ok();
        self.pool.Close().ok();
    }
}

/// A texture the game opens by its NT handle.
struct SharedTexture {
    texture: ID3D11Texture2D,
    handle: HANDLE,
}

// Only touched on the WebView2 thread
unsafe impl Send for SharedTexture {}

impl Drop for SharedTexture {
    fn drop(&mut self) {
        unsafe { CloseHandle(self.handle).ok() };
    }
}

/// Where the captured frames of a page go.
struct Targets {
    id: BrowserId,
    device: ID3D11Device,
    context: ID3D11DeviceContext,
    slots: super::Slots,
    gpu_frames: bool,
    width: u32,
    height: u32,
    fps: u32,
    last: Option<Instant>,
    ring: Vec<SharedTexture>,
    staging: Option<ID3D11Texture2D>,
    query: Option<ID3D11Query>,
}

fn texture_desc(width: u32, height: u32, staging: bool) -> D3D11_TEXTURE2D_DESC {
    D3D11_TEXTURE2D_DESC {
        Width: width,
        Height: height,
        MipLevels: 1,
        ArraySize: 1,
        Format: DXGI_FORMAT_B8G8R8A8_UNORM,
        SampleDesc: DXGI_SAMPLE_DESC { Count: 1, Quality: 0 },
        Usage: if staging { D3D11_USAGE_STAGING } else { D3D11_USAGE_DEFAULT },
        BindFlags: if staging { 0 } else { (D3D11_BIND_SHADER_RESOURCE.0 | D3D11_BIND_RENDER_TARGET.0) as u32 },
        CPUAccessFlags: if staging { D3D11_CPU_ACCESS_READ.0 as u32 } else { 0 },
        MiscFlags: if staging { 0 } else { (D3D11_RESOURCE_MISC_SHARED.0 | D3D11_RESOURCE_MISC_SHARED_NTHANDLE.0) as u32 },
    }
}

impl Targets {
    fn frame(&mut self, source: &ID3D11Texture2D, width: u32, height: u32) -> windows::core::Result<()> {
        let now = Instant::now();
        let interval = Duration::from_secs_f64(0.95 / self.fps.max(1) as f64);
        if self.last.is_some_and(|last| now - last < interval) {
            return Ok(());
        }
        self.last = Some(now);

        let (width, height) = (width.min(self.width), height.min(self.height));
        let region = D3D11_BOX { left: 0, top: 0, front: 0, right: width, bottom: height, back: 1 };
        if self.gpu_frames {
            self.to_shared(source, &region)
        } else {
            self.to_memory(source, &region)
        }
    }

    fn to_shared(&mut self, source: &ID3D11Texture2D, region: &D3D11_BOX) -> windows::core::Result<()> {
        if self.ring.is_empty() {
            for _ in 0..3 {
                let mut texture = None;
                unsafe { self.device.CreateTexture2D(&texture_desc(self.width, self.height, false), None, Some(&mut texture))? };
                let texture = texture.unwrap();
                let handle = unsafe {
                    texture.cast::<IDXGIResource1>()?
                        .CreateSharedHandle(None, (DXGI_SHARED_RESOURCE_READ | DXGI_SHARED_RESOURCE_WRITE).0, None)?
                };
                self.ring.push(SharedTexture { texture, handle });
            }
            let mut slots = self.slots.lock().unwrap();
            if let Some(slot) = slots.get_mut(&self.id) {
                gone(&slot.ring);
                slot.ring = self.ring.iter().map(|shared| shared.handle.0 as i64).collect();
                slot.width = self.width;
                slot.height = self.height;
                slot.newest = None;
                slot.shown = None;
            }
        }

        let index = {
            let slots = self.slots.lock().unwrap();
            let Some(slot) = slots.get(&self.id) else { return Ok(()) };
            (0..self.ring.len()).find(|&i| slot.newest != Some(i) && slot.shown != Some(i)).unwrap_or(0)
        };
        let query = match &self.query {
            Some(query) => query.clone(),
            None => {
                let mut query = None;
                unsafe {
                    self.device.CreateQuery(&D3D11_QUERY_DESC { Query: D3D11_QUERY_EVENT, MiscFlags: 0 }, Some(&mut query))?
                };
                self.query.insert(query.unwrap()).clone()
            }
        };
        unsafe {
            self.context.CopySubresourceRegion(&self.ring[index].texture, 0, 0, 0, 0, source, 0, Some(region));
            self.context.End(&query);
            self.context.Flush();
            // The game may only read it once the copy is done
            let started = Instant::now();
            let mut done = BOOL(0);
            while !done.as_bool() && started.elapsed() < Duration::from_millis(100) {
                self.context.GetData(&query, Some(&mut done as *mut BOOL as *mut _), size_of::<BOOL>() as u32, 0).ok();
                if !done.as_bool() {
                    std::thread::yield_now();
                }
            }
        }
        if let Some(slot) = self.slots.lock().unwrap().get_mut(&self.id) {
            slot.newest = Some(index);
        }
        Ok(())
    }

    fn to_memory(&mut self, source: &ID3D11Texture2D, region: &D3D11_BOX) -> windows::core::Result<()> {
        let staging = match &self.staging {
            Some(staging) => staging.clone(),
            None => {
                let mut texture = None;
                unsafe { self.device.CreateTexture2D(&texture_desc(self.width, self.height, true), None, Some(&mut texture))? };
                self.staging.insert(texture.unwrap()).clone()
            }
        };
        let row = self.width as usize * 4;
        let mut slots = self.slots.lock().unwrap();
        let Some(slot) = slots.get_mut(&self.id) else { return Ok(()) };
        let mut pixels = slot.spare.take().unwrap_or_default();
        pixels.resize(row * self.height as usize, 0);
        unsafe {
            self.context.CopySubresourceRegion(&staging, 0, 0, 0, 0, source, 0, Some(region));
            let mut mapped = D3D11_MAPPED_SUBRESOURCE::default();
            self.context.Map(&staging, 0, D3D11_MAP_READ, 0, Some(&mut mapped))?;
            for y in 0..self.height as usize {
                let from = std::slice::from_raw_parts((mapped.pData as *const u8).add(y * mapped.RowPitch as usize), row);
                pixels[y * row..(y + 1) * row].copy_from_slice(from);
            }
            self.context.Unmap(&staging, 0);
        }
        slot.width = self.width;
        slot.height = self.height;
        if let Some(previous) = slot.pixels.replace(pixels) {
            slot.spare = Some(previous);
        }
        Ok(())
    }

    fn resize(&mut self, width: u32, height: u32) {
        self.width = width;
        self.height = height;
        self.staging = None;
        // The old textures go once the game let go of them, see gone()
        self.ring.clear();
    }
}

/// Tells the game that shared textures are gone.
fn gone(handles: &[i64]) {
    for &handle in handles {
        api::push(api::Event { browser: 0, kind: EventKind::BufferGone, code: 0, value: handle, text: String::new(),
            detail: String::new() });
    }
}

fn string(read: impl FnOnce(*mut PWSTR) -> windows::core::Result<()>) -> String {
    let mut value = PWSTR::null();
    if read(&mut value).is_err() || value.is_null() {
        return String::new();
    }
    take_pwstr(value)
}

fn error_text(status: COREWEBVIEW2_WEB_ERROR_STATUS) -> &'static str {
    match status {
        COREWEBVIEW2_WEB_ERROR_STATUS_CANNOT_CONNECT => "Could not connect",
        COREWEBVIEW2_WEB_ERROR_STATUS_CONNECTION_ABORTED => "The connection was aborted",
        COREWEBVIEW2_WEB_ERROR_STATUS_CONNECTION_RESET => "The connection was reset",
        COREWEBVIEW2_WEB_ERROR_STATUS_DISCONNECTED => "Disconnected",
        COREWEBVIEW2_WEB_ERROR_STATUS_HOST_NAME_NOT_RESOLVED => "The host name could not be resolved",
        COREWEBVIEW2_WEB_ERROR_STATUS_SERVER_UNREACHABLE => "The server is unreachable",
        COREWEBVIEW2_WEB_ERROR_STATUS_TIMEOUT => "The connection timed out",
        COREWEBVIEW2_WEB_ERROR_STATUS_REDIRECT_FAILED => "A redirect failed",
        COREWEBVIEW2_WEB_ERROR_STATUS_ERROR_HTTP_INVALID_SERVER_RESPONSE => "The server sent an invalid response",
        _ => "The page could not be loaded",
    }
}

impl Browser {
    pub fn create(id: BrowserId, options: &BrowserOptions, state: &mut State) -> api::Result<Self> {
        let (width, height) = (options.width, options.height);
        let window = unsafe {
            CreateWindowExW(WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE, WINDOW_CLASS, PCWSTR::null(), WS_POPUP, 0, 0,
                width as i32, height as i32, None, None, GetModuleHandleW(None).ok().map(Into::into), None)
        }.map_err(hresult)?;

        let root = state.compositor.CreateContainerVisual().map_err(hresult)?;
        root.SetSize(windows_numerics::Vector2 { X: width as f32, Y: height as f32 }).map_err(hresult)?;

        let environment: ICoreWebView2Environment10 = state.environment.cast().map_err(hresult)?;
        let controller_options = unsafe { environment.CreateCoreWebView2ControllerOptions() }.map_err(hresult)?;
        unsafe { controller_options.SetIsInPrivateModeEnabled(options.incognito) }.map_err(hresult)?;
        let (tx, rx) = std::sync::mpsc::channel();
        CreateCoreWebView2CompositionControllerCompletedHandler::wait_for_async_operation(
            Box::new(move |handler| unsafe {
                environment.CreateCoreWebView2CompositionControllerWithOptions(window, &controller_options, &handler)
                    .map_err(webview2_com::Error::WindowsError)
            }),
            Box::new(move |result, controller| {
                tx.send(result.map(|_| controller)).ok();
                Ok(())
            }),
        ).map_err(|e| format!("{e:?}"))?;
        let composition: ICoreWebView2CompositionController = rx.recv().map_err(|e| e.to_string())?
            .map_err(hresult)?
            .ok_or("No WebView2 controller")?;

        let controller: ICoreWebView2Controller = composition.cast().map_err(hresult)?;
        let webview = unsafe {
            composition.SetRootVisualTarget(&root).map_err(hresult)?;
            if let Ok(controller3) = controller.cast::<ICoreWebView2Controller3>() {
                // One pixel of the page is one pixel of the texture, whatever the monitor's scale
                controller3.SetShouldDetectMonitorScaleChanges(false).ok();
                controller3.SetRasterizationScale(1.0).ok();
            }
            controller.SetBounds(RECT { left: 0, top: 0, right: width as i32, bottom: height as i32 }).map_err(hresult)?;
            controller.SetZoomFactor(options.zoom).ok();
            controller.cast::<ICoreWebView2Controller2>().map_err(hresult)?
                .SetDefaultBackgroundColor(COREWEBVIEW2_COLOR { A: 0, R: 0, G: 0, B: 0 }).map_err(hresult)?;
            controller.SetIsVisible(true).map_err(hresult)?;
            controller.CoreWebView2().map_err(hresult)?
        };

        let targets = Arc::new(Mutex::new(Targets {
            id,
            device: state.device.clone(),
            context: state.context.clone(),
            slots: state.slots.clone(),
            gpu_frames: state.gpu_frames,
            width,
            height,
            fps: options.fps,
            last: None,
            ring: Vec::new(),
            staging: None,
            query: None,
        }));
        let mut browser = Self { id, window, root, composition, controller, webview, capture: None, targets, buttons: 0 };
        browser.connect().map_err(hresult)?;
        browser.cdp("Emulation.setFocusEmulationEnabled", json!({ "enabled": true }));
        browser.navigate(&options.url);
        browser.capture = Some(browser.start_capture(state).map_err(hresult)?);
        Ok(browser)
    }

    fn connect(&self) -> windows::core::Result<()> {
        let id = self.id;
        let webview = &self.webview;
        let mut token = 0i64;
        unsafe {
            let settings = webview.Settings()?;
            settings.SetAreDefaultContextMenusEnabled(false)?;
            settings.SetIsZoomControlEnabled(false)?;
            settings.SetIsStatusBarEnabled(false)?;

            webview.AddScriptToExecuteOnDocumentCreated(&HSTRING::from(script::INIT_SCRIPT),
                &AddScriptToExecuteOnDocumentCreatedCompletedHandler::create(Box::new(|_, _| Ok(()))))?;
            webview.add_WebMessageReceived(&WebMessageReceivedEventHandler::create(Box::new(move |_, args| {
                if let Some(args) = args {
                    script::dispatch(id, &string(|value| args.TryGetWebMessageAsString(value)));
                }
                Ok(())
            })), &mut token)?;
            webview.add_NavigationStarting(&NavigationStartingEventHandler::create(Box::new(move |_, args| {
                if let Some(args) = args {
                    api::push_event(id, EventKind::Loading, 0, string(|value| args.Uri(value)), "");
                }
                Ok(())
            })), &mut token)?;
            webview.add_NavigationCompleted(&NavigationCompletedEventHandler::create(Box::new(move |sender, args| {
                let (Some(sender), Some(args)) = (sender, args) else { return Ok(()) };
                let url = string(|value| sender.Source(value));
                let mut success = BOOL(0);
                args.IsSuccess(&mut success)?;
                let mut status = COREWEBVIEW2_WEB_ERROR_STATUS_UNKNOWN;
                args.WebErrorStatus(&mut status)?;
                let mut http_status = 0;
                if let Ok(args2) = args.cast::<ICoreWebView2NavigationCompletedEventArgs2>() {
                    args2.HttpStatusCode(&mut http_status).ok();
                }
                if success.as_bool() || (http_status >= 400 && status == COREWEBVIEW2_WEB_ERROR_STATUS_UNKNOWN) {
                    api::push_event(id, EventKind::Loaded, http_status, url, "");
                } else if status != COREWEBVIEW2_WEB_ERROR_STATUS_OPERATION_CANCELED {
                    api::push_event(id, EventKind::Failed, status.0, url, error_text(status));
                }
                Ok(())
            })), &mut token)?;
            webview.add_SourceChanged(&SourceChangedEventHandler::create(Box::new(move |sender, _| {
                if let Some(sender) = sender {
                    api::push_event(id, EventKind::Url, 0, string(|value| sender.Source(value)), "");
                }
                Ok(())
            })), &mut token)?;
            webview.add_NewWindowRequested(&NewWindowRequestedEventHandler::create(Box::new(|_, args| {
                if let Some(args) = args {
                    args.SetHandled(true)?;
                }
                Ok(())
            })), &mut token)?;
            webview.add_ProcessFailed(&ProcessFailedEventHandler::create(Box::new(move |_, _| {
                api::push_event(id, EventKind::Failed, -2, "", "The page's process ended");
                Ok(())
            })), &mut token)?;
        }
        Ok(())
    }

    fn start_capture(&self, state: &State) -> windows::core::Result<Capture> {
        let item = GraphicsCaptureItem::CreateFromVisual(&self.root.cast::<Visual>()?)?;
        let pool = Direct3D11CaptureFramePool::Create(&state.capture_device, DirectXPixelFormat::B8G8R8A8UIntNormalized, 2,
            item.Size()?)?;
        let session = pool.CreateCaptureSession(&item)?;
        session.SetIsCursorCaptureEnabled(false).ok();
        session.SetIsBorderRequired(false).ok();
        let targets = self.targets.clone();
        pool.FrameArrived(&TypedEventHandler::new(move |pool: windows::core::Ref<Direct3D11CaptureFramePool>, _| {
            let Ok(pool) = pool.ok() else { return Ok(()) };
            let frame = pool.TryGetNextFrame()?;
            let size = frame.ContentSize()?;
            let texture: ID3D11Texture2D = unsafe { frame.Surface()?.cast::<IDirect3DDxgiInterfaceAccess>()?.GetInterface()? };
            if let Err(error) = targets.lock().unwrap().frame(&texture, size.Width as u32, size.Height as u32) {
                api::warn(format!("A frame could not be taken: {}", hresult(error)));
            }
            frame.Close()?;
            Ok(())
        }))?;
        session.StartCapture()?;
        Ok(Capture { pool, session, _item: item })
    }

    fn cdp(&self, method: &str, params: serde_json::Value) {
        let method = HSTRING::from(method);
        let params = HSTRING::from(params.to_string());
        unsafe {
            self.webview.CallDevToolsProtocolMethod(&method, &params,
                &CallDevToolsProtocolMethodCompletedHandler::create(Box::new(|_, _| Ok(())))).ok();
        }
    }

    pub fn navigate(&self, url: &str) {
        unsafe { self.webview.Navigate(&HSTRING::from(url)).ok() };
    }

    pub fn reload(&self, ignore_cache: bool) {
        if ignore_cache {
            self.cdp("Page.reload", json!({ "ignoreCache": true }));
        } else {
            unsafe { self.webview.Reload().ok() };
        }
    }

    pub fn go_back(&self) {
        unsafe { self.webview.GoBack().ok() };
    }

    pub fn go_forward(&self) {
        unsafe { self.webview.GoForward().ok() };
    }

    pub fn resize(&mut self, width: u32, height: u32, zoom: f64, state: &State) {
        self.capture = None;
        self.targets.lock().unwrap().resize(width, height);
        self.root.SetSize(windows_numerics::Vector2 { X: width as f32, Y: height as f32 }).ok();
        unsafe {
            self.controller.SetBounds(RECT { left: 0, top: 0, right: width as i32, bottom: height as i32 }).ok();
            self.controller.SetZoomFactor(zoom).ok();
        }
        match self.start_capture(state) {
            Ok(capture) => self.capture = Some(capture),
            Err(error) => api::warn(format!("The page can't be captured at {width}x{height}: {}", hresult(error))),
        }
    }

    pub fn set_fps(&self, fps: u32) {
        self.targets.lock().unwrap().fps = fps;
    }

    pub fn pointer(&mut self, x: f64, y: f64, pointer: Pointer) {
        let point = POINT { x: x as i32, y: y as i32 };
        let (kind, data) = match pointer {
            Pointer::Move => (COREWEBVIEW2_MOUSE_EVENT_KIND_MOVE, 0),
            Pointer::Scroll(steps) => (COREWEBVIEW2_MOUSE_EVENT_KIND_WHEEL, (steps * 120.0) as i32 as u32),
            Pointer::Button(button, pressed) => {
                let (down, up, flag) = match button {
                    MouseButton::Left => (COREWEBVIEW2_MOUSE_EVENT_KIND_LEFT_BUTTON_DOWN,
                        COREWEBVIEW2_MOUSE_EVENT_KIND_LEFT_BUTTON_UP, COREWEBVIEW2_MOUSE_EVENT_VIRTUAL_KEYS_LEFT_BUTTON),
                    MouseButton::Right => (COREWEBVIEW2_MOUSE_EVENT_KIND_RIGHT_BUTTON_DOWN,
                        COREWEBVIEW2_MOUSE_EVENT_KIND_RIGHT_BUTTON_UP, COREWEBVIEW2_MOUSE_EVENT_VIRTUAL_KEYS_RIGHT_BUTTON),
                    MouseButton::Middle => (COREWEBVIEW2_MOUSE_EVENT_KIND_MIDDLE_BUTTON_DOWN,
                        COREWEBVIEW2_MOUSE_EVENT_KIND_MIDDLE_BUTTON_UP, COREWEBVIEW2_MOUSE_EVENT_VIRTUAL_KEYS_MIDDLE_BUTTON),
                };
                if pressed {
                    self.buttons |= flag.0;
                } else {
                    self.buttons &= !flag.0;
                }
                (if pressed { down } else { up }, 0)
            }
        };
        unsafe {
            self.composition.SendMouseInput(COREWEBVIEW2_MOUSE_EVENT_KIND_MOVE,
                COREWEBVIEW2_MOUSE_EVENT_VIRTUAL_KEYS(self.buttons), 0, point).ok();
            if kind != COREWEBVIEW2_MOUSE_EVENT_KIND_MOVE {
                self.composition.SendMouseInput(kind, COREWEBVIEW2_MOUSE_EVENT_VIRTUAL_KEYS(self.buttons), data, point).ok();
            }
        }
    }

    /// Keys go through the DevTools protocol, which reaches the page without its window having focus.
    pub fn key(&self, key: &Key) {
        if key.typed_character().is_some() {
            return;
        }
        let by_scancode = keys::lookup(key.scancode);
        // Shortcuts follow the layout: the letter the key types, not where it sits
        let (vk, code, name) = match char::from_u32(key.keycode as u32).filter(|c| c.is_ascii_graphic()) {
            Some(c) if by_scancode.is_none_or(|info| info.key.is_empty()) => {
                let info = keys::by_keysym(c.to_ascii_lowercase() as u32).or(by_scancode);
                let vk = if c.is_ascii_alphanumeric() { c.to_ascii_uppercase() as u32 } else { info.map_or(0, |i| i.vk) };
                (vk, by_scancode.or(info).map_or("", |i| i.code), c.to_string())
            }
            _ => match by_scancode {
                Some(info) => (info.vk, info.code, info.key.to_string()),
                None => return,
            },
        };
        let mut modifiers = 0;
        if key.modifiers & MOD_ALT != 0 {
            modifiers |= 1;
        }
        if key.modifiers & MOD_CTRL != 0 {
            modifiers |= 2;
        }
        if key.modifiers & MOD_GUI != 0 {
            modifiers |= 4;
        }
        if key.modifiers & MOD_SHIFT != 0 {
            modifiers |= 8;
        }
        let mut params = json!({
            "type": if key.pressed { "rawKeyDown" } else { "keyUp" },
            "modifiers": modifiers,
            "windowsVirtualKeyCode": vk,
            "nativeVirtualKeyCode": vk,
            "code": code,
            "key": name,
            "isKeypad": (84..=99).contains(&key.scancode),
        });
        // Enter also types, for text areas and forms
        if key.pressed && name == "Enter" {
            params["type"] = json!("keyDown");
            params["text"] = json!("\r");
            params["unmodifiedText"] = json!("\r");
        }
        self.cdp("Input.dispatchKeyEvent", params);
    }

    pub fn text(&self, text: &str) {
        self.cdp("Input.insertText", json!({ "text": text }));
    }

    pub fn close(mut self) {
        self.capture = None;
        let handles: Vec<i64> = self.targets.lock().unwrap().ring.iter().map(|shared| shared.handle.0 as i64).collect();
        gone(&handles);
        unsafe {
            self.controller.Close().ok();
            DestroyWindow(self.window).ok();
        }
    }
}
