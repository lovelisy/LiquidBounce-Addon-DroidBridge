//! Windows: WebView2 in composition mode on a thread of its own. Each page draws into a composition visual that is
//! never shown, and Windows.Graphics.Capture hands out every frame of it as a D3D11 texture.

mod browser;

use crate::api::{self, BrowserId, BrowserOptions, Frame, Key, Pointer, StartOptions};
use browser::Browser;
use std::cell::RefCell;
use std::collections::HashMap;
use std::sync::{mpsc, Arc, Mutex};
use std::time::Duration;
use webview2_com::Microsoft::Web::WebView2::Win32::*;
use webview2_com::{take_pwstr, CoreWebView2EnvironmentOptions, CreateCoreWebView2EnvironmentCompletedHandler};
use windows::core::{Interface, HSTRING, PCWSTR, PWSTR};
use windows::Graphics::DirectX::Direct3D11::IDirect3DDevice;
use windows::System::{DispatcherQueue, DispatcherQueueController, DispatcherQueueHandler};
use windows::UI::Composition::Compositor;
use windows::Win32::Foundation::*;
use windows::Win32::Graphics::Direct3D::*;
use windows::Win32::Graphics::Direct3D11::*;
use windows::Win32::Graphics::Dxgi::IDXGIDevice;
use windows::Win32::System::Com::{CoInitializeEx, CoUninitialize, COINIT_APARTMENTTHREADED};
use windows::Win32::System::LibraryLoader::GetModuleHandleW;
use windows::Win32::System::WinRT::Direct3D11::CreateDirect3D11DeviceFromDXGIDevice;
use windows::Win32::System::WinRT::{CreateDispatcherQueueController, DispatcherQueueOptions, DQTAT_COM_STA,
    DQTYPE_THREAD_CURRENT};
use windows::Win32::UI::WindowsAndMessaging::*;

/// Where the WebView2 runtime comes from, for players who don't have it.
const RUNTIME_URL: &str = "https://go.microsoft.com/fwlink/p/?LinkId=2124703";

/// What the capture thread and the render thread share of a browser.
#[derive(Default)]
struct Slot {
    width: u32,
    height: u32,
    /// Shared textures by NT handle, the capture writes into the one neither shown nor newest.
    ring: Vec<i64>,
    newest: Option<usize>,
    shown: Option<usize>,
    /// Pixels when frames go through memory.
    pixels: Option<Vec<u8>>,
    front: Option<Vec<u8>>,
    spare: Option<Vec<u8>>,
}

type Slots = Arc<Mutex<HashMap<BrowserId, Slot>>>;

/// What lives on the WebView2 thread.
struct State {
    environment: ICoreWebView2Environment,
    compositor: Compositor,
    device: ID3D11Device,
    context: ID3D11DeviceContext,
    capture_device: IDirect3DDevice,
    gpu_frames: bool,
    slots: Slots,
    browsers: HashMap<BrowserId, Browser>,
}

thread_local! {
    static STATE: RefCell<Option<State>> = const { RefCell::new(None) };
}

pub struct Engine {
    queue: DispatcherQueue,
    thread: Option<std::thread::JoinHandle<()>>,
    slots: Slots,
    next_id: BrowserId,
}

fn hresult(error: windows::core::Error) -> String {
    format!("{} ({:#x})", error.message(), error.code().0)
}

impl Engine {
    pub fn start(options: StartOptions) -> api::Result<Self> {
        let mut version = PWSTR::null();
        if unsafe { GetAvailableCoreWebView2BrowserVersionString(PCWSTR::null(), &mut version) }.is_err() || version.is_null() {
            return Err(format!("The WebView2 runtime is not installed, it can be downloaded from {RUNTIME_URL}"));
        }
        api::info(format!("WebView2 runtime {}", take_pwstr(version)));

        let slots = Slots::default();
        let (ready_tx, ready_rx) = mpsc::channel();
        let thread_slots = slots.clone();
        let thread = std::thread::Builder::new()
            .name("wry-webview2".into())
            .spawn(move || run(options, thread_slots, ready_tx))
            .map_err(|e| e.to_string())?;
        let queue = ready_rx.recv_timeout(Duration::from_secs(30)).map_err(|_| "WebView2 did not start".to_string())??;
        Ok(Self { queue, thread: Some(thread), slots, next_id: 0 })
    }

    /// Runs `task` on the WebView2 thread.
    fn run(&self, task: impl FnOnce(&mut State) + Send + 'static) {
        let task = Mutex::new(Some(task));
        let handler = DispatcherQueueHandler::new(move || {
            if let Some(task) = task.lock().unwrap().take() {
                STATE.with(|state| {
                    if let Some(state) = state.borrow_mut().as_mut() {
                        task(state);
                    }
                });
            }
            Ok(())
        });
        if !self.queue.TryEnqueue(&handler).unwrap_or(false) {
            api::warn("The WebView2 thread is gone");
        }
    }

