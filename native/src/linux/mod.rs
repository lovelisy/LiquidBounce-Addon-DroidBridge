//! Linux: WebKitGTK windows on a private Wayland compositor, see [compositor].

mod compositor;
mod gtk;

use crate::api::{self, BrowserId, BrowserOptions, Frame, Key, Pointer, StartOptions};
use compositor::{Command, Compositor};
use smithay::backend::allocator::{dmabuf::Dmabuf, Buffer as _};
use smithay::reexports::wayland_server::protocol::wl_buffer::WlBuffer;
use std::collections::HashMap;
use std::os::unix::fs::{DirBuilderExt, PermissionsExt};
use std::path::PathBuf;
use std::sync::{Arc, Mutex};

/// Windows are titled with this and their browser's id.
const TITLE_PREFIX: &str = "liquidbounce-wry-";

struct ShmFrame {
    data: Vec<u8>,
    width: u32,
    height: u32,
    stride: u32,
}

struct DmaBufFrame {
    id: u64,
    buffer: WlBuffer,
    dmabuf: Dmabuf,
}

/// Only the newest frame waits for the game, in memory or as a dmabuf.
#[derive(Default)]
struct BrowserSlot {
    /// The newest frame the game didn't take yet.
    shm: Option<ShmFrame>,
    /// The frame the game took last, which it may still read.
    front: Option<ShmFrame>,
    spare: Option<Vec<u8>>,
    dmabuf: Option<DmaBufFrame>,
    shown: Option<DmaBufFrame>,
}

type Slots = Arc<Mutex<HashMap<BrowserId, BrowserSlot>>>;

pub struct Engine {
    compositor: Compositor,
    gtk: gtk::Gtk,
    slots: Slots,
    next_id: BrowserId,
}

fn socket_path() -> api::Result<PathBuf> {
    let name = format!("liquidbounce-wry-{}", std::process::id());
    if let Some(runtime) = std::env::var_os("XDG_RUNTIME_DIR").map(PathBuf::from).filter(|dir| dir.is_dir()) {
        return Ok(runtime.join(name));
    }
    let dir = std::env::temp_dir().join(format!("liquidbounce-wry-{}", unsafe { libc::getuid() }));
    std::fs::DirBuilder::new().recursive(true).mode(0o700).create(&dir).map_err(|e| format!("{}: {e}", dir.display()))?;
    std::fs::set_permissions(&dir, std::fs::Permissions::from_mode(0o700)).ok();
    // GTK doesn't open a Wayland display without it; only happens outside a desktop session, before GTK runs
    unsafe { std::env::set_var("XDG_RUNTIME_DIR", &dir) };
    Ok(dir.join(name))
}

impl Engine {
    pub fn start(options: StartOptions) -> api::Result<Self> {
        let socket = socket_path()?;
        if std::env::var_os("WAYLAND_DISPLAY").is_none() && std::env::var_os("DISPLAY").is_none() {
            // WebKit's web processes start GTK themselves and need a display, only missing outside a desktop session
            unsafe { std::env::set_var("WAYLAND_DISPLAY", &socket) };
        }
        let slots = Slots::default();
        let render_node = options.render_node.filter(|_| options.gpu_frames);
        let mut compositor = Compositor::start(&socket, slots.clone(), render_node.as_deref(), options.formats)?;
        let gtk = match gtk::Gtk::start(&socket, options.data_dir) {
            Ok(gtk) => gtk,
            Err(error) => {
                compositor.stop();
                return Err(error);
            }
        };
        api::info(format!("WebKitGTK {}.{}.{} on {}", webkit_version().0, webkit_version().1, webkit_version().2,
            socket.display()));
        Ok(Self { compositor, gtk, slots, next_id: 0 })
    }

    pub fn stop(&mut self) {
        self.gtk.stop();
        self.compositor.stop();
        self.slots.lock().unwrap().clear();
    }

    pub fn update(&mut self) {}

