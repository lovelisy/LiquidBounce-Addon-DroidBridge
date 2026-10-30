mod art;
mod bridge;

use crate::api::{self, BrowserId, BrowserOptions, Key, MouseButton, Pointer, StartOptions};
use std::collections::HashMap;

struct Page {
    viewport: (u32, u32),
    url: String,
    announced: bool,
}

pub struct Engine {
    bridge: Option<bridge::Bridge>,
    next: BrowserId,
    pages: HashMap<BrowserId, Page>,
}

impl Engine {
    pub fn start(options: StartOptions) -> api::Result<Self> {
        let vm = art::find()?;
        let bridge = bridge::Bridge::attach(vm, &options.data_dir)?;
        let report = bridge.start()?;
        api::info(format!("[DroidBridge] {report}"));
        Ok(Self { bridge: Some(bridge), next: 1, pages: HashMap::new() })
    }

    pub fn stop(&mut self) {
        self.pages.clear();
        if let Some(bridge) = self.bridge.take() {
            if let Err(error) = bridge.close_all() {
                api::warn(format!("[DroidBridge] closing the web views failed: {error}"));
            }
        }
    }

    pub fn update(&mut self) {
        let Some(bridge) = self.bridge.as_ref() else {
            return;
        };
        for (id, page) in self.pages.iter_mut() {
            if page.announced {
                continue;
            }
            match bridge.loaded(*id as i64) {
                Ok(true) => {
                    page.announced = true;
                    api::push_event(*id, api::EventKind::Loaded, 200, page.url.clone(), "");
                }
                Ok(false) => {}
                Err(error) => {
                    page.announced = true;
                    api::warn(format!("[DroidBridge] checking the page state failed: {error}"));
                }
            }
        }
    }

    pub fn create_browser(&mut self, options: BrowserOptions) -> api::Result<BrowserId> {
        let id = self.next;
        self.next += 1;
        let bridge = self
            .bridge
            .as_ref()
            .ok_or_else(|| "the Android bridge is not running".to_string())?;
        api::info(format!(
            "[DroidBridge] browser {id} asked for {} ({}x{})",
            options.url, options.width, options.height
        ));
        let report = bridge.create(id as i64, &options.url)?;
        api::info(format!("[DroidBridge] {report}"));
        self.pages.insert(
            id,
            Page { viewport: (options.width.max(1), options.height.max(1)), url: options.url, announced: false },
        );
        Ok(id)
    }

    pub fn close_browser(&mut self, id: BrowserId) {
        self.pages.remove(&id);
        if let Some(bridge) = self.bridge.as_ref() {
            if let Err(error) = bridge.close(id as i64) {
                api::warn(format!("[DroidBridge] closing the web view failed: {error}"));
            }
        }
    }

    pub fn set_visible(&mut self, id: BrowserId, visible: bool) {
        let Some(bridge) = self.bridge.as_ref() else {
            return;
        };
        if let Err(error) = bridge.set_visible(id as i64, visible) {
            api::warn(format!("[DroidBridge] setting the web view visibility failed: {error}"));
        }
    }

    pub fn set_texture(&mut self, id: BrowserId, texture: bool) {
        let Some(bridge) = self.bridge.as_ref() else {
            return;
        };
        if let Err(error) = bridge.set_texture(id as i64, texture) {
            api::warn(format!("[DroidBridge] switching the web view to a texture failed: {error}"));
        }
    }

    /// The last frame of a page that is drawn on a texture, or an empty vector when there is none.
    pub fn snapshot(&mut self, id: BrowserId, last_version: i32) -> Vec<u8> {
        let Some(bridge) = self.bridge.as_ref() else {
            return Vec::new();
        };
        match bridge.snapshot(id as i64, last_version) {
            Ok(frame) => frame,
            Err(error) => {
                api::warn(format!("[DroidBridge] reading a frame of the web view failed: {error}"));
                Vec::new()
            }
        }
    }

    pub fn configure(&mut self, clickgui: f32, hud: f32, settings: f32) {
        let Some(bridge) = self.bridge.as_ref() else {
            return;
        };
        if let Err(error) = bridge.configure(clickgui, hud, settings) {
            api::warn(format!("[DroidBridge] configuring the page scale failed: {error}"));
        }
    }

    pub fn navigate(&mut self, _id: BrowserId, _url: String) {}

    pub fn reload(&mut self, _id: BrowserId, _ignore_cache: bool) {}

    pub fn go_back(&mut self, _id: BrowserId) {}

    pub fn go_forward(&mut self, _id: BrowserId) {}

    pub fn resize(&mut self, id: BrowserId, width: u32, height: u32, _zoom: f64) {
        if let Some(page) = self.pages.get_mut(&id) {
            page.viewport = (width.max(1), height.max(1));
        }
    }

    pub fn pointer(&mut self, id: BrowserId, x: f64, y: f64, pointer: Pointer) {
        let Some(bridge) = self.bridge.as_ref() else {
            return;
        };
        let (width, height) = match self.pages.get(&id) {
            Some(page) => page.viewport,
            None => return,
        };
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
        if let Err(error) = bridge.pointer(id as i64, nx, ny, action, button, amount) {
            api::warn(format!("[DroidBridge] forwarding a pointer event failed: {error}"));
        }
    }

    pub fn key(&mut self, id: BrowserId, key: Key) {
        let Some(bridge) = self.bridge.as_ref() else {
            return;
        };
        if let Err(error) = bridge.key(id as i64, key.pressed, key.keycode) {
            api::warn(format!("[DroidBridge] forwarding a key event failed: {error}"));
        }
    }

    pub fn text(&mut self, id: BrowserId, text: String) {
        let Some(bridge) = self.bridge.as_ref() else {
            return;
        };
        if let Err(error) = bridge.text(id as i64, &text) {
            api::warn(format!("[DroidBridge] forwarding typed text failed: {error}"));
        }
    }

    pub fn enter(&mut self, id: BrowserId) {
        let Some(bridge) = self.bridge.as_ref() else {
            return;
        };
        if let Err(error) = bridge.enter(id as i64) {
            api::warn(format!("[DroidBridge] forwarding enter failed: {error}"));
        }
    }
}
