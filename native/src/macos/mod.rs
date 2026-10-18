//! macOS: WKWebViews through Wry, on the main thread, which is the game's render thread.
//!
//! WebKit hands the page to this process as a tree of layers (its remote layer tree), which CARenderer draws into
//! an IOSurface the game binds. Where that doesn't work, pages are copied out with `takeSnapshot`.

mod browser;
mod navigation;

use crate::api::{self, BrowserId, BrowserOptions, Frame, Key, Pointer, StartOptions};
use browser::Browser;
use objc2::rc::Retained;
use objc2::runtime::{AnyObject, ProtocolObject};
use objc2::MainThreadMarker;
use objc2_core_foundation::{kCFRunLoopDefaultMode, CFRunLoop, CFRunLoopRunResult};
use objc2_foundation::{NSDictionary, NSNumber, NSString, NSUserDefaults};
use objc2_metal::{MTLCommandQueue, MTLCreateSystemDefaultDevice, MTLDevice};
use std::collections::HashMap;
use std::ffi::c_void;
use std::time::{Duration, Instant};

/// How long a frame may wait for WebKit's work on the main run loop, which the page's frame rate depends on.
const RUN_LOOP_BUDGET: Duration = Duration::from_millis(4);

pub struct Engine {
    mtm: MainThreadMarker,
    gpu_frames: bool,
    device: Option<Retained<ProtocolObject<dyn MTLDevice>>>,
    queue: Option<Retained<ProtocolObject<dyn MTLCommandQueue>>>,
    browsers: HashMap<BrowserId, Browser>,
    next_id: BrowserId,
}

impl Engine {
    pub fn start(options: StartOptions) -> api::Result<Self> {
        let mtm = MainThreadMarker::new()
            .ok_or("Wry has to run on the main thread, which the launcher gives the game with -XstartOnFirstThread")?;

        // WebKit then sends the page as layers into this process instead of showing it through the window server.
        // The registration domain is never written to disk.
        let key = NSString::from_str("WebKit2UseRemoteLayerTreeDrawingArea");
        let value = NSNumber::new_bool(true);
        let value: &AnyObject = &value;
        let defaults = NSDictionary::<NSString, AnyObject>::from_slices(&[&*key], &[value]);
        let user_defaults = NSUserDefaults::standardUserDefaults();
        unsafe { user_defaults.registerDefaults(&defaults) };

        let device = MTLCreateSystemDefaultDevice();
        let queue = device.as_ref().and_then(|device| device.newCommandQueue());
        if let Some(device) = &device {
            api::info(format!("Rendering pages on {}", device.name()));
        }
        let gpu_frames = options.gpu_frames && queue.is_some();
        Ok(Self { mtm, gpu_frames, device, queue, browsers: HashMap::new(), next_id: 0 })
    }

    pub fn stop(&mut self) {
        for (_, browser) in self.browsers.drain() {
            browser.close();
        }
    }

    /// Lets WebKit do its work on the main run loop, then draws what changed.
    pub fn update(&mut self) {
        if self.browsers.is_empty() {
            return;
        }
        let mode = unsafe { kCFRunLoopDefaultMode };
        for _ in 0..64 {
            if CFRunLoop::run_in_mode(mode, 0.0, true) != CFRunLoopRunResult::HandledSource {
                break;
            }
        }
        if self.browsers.values().any(Browser::is_due) {
            let until = Instant::now() + RUN_LOOP_BUDGET;
            while let Some(left) = until.checked_duration_since(Instant::now()) {
                CFRunLoop::run_in_mode(mode, left.as_secs_f64(), true);
            }
        }
        for browser in self.browsers.values_mut() {
            browser.update(self.mtm);
        }
    }

    pub fn create_browser(&mut self, options: BrowserOptions) -> api::Result<BrowserId> {
        self.next_id += 1;
        let id = self.next_id;
        let gpu = self.gpu_frames.then(|| (self.device.clone().unwrap(), self.queue.clone().unwrap()));
        let browser = Browser::create(id, &options, gpu, self.mtm)?;
        self.browsers.insert(id, browser);
        Ok(id)
    }

    pub fn close_browser(&mut self, id: BrowserId) {
        if let Some(browser) = self.browsers.remove(&id) {
            browser.close();
        }
    }

    fn browser(&mut self, id: BrowserId) -> Option<&mut Browser> {
        self.browsers.get_mut(&id)
    }

    pub fn navigate(&mut self, id: BrowserId, url: String) {
        if let Some(browser) = self.browser(id) {
            browser.navigate(&url);
        }
    }

    pub fn reload(&mut self, id: BrowserId, ignore_cache: bool) {
        if let Some(browser) = self.browser(id) {
            browser.reload(ignore_cache);
        }
    }

    pub fn go_back(&mut self, id: BrowserId) {
        if let Some(browser) = self.browser(id) {
            browser.go_back();
        }
    }

    pub fn go_forward(&mut self, id: BrowserId) {
        if let Some(browser) = self.browser(id) {
            browser.go_forward();
        }
    }

    pub fn resize(&mut self, id: BrowserId, width: u32, height: u32, zoom: f64) {
        if let Some(browser) = self.browser(id) {
            browser.resize(width, height, zoom);
        }
    }

    pub fn set_fps(&mut self, id: BrowserId, fps: u32) {
        if let Some(browser) = self.browser(id) {
            browser.fps = fps.max(1);
        }
    }

    pub fn pointer(&mut self, id: BrowserId, x: f64, y: f64, pointer: Pointer) {
        if let Some(browser) = self.browser(id) {
            browser.pointer(x, y, pointer);
        }
    }

    pub fn focus(&mut self, id: BrowserId) {
        if let Some(browser) = self.browser(id) {
            browser.focus();
        }
    }

    pub fn key(&mut self, id: BrowserId, key: Key) {
        if let Some(browser) = self.browser(id) {
            browser.key(&key);
        }
    }

    pub fn text(&mut self, id: BrowserId, text: String) {
        if let Some(browser) = self.browser(id) {
            browser.text(&text);
        }
    }

    pub fn take_frame(&mut self, id: BrowserId) -> Option<Frame> {
        self.browser(id)?.take_frame()
    }
}

#[link(name = "OpenGL", kind = "framework")]
unsafe extern "C" {
    fn CGLGetCurrentContext() -> *mut c_void;
    fn CGLTexImageIOSurface2D(context: *mut c_void, target: u32, internal_format: u32, width: i32, height: i32,
        format: u32, kind: u32, surface: *mut c_void, plane: u32) -> i32;
}

/// Binds an IOSurface to the rectangle texture bound on the current OpenGL context, returns the CGL error.
pub fn bind_io_surface(surface: i64, width: i32, height: i32) -> i32 {
    const GL_TEXTURE_RECTANGLE: u32 = 0x84F5;
    const GL_RGBA: u32 = 0x1908;
    const GL_BGRA: u32 = 0x80E1;
    const GL_UNSIGNED_INT_8_8_8_8_REV: u32 = 0x8367;
    unsafe {
        CGLTexImageIOSurface2D(CGLGetCurrentContext(), GL_TEXTURE_RECTANGLE, GL_RGBA, width, height, GL_BGRA,
            GL_UNSIGNED_INT_8_8_8_8_REV, surface as *mut c_void, 0)
    }
}
