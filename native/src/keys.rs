//! SDL scancodes, which Minecraft reports, to the key codes of each platform's webview.

#![allow(dead_code)]

pub struct KeyInfo {
    pub scancode: i32,
    /// Linux input event code.
    pub evdev: u32,
    /// GDK keysym of the key without modifiers, for keys that don't type a character.
    pub keysym: u32,
    /// Windows virtual key code.
    pub vk: u32,
    /// DOM `KeyboardEvent.code`.
    pub code: &'static str,
    /// DOM `KeyboardEvent.key` for keys that don't type a character.
    pub key: &'static str,
    /// macOS virtual key code.
    pub mac: u16,
    /// What AppKit reports as the characters of keys that don't type one.
    pub mac_chars: u32,
}

macro_rules! keys {
    ($($scancode:expr => $evdev:expr, $keysym:expr, $vk:expr, $code:expr, $key:expr, $mac:expr, $mac_chars:expr;)*) => {
        const KEYS: &[KeyInfo] = &[$(KeyInfo {
            scancode: $scancode, evdev: $evdev, keysym: $keysym, vk: $vk, code: $code, key: $key, mac: $mac,
            mac_chars: $mac_chars,
        }),*];
    };
}

keys! {
    4 => 30, 0x61, 0x41, "KeyA", "", 0x00, 0;
    5 => 48, 0x62, 0x42, "KeyB", "", 0x0B, 0;
    6 => 46, 0x63, 0x43, "KeyC", "", 0x08, 0;
    7 => 32, 0x64, 0x44, "KeyD", "", 0x02, 0;
    8 => 18, 0x65, 0x45, "KeyE", "", 0x0E, 0;
    9 => 33, 0x66, 0x46, "KeyF", "", 0x03, 0;
    10 => 34, 0x67, 0x47, "KeyG", "", 0x05, 0;
    11 => 35, 0x68, 0x48, "KeyH", "", 0x04, 0;
    12 => 23, 0x69, 0x49, "KeyI", "", 0x22, 0;
    13 => 36, 0x6a, 0x4A, "KeyJ", "", 0x26, 0;
    14 => 37, 0x6b, 0x4B, "KeyK", "", 0x28, 0;
    15 => 38, 0x6c, 0x4C, "KeyL", "", 0x25, 0;
    16 => 50, 0x6d, 0x4D, "KeyM", "", 0x2E, 0;
    17 => 49, 0x6e, 0x4E, "KeyN", "", 0x2D, 0;
    18 => 24, 0x6f, 0x4F, "KeyO", "", 0x1F, 0;
    19 => 25, 0x70, 0x50, "KeyP", "", 0x23, 0;
    20 => 16, 0x71, 0x51, "KeyQ", "", 0x0C, 0;
    21 => 19, 0x72, 0x52, "KeyR", "", 0x0F, 0;
    22 => 31, 0x73, 0x53, "KeyS", "", 0x01, 0;
    23 => 20, 0x74, 0x54, "KeyT", "", 0x11, 0;
    24 => 22, 0x75, 0x55, "KeyU", "", 0x20, 0;
    25 => 47, 0x76, 0x56, "KeyV", "", 0x09, 0;
    26 => 17, 0x77, 0x57, "KeyW", "", 0x0D, 0;
    27 => 45, 0x78, 0x58, "KeyX", "", 0x07, 0;
    28 => 21, 0x79, 0x59, "KeyY", "", 0x10, 0;
    29 => 44, 0x7a, 0x5A, "KeyZ", "", 0x06, 0;
    30 => 2, 0x31, 0x31, "Digit1", "", 0x12, 0;
    31 => 3, 0x32, 0x32, "Digit2", "", 0x13, 0;
    32 => 4, 0x33, 0x33, "Digit3", "", 0x14, 0;
    33 => 5, 0x34, 0x34, "Digit4", "", 0x15, 0;
    34 => 6, 0x35, 0x35, "Digit5", "", 0x17, 0;
    35 => 7, 0x36, 0x36, "Digit6", "", 0x16, 0;
    36 => 8, 0x37, 0x37, "Digit7", "", 0x1A, 0;
    37 => 9, 0x38, 0x38, "Digit8", "", 0x1C, 0;
    38 => 10, 0x39, 0x39, "Digit9", "", 0x19, 0;
    39 => 11, 0x30, 0x30, "Digit0", "", 0x1D, 0;
    40 => 28, 0xff0d, 0x0D, "Enter", "Enter", 0x24, 0x0d;
    41 => 1, 0xff1b, 0x1B, "Escape", "Escape", 0x35, 0x1b;
    42 => 14, 0xff08, 0x08, "Backspace", "Backspace", 0x33, 0x7f;
    43 => 15, 0xff09, 0x09, "Tab", "Tab", 0x30, 0x09;
    44 => 57, 0x20, 0x20, "Space", "", 0x31, 0;
    45 => 12, 0x2d, 0xBD, "Minus", "", 0x1B, 0;
    46 => 13, 0x3d, 0xBB, "Equal", "", 0x18, 0;
    47 => 26, 0x5b, 0xDB, "BracketLeft", "", 0x21, 0;
    48 => 27, 0x5d, 0xDD, "BracketRight", "", 0x1E, 0;
    49 => 43, 0x5c, 0xDC, "Backslash", "", 0x2A, 0;
    51 => 39, 0x3b, 0xBA, "Semicolon", "", 0x29, 0;
    52 => 40, 0x27, 0xDE, "Quote", "", 0x27, 0;
    53 => 41, 0x60, 0xC0, "Backquote", "", 0x32, 0;
    54 => 51, 0x2c, 0xBC, "Comma", "", 0x2B, 0;
    55 => 52, 0x2e, 0xBE, "Period", "", 0x2F, 0;
    56 => 53, 0x2f, 0xBF, "Slash", "", 0x2C, 0;
    57 => 58, 0xffe5, 0x14, "CapsLock", "CapsLock", 0x39, 0;
    58 => 59, 0xffbe, 0x70, "F1", "F1", 0x7A, 0xf704;
    59 => 60, 0xffbf, 0x71, "F2", "F2", 0x78, 0xf705;
    60 => 61, 0xffc0, 0x72, "F3", "F3", 0x63, 0xf706;
    61 => 62, 0xffc1, 0x73, "F4", "F4", 0x76, 0xf707;
    62 => 63, 0xffc2, 0x74, "F5", "F5", 0x60, 0xf708;
    63 => 64, 0xffc3, 0x75, "F6", "F6", 0x61, 0xf709;
    64 => 65, 0xffc4, 0x76, "F7", "F7", 0x62, 0xf70a;
    65 => 66, 0xffc5, 0x77, "F8", "F8", 0x64, 0xf70b;
    66 => 67, 0xffc6, 0x78, "F9", "F9", 0x65, 0xf70c;
    67 => 68, 0xffc7, 0x79, "F10", "F10", 0x6D, 0xf70d;
    68 => 87, 0xffc8, 0x7A, "F11", "F11", 0x67, 0xf70e;
    69 => 88, 0xffc9, 0x7B, "F12", "F12", 0x6F, 0xf70f;
    70 => 99, 0xff61, 0x2C, "PrintScreen", "PrintScreen", 0x69, 0xf710;
    71 => 70, 0xff14, 0x91, "ScrollLock", "ScrollLock", 0x6B, 0xf711;
    72 => 119, 0xff13, 0x13, "Pause", "Pause", 0x71, 0xf712;
    73 => 110, 0xff63, 0x2D, "Insert", "Insert", 0x72, 0xf727;
    74 => 102, 0xff50, 0x24, "Home", "Home", 0x73, 0xf729;
    75 => 104, 0xff55, 0x21, "PageUp", "PageUp", 0x74, 0xf72c;
    76 => 111, 0xffff, 0x2E, "Delete", "Delete", 0x75, 0xf728;
    77 => 107, 0xff57, 0x23, "End", "End", 0x77, 0xf72b;
    78 => 109, 0xff56, 0x22, "PageDown", "PageDown", 0x79, 0xf72d;
    79 => 106, 0xff53, 0x27, "ArrowRight", "ArrowRight", 0x7C, 0xf703;
    80 => 105, 0xff51, 0x25, "ArrowLeft", "ArrowLeft", 0x7B, 0xf702;
    81 => 108, 0xff54, 0x28, "ArrowDown", "ArrowDown", 0x7D, 0xf701;
    82 => 103, 0xff52, 0x26, "ArrowUp", "ArrowUp", 0x7E, 0xf700;
    83 => 69, 0xff7f, 0x90, "NumLock", "NumLock", 0x47, 0xf739;
    84 => 98, 0xffaf, 0x6F, "NumpadDivide", "", 0x4B, 0;
    85 => 55, 0xffaa, 0x6A, "NumpadMultiply", "", 0x43, 0;
    86 => 74, 0xffad, 0x6D, "NumpadSubtract", "", 0x4E, 0;
    87 => 78, 0xffab, 0x6B, "NumpadAdd", "", 0x45, 0;
    88 => 96, 0xff8d, 0x0D, "NumpadEnter", "Enter", 0x4C, 0x03;
    89 => 79, 0xffb1, 0x61, "Numpad1", "", 0x53, 0;
    90 => 80, 0xffb2, 0x62, "Numpad2", "", 0x54, 0;
    91 => 81, 0xffb3, 0x63, "Numpad3", "", 0x55, 0;
    92 => 75, 0xffb4, 0x64, "Numpad4", "", 0x56, 0;
    93 => 76, 0xffb5, 0x65, "Numpad5", "", 0x57, 0;
    94 => 77, 0xffb6, 0x66, "Numpad6", "", 0x58, 0;
    95 => 71, 0xffb7, 0x67, "Numpad7", "", 0x59, 0;
    96 => 72, 0xffb8, 0x68, "Numpad8", "", 0x5B, 0;
    97 => 73, 0xffb9, 0x69, "Numpad9", "", 0x5C, 0;
    98 => 82, 0xffb0, 0x60, "Numpad0", "", 0x52, 0;
    99 => 83, 0xffae, 0x6E, "NumpadDecimal", "", 0x41, 0;
    100 => 86, 0x3c, 0xE2, "IntlBackslash", "", 0x0A, 0;
    101 => 127, 0xff67, 0x5D, "ContextMenu", "ContextMenu", 0x6E, 0xf735;
    104 => 183, 0xffca, 0x7C, "F13", "F13", 0x69, 0xf710;
    105 => 184, 0xffcb, 0x7D, "F14", "F14", 0x6B, 0xf711;
    106 => 185, 0xffcc, 0x7E, "F15", "F15", 0x71, 0xf712;
    224 => 29, 0xffe3, 0x11, "ControlLeft", "Control", 0x3B, 0;
    225 => 42, 0xffe1, 0x10, "ShiftLeft", "Shift", 0x38, 0;
    226 => 56, 0xffe9, 0x12, "AltLeft", "Alt", 0x3A, 0;
    227 => 125, 0xffeb, 0x5B, "MetaLeft", "Meta", 0x37, 0;
    228 => 97, 0xffe4, 0x11, "ControlRight", "Control", 0x3E, 0;
    229 => 54, 0xffe2, 0x10, "ShiftRight", "Shift", 0x3C, 0;
    230 => 100, 0xffea, 0x12, "AltRight", "Alt", 0x3D, 0;
    231 => 126, 0xffec, 0x5C, "MetaRight", "Meta", 0x36, 0;
}

pub fn lookup(scancode: i32) -> Option<&'static KeyInfo> {
    KEYS.iter().find(|key| key.scancode == scancode)
}

/// The key that types `keysym` on a US layout.
pub fn by_keysym(keysym: u32) -> Option<&'static KeyInfo> {
    KEYS.iter().find(|key| key.keysym == keysym)
}

/// Modifier keys, which only change the state of the other keys.
pub fn is_modifier(scancode: i32) -> bool {
    (224..=231).contains(&scancode)
}
