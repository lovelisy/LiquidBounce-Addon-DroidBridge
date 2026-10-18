//! What every platform's engine offers the game, independent of how it gets there.

use std::path::PathBuf;
use std::sync::Mutex;

pub type BrowserId = u64;
pub type Result<T> = std::result::Result<T, String>;

pub struct StartOptions {
    /// Where cookies, storage and caches of non-incognito browsers are kept.
    pub data_dir: PathBuf,
    /// Whether frames may be handed out as GPU handles the game imports, instead of pixels.
    pub gpu_frames: bool,
    /// Linux: the DRM render node the game renders on, which clients are told to allocate dmabufs on.
    pub render_node: Option<PathBuf>,
    /// Linux: DRM fourcc and modifier pairs the game can import, empty for the common ones.
    pub formats: Vec<(u32, u64)>,
}

pub struct BrowserOptions {
    pub url: String,
    /// Size of the texture in pixels.
    pub width: u32,
    pub height: u32,
    /// Page zoom, so the layout stays the same at a lower texture size.
    pub zoom: f64,
    pub incognito: bool,
    pub fps: u32,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
#[repr(i32)]
pub enum EventKind {
    /// `code` is the level: 0 debug, 1 info, 2 warning, 3 error.
    Log = 0,
    /// `text` is the URL.
    Loading = 1,
    /// `code` is the HTTP status, `text` the URL.
    Loaded = 2,
    /// `code` is the error code, `text` the URL, `detail` the description.
    Failed = 3,
    /// `text` is the new URL.
    Url = 4,
    /// A message of the page's console, `code` the level as for [EventKind::Log].
    Console = 5,
    /// `text` is the CSS cursor the page shows.
    Cursor = 6,
    /// A GPU buffer handed out before is gone, `value` is its id.
    BufferGone = 7,
}

#[derive(Debug)]
pub struct Event {
    pub browser: BrowserId,
    pub kind: EventKind,
    pub code: i32,
    pub value: i64,
    pub text: String,
    pub detail: String,
}

static EVENTS: Mutex<Vec<Event>> = Mutex::new(Vec::new());

pub fn push_event(browser: BrowserId, kind: EventKind, code: i32, text: impl Into<String>, detail: impl Into<String>) {
    push(Event { browser, kind, code, value: 0, text: text.into(), detail: detail.into() });
}

pub fn push(event: Event) {
    EVENTS.lock().unwrap().push(event);
}

pub fn take_events() -> Vec<Event> {
    std::mem::take(&mut *EVENTS.lock().unwrap())
}

pub fn log(level: i32, message: impl Into<String>) {
    push_event(0, EventKind::Log, level, message, "");
}

pub fn debug(message: impl Into<String>) {
    log(0, message);
}

pub fn info(message: impl Into<String>) {
    log(1, message);
}

pub fn warn(message: impl Into<String>) {
    log(2, message);
}

/// A frame of a browser, valid until the next frame of the same browser is taken.
pub enum Frame {
    /// Pixels in memory, 4 bytes each.
    Pixels { data: *const u8, len: usize, width: u32, height: u32, stride: u32, bgra: bool, flipped: bool },
    /// A Linux dmabuf; `id` stays the same for every frame in the same buffer.
    DmaBuf { id: u64, fds: Vec<i32>, offsets: Vec<u32>, strides: Vec<u32>, fourcc: u32, modifier: u64, width: u32, height: u32 },
    /// A shared D3D11 texture (B8G8R8A8), by its NT handle.
    SharedTexture { handle: i64, width: u32, height: u32 },
    /// An IOSurface (BGRA), by its `IOSurfaceRef`.
    IoSurface { surface: i64, width: u32, height: u32, flipped: bool },
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum MouseButton {
    Left,
    Middle,
    Right,
}

#[derive(Clone, Copy, Debug)]
pub enum Pointer {
    Move,
    Button(MouseButton, bool),
    /// Scroll steps, positive is up.
    Scroll(f64),
}

/// A key as SDL reports it to Minecraft.
#[derive(Clone, Copy, Debug)]
pub struct Key {
    pub pressed: bool,
    pub keycode: i32,
    pub scancode: i32,
    pub modifiers: i32,
}

pub const MOD_SHIFT: i32 = 0x0003;
pub const MOD_CTRL: i32 = 0x00c0;
pub const MOD_ALT: i32 = 0x0300;
pub const MOD_GUI: i32 = 0x0c00;

impl Key {
    /// The character the key stands for, when it types one on its own. Such keys only reach the page as text,
    /// since SDL reports the text separately and the page would get it twice.
    pub fn typed_character(&self) -> Option<char> {
        if self.modifiers & (MOD_CTRL | MOD_ALT | MOD_GUI) != 0 || self.keycode & (1 << 30) != 0 {
            return None;
        }
        char::from_u32(self.keycode as u32).filter(|c| !c.is_control())
    }
}
