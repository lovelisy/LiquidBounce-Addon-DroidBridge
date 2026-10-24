mod art;
mod bridge;

use crate::api::{self, BrowserId, BrowserOptions, Frame, Key, MouseButton, Pointer, StartOptions};

pub struct Engine {
    bridge: Option<bridge::Bridge>,
    next: BrowserId,
    viewport: (u32, u32),
    browser: BrowserId,
    url: String,
    announced: bool,
}

impl Engine {
    pub fn start(options: StartOptions) -> api::Result<Self> {
        let vm = art::find()?;
        let bridge = bridge::Bridge::attach(vm, &options.data_dir)?;
        let report = bridge.start()?;
        api::info(format!("[Wry] {report}"));
        Ok(Self {
            bridge: Some(bridge),
            next: 1,
            viewport: (0, 0),
            browser: 0,
            url: String::new(),
            announced: true,
        })
    }

    pub fn stop(&mut self) {
        if let Some(bridge) = self.bridge.take() {
            if let Err(error) = bridge.close() {
                api::warn(format!("[Wry] closing the webview failed: {error}"));
            }
        }
    }

    pub fn update(&mut self) {
        if self.announced {
            return;
        }
        let loaded = match self.bridge.as_ref() {
            Some(bridge) => bridge.loaded(),
            None => return,
        };
        match loaded {
            Ok(true) => {
                self.announced = true;
                api::push_event(self.browser, api::EventKind::Loaded, 200, self.url.clone(), "");
            }
            Ok(false) => {}
            Err(error) => api::warn(format!("[Wry] checking the page state failed: {error}")),
        }
    }

    pub fn create_browser(&mut self, options: BrowserOptions) -> api::Result<BrowserId> {
        let id = self.next;
        self.next += 1;
        self.viewport = (options.width, options.height);
        self.browser = id;
        self.url = options.url.clone();
        self.announced = false;
        let bridge = self
            .bridge
            .as_ref()
            .ok_or_else(|| "the Android bridge is not running".to_string())?;
        let report = bridge.create(&options.url)?;
        api::info(format!("[Wry] browser {id} asked for {} ({}x{})", options.url, options.width, options.height));
        api::info(format!("[Wry] {report}"));
        Ok(id)
    }

    pub fn close_browser(&mut self, _id: BrowserId) {
        self.announced = true;
        if let Some(bridge) = self.bridge.as_ref() {
            if let Err(error) = bridge.close() {
                api::warn(format!("[Wry] closing the webview failed: {error}"));
            }
        }
    }

    pub fn set_visible(&mut self, id: BrowserId, visible: bool) {
        api::info(format!("[Wry] browser {id} visibility {visible}"));
        let Some(bridge) = self.bridge.as_ref() else {
            return;
        };
        if let Err(error) = bridge.set_visible(visible) {
            api::warn(format!("[Wry] setting the webview visibility failed: {error}"));
        }
    }

    pub fn navigate(&mut self, _id: BrowserId, _url: String) {}

    pub fn reload(&mut self, _id: BrowserId, _ignore_cache: bool) {}

    pub fn go_back(&mut self, _id: BrowserId) {}

    pub fn go_forward(&mut self, _id: BrowserId) {}

    pub fn resize(&mut self, _id: BrowserId, width: u32, height: u32, _zoom: f64) {
        self.viewport = (width.max(1), height.max(1));
    }

    pub fn set_fps(&mut self, _id: BrowserId, _fps: u32) {}

    pub fn pointer(&mut self, _id: BrowserId, x: f64, y: f64, pointer: Pointer) {
        let Some(bridge) = self.bridge.as_ref() else {
            return;
        };
        let (width, height) = self.viewport;
        let nx = if width > 0 { x / f64::from(width) } else { 0.0 };
        let ny = if height > 0 { y / f64::from(height) } else { 0.0 };
        let (action, button, amount) = match pointer {
            Pointer::Move => (0, 0, 0.0),
            Pointer::Button(button, pressed) => {
                let code = match button {
                    MouseButton::Left => 1,
                    MouseButton::Right => 2,
                    MouseButton::Middle => 3,
                };
                (if pressed { 1 } else { 2 }, code, 0.0)
            }
            Pointer::Scroll(steps) => (3, 0, steps),
        };
        if let Err(error) = bridge.pointer(nx, ny, action, button, amount) {
            api::warn(format!("[Wry] forwarding a pointer event failed: {error}"));
        }
    }

    pub fn focus(&mut self, _id: BrowserId) {}

    pub fn key(&mut self, _id: BrowserId, key: Key) {
        let Some(bridge) = self.bridge.as_ref() else {
            return;
        };
        if let Err(error) = bridge.key(key.pressed, key.keycode) {
            api::warn(format!("[Wry] forwarding a key event failed: {error}"));
        }
    }

    pub fn text(&mut self, _id: BrowserId, _text: String) {}

    pub fn take_frame(&mut self, _id: BrowserId) -> Option<Frame> {
        None
    }
}