    pub fn create_browser(&mut self, options: BrowserOptions) -> api::Result<BrowserId> {
        self.next_id += 1;
        let id = self.next_id;
        self.slots.lock().unwrap().insert(id, BrowserSlot::default());
        self.compositor.send(Command::Size { browser: id, width: options.width, height: options.height });
        self.compositor.send(Command::Fps { browser: id, fps: options.fps });
        self.gtk.create(id, options);
        Ok(id)
    }

    pub fn close_browser(&mut self, id: BrowserId) {
        self.gtk.close(id);
        self.compositor.send(Command::Remove(id));
        if let Some(slot) = self.slots.lock().unwrap().remove(&id) {
            for frame in [slot.dmabuf, slot.shown].into_iter().flatten() {
                self.compositor.send(Command::Release(frame.buffer));
            }
        }
    }

    pub fn navigate(&mut self, id: BrowserId, url: String) {
        self.gtk.navigate(id, url);
    }

    pub fn reload(&mut self, id: BrowserId, ignore_cache: bool) {
        self.gtk.reload(id, ignore_cache);
    }

    pub fn go_back(&mut self, id: BrowserId) {
        self.gtk.go_back(id);
    }

    pub fn go_forward(&mut self, id: BrowserId) {
        self.gtk.go_forward(id);
    }

    pub fn resize(&mut self, id: BrowserId, width: u32, height: u32, zoom: f64) {
        self.compositor.send(Command::Size { browser: id, width, height });
        self.gtk.resize(id, width, height, zoom);
    }

    pub fn set_fps(&mut self, id: BrowserId, fps: u32) {
        self.compositor.send(Command::Fps { browser: id, fps });
    }

    pub fn pointer(&mut self, id: BrowserId, x: f64, y: f64, pointer: Pointer) {
        self.compositor.send(Command::Pointer { browser: id, x, y, pointer });
    }

    pub fn focus(&mut self, id: BrowserId) {
        self.compositor.send(Command::Focus(id));
        self.gtk.focus(id);
    }

    pub fn key(&mut self, id: BrowserId, key: Key) {
        self.gtk.key(id, key);
    }

    pub fn text(&mut self, id: BrowserId, text: String) {
        self.gtk.text(id, text);
    }

    pub fn take_frame(&mut self, id: BrowserId) -> Option<Frame> {
        let mut slots = self.slots.lock().unwrap();
        let slot = slots.get_mut(&id)?;

        if let Some(frame) = slot.dmabuf.take() {
            if let Some(previous) = slot.shown.take() {
                if previous.id != frame.id {
                    self.compositor.send(Command::Release(previous.buffer));
                }
            }
            let shown = slot.shown.insert(frame);
            let dmabuf = &shown.dmabuf;
            let format = dmabuf.format();
            return Some(Frame::DmaBuf {
                id: shown.id,
                fds: compositor::raw_fds(dmabuf),
                offsets: dmabuf.offsets().collect(),
                strides: dmabuf.strides().collect(),
                fourcc: format.code as u32,
                modifier: format.modifier.into(),
                width: dmabuf.width(),
                height: dmabuf.height(),
            });
        }

        let frame = slot.shm.take()?;
        if let Some(previous) = slot.shown.take() {
            self.compositor.send(Command::Release(previous.buffer));
        }
        if let Some(previous) = slot.front.replace(frame) {
            slot.spare.get_or_insert(previous.data);
        }
        let front = slot.front.as_ref().unwrap();
        Some(Frame::Pixels {
            data: front.data.as_ptr(),
            len: front.data.len(),
            width: front.width,
            height: front.height,
            stride: front.stride,
            // ARGB8888 in little endian, premultiplied
            bgra: true,
            flipped: false,
        })
    }
}

fn webkit_version() -> (u32, u32, u32) {
    unsafe {
        (webkit2gtk::ffi::webkit_get_major_version(), webkit2gtk::ffi::webkit_get_minor_version(),
            webkit2gtk::ffi::webkit_get_micro_version())
    }
}
