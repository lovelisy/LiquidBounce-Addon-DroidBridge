use crate::api::{self, BrowserId, BrowserOptions, Frame, Key, Pointer, StartOptions};

pub struct Engine;

impl Engine {
    pub fn start(_options: StartOptions) -> api::Result<Self> {
        Err("Wry has no web view on Android yet".into())
    }

    pub fn stop(&mut self) {}

    pub fn update(&mut self) {}

    pub fn create_browser(&mut self, _options: BrowserOptions) -> api::Result<BrowserId> {
        Err("Wry has no web view on Android yet".into())
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