    fn with_browser(&self, id: BrowserId, task: impl FnOnce(&mut Browser, &mut State) + Send + 'static) {
        self.run(move |state| {
            if let Some(mut browser) = state.browsers.remove(&id) {
                task(&mut browser, state);
                state.browsers.insert(id, browser);
            }
        });
    }

    pub fn stop(&mut self) {
        let (done_tx, done_rx) = mpsc::channel();
        self.run(move |state| {
            for (_, browser) in state.browsers.drain() {
                browser.close();
            }
            unsafe { PostQuitMessage(0) };
            done_tx.send(()).ok();
        });
        if done_rx.recv_timeout(Duration::from_secs(5)).is_err() {
            api::warn("WebView2 did not stop in time");
            return;
        }
        if let Some(thread) = self.thread.take() {
            thread.join().ok();
        }
        self.slots.lock().unwrap().clear();
    }

    pub fn update(&mut self) {}

    pub fn create_browser(&mut self, options: BrowserOptions) -> api::Result<BrowserId> {
        self.next_id += 1;
        let id = self.next_id;
        self.slots.lock().unwrap().insert(id, Slot::default());
        self.run(move |state| {
            match Browser::create(id, &options, state) {
                Ok(browser) => {
                    state.browsers.insert(id, browser);
                }
                Err(error) => api::push_event(id, api::EventKind::Failed, -1, options.url, error),
            }
        });
        Ok(id)
    }

    pub fn close_browser(&mut self, id: BrowserId) {
        self.run(move |state| {
            if let Some(browser) = state.browsers.remove(&id) {
                browser.close();
            }
            state.slots.lock().unwrap().remove(&id);
        });
    }

    pub fn navigate(&mut self, id: BrowserId, url: String) {
        self.with_browser(id, move |browser, _| browser.navigate(&url));
    }

    pub fn reload(&mut self, id: BrowserId, ignore_cache: bool) {
        self.with_browser(id, move |browser, _| browser.reload(ignore_cache));
    }

    pub fn go_back(&mut self, id: BrowserId) {
        self.with_browser(id, |browser, _| browser.go_back());
    }

    pub fn go_forward(&mut self, id: BrowserId) {
        self.with_browser(id, |browser, _| browser.go_forward());
    }

    pub fn resize(&mut self, id: BrowserId, width: u32, height: u32, zoom: f64) {
        self.with_browser(id, move |browser, state| browser.resize(width, height, zoom, state));
    }

    pub fn set_fps(&mut self, id: BrowserId, fps: u32) {
        self.with_browser(id, move |browser, _| browser.set_fps(fps));
    }

    pub fn pointer(&mut self, id: BrowserId, x: f64, y: f64, pointer: Pointer) {
        self.with_browser(id, move |browser, _| browser.pointer(x, y, pointer));
    }

    pub fn focus(&mut self, _id: BrowserId) {
        // The page is told it has focus once, see Browser::create
    }

    pub fn key(&mut self, id: BrowserId, key: Key) {
        self.with_browser(id, move |browser, _| browser.key(&key));
    }

    pub fn text(&mut self, id: BrowserId, text: String) {
        self.with_browser(id, move |browser, _| browser.text(&text));
    }

    pub fn take_frame(&mut self, id: BrowserId) -> Option<Frame> {
        let mut slots = self.slots.lock().unwrap();
        let slot = slots.get_mut(&id)?;
        if let Some(index) = slot.newest.take() {
            slot.shown = Some(index);
            return Some(Frame::SharedTexture { handle: slot.ring[index], width: slot.width, height: slot.height });
        }
        let pixels = slot.pixels.take()?;
        if let Some(previous) = slot.front.replace(pixels) {
            slot.spare.get_or_insert(previous);
        }
        let front = slot.front.as_ref().unwrap();
        Some(Frame::Pixels {
            data: front.as_ptr(),
            len: front.len(),
            width: slot.width,
            height: slot.height,
            stride: slot.width * 4,
            bgra: true,
            flipped: false,
        })
    }
}

fn create_device(driver: D3D_DRIVER_TYPE) -> windows::core::Result<(ID3D11Device, ID3D11DeviceContext)> {
    let mut device = None;
    let mut context = None;
    unsafe {
        D3D11CreateDevice(None, driver, HMODULE::default(), D3D11_CREATE_DEVICE_BGRA_SUPPORT, None, D3D11_SDK_VERSION,
            Some(&mut device), None, Some(&mut context))?;
    }
    Ok((device.unwrap(), context.unwrap()))
}

