mod art;
mod bridge;

use crate::api::{self, BrowserId, BrowserOptions, Frame, Key, Pointer, StartOptions};

pub struct Engine {
    bridge: Option<bridge::Bridge>,
    next: BrowserId,
}

impl Engine {
    pub fn start(options: StartOptions) -> api::Result<Self> {
        api::info("[Wry] looking for the Android runtime");
        let vm = art::find()?;
        api::info("[Wry] found ART, loading the bridge");
        let bridge = bridge::Bridge::attach(vm, &options.data_dir)?;
        api::info("[Wry] probing the web view on the main thread");
        let report = bridge.probe()?;
        api::info(format!("[Wry] {report}"));
        Ok(Self { bridge: Some(bridge), next: 1 })
    }

    pub fn stop(&mut self) {
        self.bridge = None;
    }

    pub fn update(&mut self) {}

    pub fn create_browser(&mut self, options: BrowserOptions) -> api::Result<BrowserId> {
        if self.bridge.is_none() {
            return Err("the Android bridge is not running".into());
        }
        let id = self.next;
        self.next += 1;
        api::info(format!(
            "[Wry] browser {id} asked for {} ({}x{})",
            options.url, options.width, options.height
        ));
        Ok(id)
    }

    pub fn close_browser(&mut self, _id: BrowserId) {}

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
