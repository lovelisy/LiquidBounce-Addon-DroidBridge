//! What the add-on runs in every page before the page's own scripts.

/// Forwards the console and the cursor the page shows, which no webview reports for pages drawn off-screen.
pub const INIT_SCRIPT: &str = r#"
(() => {
    if (window.__liquidbounceWry) return;
    window.__liquidbounceWry = true;

    const post = (message) => {
        try {
            const text = JSON.stringify(message);
            if (window.ipc && window.ipc.postMessage) window.ipc.postMessage(text);
            else if (window.chrome && window.chrome.webview) window.chrome.webview.postMessage(text);
        } catch (_) {}
    };
    const format = (values) => values.map((value) => {
        if (typeof value === 'string') return value;
        if (value instanceof Error) return value.stack || String(value);
        try { return JSON.stringify(value); } catch (_) { return String(value); }
    }).join(' ');

    for (const level of ['debug', 'log', 'info', 'warn', 'error']) {
        const original = console[level];
        console[level] = function (...values) {
            post({ t: 'console', l: level, m: format(values) });
            return original.apply(this, values);
        };
    }
    addEventListener('error', (event) =>
        post({ t: 'console', l: 'error', m: `${event.message} (${event.filename}:${event.lineno}:${event.colno})` }));
    addEventListener('unhandledrejection', (event) =>
        post({ t: 'console', l: 'error', m: 'Unhandled rejection: ' + format([event.reason]) }));

    const cursorOf = (element) => {
        if (!(element instanceof Element)) return 'default';
        const cursor = getComputedStyle(element).cursor;
        if (cursor && cursor !== 'auto') return cursor.split(',').pop().trim();
        if (element.closest('a[href]')) return 'pointer';
        if (element.isContentEditable || element.matches('textarea, input:not([type=button], [type=submit], [type=reset], [type=checkbox], [type=radio], [type=range], [type=color], [type=file])')) return 'text';
        return 'default';
    };
    // The page's own handlers still run; native menus can't be shown and would block the game on macOS
    addEventListener('contextmenu', (event) => event.preventDefault(), { capture: true });

    let cursor = 'default';
    addEventListener('mousemove', (event) => {
        const next = cursorOf(event.target);
        if (next !== cursor) {
            cursor = next;
            post({ t: 'cursor', c: cursor });
        }
    }, { capture: true, passive: true });
})();
"#;

pub enum Message {
    Console { level: i32, text: String },
    Cursor(String),
}

/// Reads a message [INIT_SCRIPT] posted.
pub fn parse(body: &str) -> Option<Message> {
    let value: serde_json::Value = serde_json::from_str(body).ok()?;
    match value.get("t")?.as_str()? {
        "console" => {
            let level = match value.get("l")?.as_str()? {
                "debug" => 0,
                "warn" => 2,
                "error" => 3,
                _ => 1,
            };
            Some(Message::Console { level, text: value.get("m")?.as_str()?.to_owned() })
        }
        "cursor" => Some(Message::Cursor(value.get("c")?.as_str()?.to_owned())),
        _ => None,
    }
}

/// Hands a message of a page to the game.
pub fn dispatch(browser: crate::api::BrowserId, body: &str) {
    use crate::api::{push_event, EventKind};
    match parse(body) {
        Some(Message::Console { level, text }) => push_event(browser, EventKind::Console, level, text, ""),
        Some(Message::Cursor(cursor)) => push_event(browser, EventKind::Cursor, 0, cursor, ""),
        None => {}
    }
}