fn environment(data_dir: &std::path::Path) -> api::Result<ICoreWebView2Environment> {
    let options = CoreWebView2EnvironmentOptions::default();
    // Chromium stops drawing pages it thinks are covered, and the page's window is never on screen
    unsafe { options.set_additional_browser_arguments("--disable-features=CalculateNativeWinOcclusion".into()) };
    let options: ICoreWebView2EnvironmentOptions = options.into();
    let data_dir = HSTRING::from(data_dir.as_os_str());
    let (tx, rx) = mpsc::channel();
    CreateCoreWebView2EnvironmentCompletedHandler::wait_for_async_operation(
        Box::new(move |handler| unsafe {
            CreateCoreWebView2EnvironmentWithOptions(PCWSTR::null(), &data_dir, &options, &handler)
                .map_err(webview2_com::Error::WindowsError)
        }),
        Box::new(move |result, environment| {
            tx.send(result.map(|_| environment)).ok();
            Ok(())
        }),
    ).map_err(|e| format!("{e:?}"))?;
    rx.recv().map_err(|e| e.to_string())?.map_err(hresult)?.ok_or_else(|| "No WebView2 environment".to_string())
}

unsafe extern "system" fn window_proc(hwnd: HWND, msg: u32, wparam: WPARAM, lparam: LPARAM) -> LRESULT {
    unsafe { DefWindowProcW(hwnd, msg, wparam, lparam) }
}

const WINDOW_CLASS: PCWSTR = windows::core::w!("LiquidBounceWry");

fn run(options: StartOptions, slots: Slots, ready: mpsc::Sender<api::Result<DispatcherQueue>>) {
    unsafe { CoInitializeEx(None, COINIT_APARTMENTTHREADED).ok().ok() };
    let setup = || -> api::Result<(DispatcherQueueController, State)> {
        let controller = unsafe {
            CreateDispatcherQueueController(DispatcherQueueOptions {
                dwSize: std::mem::size_of::<DispatcherQueueOptions>() as u32,
                threadType: DQTYPE_THREAD_CURRENT,
                apartmentType: DQTAT_COM_STA,
            })
        }.map_err(hresult)?;
        let (device, context) = create_device(D3D_DRIVER_TYPE_HARDWARE)
            .or_else(|_| create_device(D3D_DRIVER_TYPE_WARP))
            .map_err(hresult)?;
        let adapter = unsafe { device.cast::<IDXGIDevice>().and_then(|d| d.GetAdapter()).and_then(|a| a.GetDesc()) }
            .map(|desc| String::from_utf16_lossy(&desc.Description).trim_end_matches('\0').to_string())
            .unwrap_or_default();
        api::info(format!("Capturing pages on {adapter}"));
        let capture_device: IDirect3DDevice = unsafe {
            CreateDirect3D11DeviceFromDXGIDevice(&device.cast::<IDXGIDevice>().map_err(hresult)?)
                .and_then(|d| d.cast())
                .map_err(hresult)?
        };
        let compositor = Compositor::new().map_err(hresult)?;
        std::fs::create_dir_all(&options.data_dir).ok();
        let environment = environment(&options.data_dir)?;

        unsafe {
            let instance = GetModuleHandleW(None).map_err(hresult)?;
            RegisterClassW(&WNDCLASSW {
                lpfnWndProc: Some(window_proc),
                hInstance: instance.into(),
                lpszClassName: WINDOW_CLASS,
                ..Default::default()
            });
        }

        Ok((controller, State {
            environment,
            compositor,
            device,
            context,
            capture_device,
            gpu_frames: options.gpu_frames,
            slots,
            browsers: HashMap::new(),
        }))
    };

    let (controller, state) = match setup() {
        Ok(setup) => setup,
        Err(error) => {
            ready.send(Err(error)).ok();
            unsafe { CoUninitialize() };
            return;
        }
    };
    let queue = match controller.DispatcherQueue() {
        Ok(queue) => queue,
        Err(error) => {
            ready.send(Err(hresult(error))).ok();
            return;
        }
    };
    STATE.with(|cell| *cell.borrow_mut() = Some(state));
    ready.send(Ok(queue)).ok();

    unsafe {
        let mut message = MSG::default();
        while GetMessageW(&mut message, None, 0, 0).as_bool() {
            let _ = TranslateMessage(&message);
            DispatchMessageW(&message);
        }
    }

    STATE.with(|cell| cell.borrow_mut().take());
    drop(controller);
    unsafe { CoUninitialize() };
}
