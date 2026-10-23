mod art;
mod bridge;

use crate::api::{self, BrowserId, BrowserOptions, Frame, Key, Pointer, StartOptions};

pub struct Engine {
    bridge: Option<bridge::Bridge>,
    next: BrowserId,
}

impl Engine {
    pub fn start(options: StartOptions) -> api::Result<Self> {
        let vm = art::find()?;
        let bridge = bridge::Bridge::attach(vm, &options.data_dir)?;
        let report = bridge.start()?;
        api::info(format!("[Wry] {report}"));
        Ok(Self { bridge: Some(bridge), next: 1 })
    }

    pub fn stop(&mut self) {
        self.bridge = None;
    }

    pub fn update(&mut self) {}

    pub fn create_browser(&mut self, options: BrowserOptions) -> api::Result<BrowserId> {
        let bridge = self
            .bridge
            .as_ref()
            .ok_or_else(|| "the Android bridge is not running".to_string())?;
        let id = self.next;
        self.next += 1;
        let report = bridge.create(&options.url)?;
        api::info(format!("[Wry] {report}"));
        Ok(id)
    }

    pub fn close_browser(&mut self, _id: BrowserId) {
        if let Some(bridge) = self.bridge.as_ref() {
            if let Err(error) = bridge.close() {
                api::warn(format!("[Wry] closing the webview failed: {error}"));
            }
        }
    }

    pub fn set_visible(&mut self, _id: BrowserId, visible: bool) {
        if let Some(bridge) = self.bridge.as_ref() {
            if let Err(error) = bridge.set_visible(visible) {
                api::warn(format!("[Wry] setting the webview visibility failed: {error}"));
            }
        }
    }

    pub fn navigate(&mut self, _id: BrowserId, _url: String) {}

    pub fn reload(&mut self, _id: BrowserId, _ignore_cache: bool) {}

    pub fn go_back(&mut self, _id: BrowserId) {}

    pub fn go_forward(&mut self, _id: BrowserId) {}

    pub fn resize(&mut self, _id: BrowserId, _width: u32, _height: u32, _zoom: f64) {}

    pub fn set_fps(&mut self, _id: BrowserId, _fps: u32) {}

    pub fn pointer(&mut self, _id: BrowserId, _x: f64, _y: f64, _pointer: Pointer) {}

    pub fn focus(&mut self, _id: BrowserId) {}

    pub fn key(&mut self, _id: BrowserId, _key: Key) {}

    pub fn text(&mut self, _id: BrowserId, _text: String) {}

    pub fn take_frame(&mut self, _id: BrowserId) -> Option<Frame> {
        None
    }
}
