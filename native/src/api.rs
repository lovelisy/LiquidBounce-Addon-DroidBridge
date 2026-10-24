//! What the engine offers the game.

use std::path::PathBuf;
use std::sync::Mutex;

pub type BrowserId = u64;
pub type Result<T> = std::result::Result<T, String>;

pub struct StartOptions {
    /// Where cookies, storage and caches of non-incognito browsers are kept.
    pub data_dir: PathBuf,
}

pub struct BrowserOptions {
    pub url: String,
    /// The size the page is laid out for.
    pub width: u32,
    pub height: u32,
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
}

#[derive(Debug)]
pub struct Event {
    pub browser: BrowserId,
    pub kind: EventKind,
    pub code: i32,
    pub text: String,
    pub detail: String,
}

static EVENTS: Mutex<Vec<Event>> = Mutex::new(Vec::new());

pub fn push_event(browser: BrowserId, kind: EventKind, code: i32, text: impl Into<String>, detail: impl Into<String>) {
    push(Event { browser, kind, code, text: text.into(), detail: detail.into() });
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

/// A key as Minecraft reports it.
#[derive(Clone, Copy, Debug)]
pub struct Key {
    pub pressed: bool,
    pub keycode: i32,
}
