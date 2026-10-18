//! The GTK thread. Each browser is an undecorated window on the private compositor with a Wry webview in it.

use super::TITLE_PREFIX;
use crate::api::{self, BrowserId, BrowserOptions, EventKind, Key, MOD_ALT, MOD_CTRL, MOD_GUI, MOD_SHIFT};
use crate::{keys, script};
use gtk::glib;
use gtk::prelude::*;
use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::ffi::CString;
use std::path::{Path, PathBuf};
use std::rc::Rc;
use std::sync::mpsc;
use std::time::Duration;
use webkit2gtk::{LoadEvent, NetworkError, URIResponseExt, WebResourceExt, WebViewExt as _};
use wry::{WebViewBuilderExtUnix, WebViewExtUnix};

struct Browser {
    window: gtk::Window,
    webview: wry::WebView,
}

struct State {
    context: wry::WebContext,
    browsers: HashMap<BrowserId, Browser>,
}

thread_local! {
    static STATE: RefCell<Option<State>> = const { RefCell::new(None) };
}

pub struct Gtk {
    stopped: mpsc::Receiver<()>,
}

impl Gtk {
    pub fn start(socket: &Path, data_dir: PathBuf) -> api::Result<Self> {
        let socket = socket.to_path_buf();
        let (ready_tx, ready_rx) = mpsc::channel();
        let (stopped_tx, stopped) = mpsc::channel();
        std::thread::Builder::new()
            .name("wry-gtk".into())
            .spawn(move || run(socket, data_dir, ready_tx, stopped_tx))
            .map_err(|e| e.to_string())?;
        ready_rx.recv_timeout(Duration::from_secs(20)).map_err(|_| "GTK did not start".to_string())??;
        Ok(Self { stopped })
    }

    /// Runs `task` on the GTK thread.
    fn run(task: impl FnOnce(&mut State) + Send + 'static) {
        glib::MainContext::default().invoke(move || {
            STATE.with(|state| {
                if let Some(state) = state.borrow_mut().as_mut() {
                    task(state);
                }
            })
        });
    }

    pub fn create(&self, id: BrowserId, options: BrowserOptions) {
        Self::run(move |state| {
            if let Err(error) = state.create(id, &options) {
                api::push_event(id, EventKind::Failed, -1, options.url, error);
            }
        });
    }

    pub fn close(&self, id: BrowserId) {
        Self::run(move |state| {
            if let Some(browser) = state.browsers.remove(&id) {
                drop(browser.webview);
                browser.window.close();
            }
        });
    }

    fn with_browser(id: BrowserId, task: impl FnOnce(&Browser) + Send + 'static) {
        Self::run(move |state| {
            if let Some(browser) = state.browsers.get(&id) {
                task(browser);
            }
        });
    }

    pub fn navigate(&self, id: BrowserId, url: String) {
        Self::with_browser(id, move |browser| {
            browser.webview.load_url(&url).ok();
        });
    }

    pub fn reload(&self, id: BrowserId, ignore_cache: bool) {
        Self::with_browser(id, move |browser| {
            let view = browser.webview.webview();
            if ignore_cache {
                view.reload_bypass_cache();
            } else {
                view.reload();
            }
        });
    }

    pub fn go_back(&self, id: BrowserId) {
        Self::with_browser(id, |browser| browser.webview.webview().go_back());
    }

    pub fn go_forward(&self, id: BrowserId) {
        Self::with_browser(id, |browser| browser.webview.webview().go_forward());
    }

    pub fn resize(&self, id: BrowserId, width: u32, height: u32, zoom: f64) {
        Self::with_browser(id, move |browser| {
            browser.window.resize(width as i32, height as i32);
            browser.webview.zoom(zoom).ok();
        });
    }

    pub fn focus(&self, id: BrowserId) {
        Self::with_browser(id, |browser| {
            let view = browser.webview.webview();
            if !view.has_focus() {
                view.grab_focus();
            }
        });
    }

    pub fn key(&self, id: BrowserId, key: Key) {
        Self::with_browser(id, move |browser| send_key(browser, &key));
    }

    pub fn text(&self, id: BrowserId, text: String) {
        Self::with_browser(id, move |browser| {
            browser.webview.webview().execute_editing_command_with_argument("InsertText", &text);
        });
    }

    pub fn stop(&mut self) {
        Self::run(|state| {
            for (_, browser) in state.browsers.drain() {
                drop(browser.webview);
                browser.window.close();
            }
            gtk::main_quit();
        });
        if self.stopped.recv_timeout(Duration::from_secs(5)).is_err() {
            api::warn("GTK did not stop in time");
        }
    }
}

fn run(socket: PathBuf, data_dir: PathBuf, ready: mpsc::Sender<api::Result<()>>, stopped: mpsc::Sender<()>) {
    // GTK only talks to the private compositor, whatever display the game uses
    gtk::gdk::set_allowed_backends("wayland");
    let args = ["liquidbounce", "--display", &socket.to_string_lossy()].map(|arg| CString::new(arg).unwrap());
    let mut argv: Vec<*mut std::ffi::c_char> = args.iter().map(|arg| arg.as_ptr() as *mut _).collect();
    let mut argc = argv.len() as i32;
    let mut argv_ptr = argv.as_mut_ptr();
    let initialized = unsafe {
        gtk::ffi::gtk_init_check(&mut argc, &mut argv_ptr) != 0
            && glib::ffi::g_main_context_acquire(glib::ffi::g_main_context_default()) != 0
    };
    if !initialized {
        ready.send(Err(format!("GTK could not open the display {}", socket.display()))).ok();
        return;
    }
    unsafe { gtk::set_initialized() };

    STATE.with(|state| {
        *state.borrow_mut() = Some(State { context: wry::WebContext::new(Some(data_dir)), browsers: HashMap::new() });
    });
    ready.send(Ok(())).ok();
    gtk::main();
    STATE.with(|state| state.borrow_mut().take());
    stopped.send(()).ok();
    // WebKit deadlocks tearing down its run loop when the thread it started on exits
    loop {
        std::thread::park();
    }
}

impl State {
    fn create(&mut self, id: BrowserId, options: &BrowserOptions) -> api::Result<()> {
        let window = gtk::Window::new(gtk::WindowType::Toplevel);
        window.set_title(&format!("{TITLE_PREFIX}{id}"));
        window.set_decorated(false);
        window.set_default_size(options.width as i32, options.height as i32);
        // A transparent window, so the page's own transparency reaches the game
        window.set_app_paintable(true);
        if let Some(visual) = WidgetExt::screen(&window).and_then(|screen| screen.rgba_visual()) {
            window.set_visual(Some(&visual));
        }
        window.connect_draw(|_, cr| {
            cr.set_operator(cairo::Operator::Source);
            cr.set_source_rgba(0.0, 0.0, 0.0, 0.0);
            cr.paint().ok();
            glib::Propagation::Proceed
        });
        let container = gtk::Box::new(gtk::Orientation::Vertical, 0);
        window.add(&container);

        let builder = if options.incognito {
            wry::WebViewBuilder::new().with_incognito(true)
        } else {
            wry::WebViewBuilder::new_with_web_context(&mut self.context)
        };
        let webview = builder
            .with_url(&options.url)
            .with_transparent(true)
            .with_hotkeys_zoom(false)
            .with_initialization_script(script::INIT_SCRIPT)
            .with_ipc_handler(move |request| script::dispatch(id, request.body()))
            .with_new_window_req_handler(|_, _| wry::NewWindowResponse::Deny)
            .build_gtk(&container)
            .map_err(|e| e.to_string())?;
        webview.zoom(options.zoom).ok();
        connect(id, &webview.webview());
        window.show_all();
        self.browsers.insert(id, Browser { window, webview });
        Ok(())
    }
}

fn connect(id: BrowserId, view: &webkit2gtk::WebView) {
    let failed = Rc::new(Cell::new(false));
    let uri = |view: &webkit2gtk::WebView| view.uri().map(|uri| uri.to_string()).unwrap_or_default();

    let load_failed = failed.clone();
    view.connect_load_changed(move |view, event| match event {
        LoadEvent::Started => {
            load_failed.set(false);
            api::push_event(id, EventKind::Loading, 0, uri(view), "");
        }
        LoadEvent::Finished if !load_failed.get() => {
            let status = view.main_resource().and_then(|r| r.response()).map(|r| r.status_code()).unwrap_or(0);
            api::push_event(id, EventKind::Loaded, status as i32, uri(view), "");
        }
        _ => {}
    });
    view.connect_load_failed(move |_, _, failing_uri, error| {
        if error.matches(NetworkError::Cancelled) {
            return false;
        }
        failed.set(true);
        let code = unsafe { (*glib::translate::ToGlibPtr::<*const glib::ffi::GError>::to_glib_none(error).0).code };
        api::push_event(id, EventKind::Failed, code, failing_uri, error.message());
        // No error page of its own, which would count as loaded
        true
    });
    view.connect_uri_notify(move |view| api::push_event(id, EventKind::Url, 0, uri(view), ""));
    // Menus can't be shown on the compositor
    view.connect_context_menu(|_, _, _, _| true);
    view.connect_web_process_terminated(move |_, reason| {
        api::push_event(id, EventKind::Failed, -2, "", format!("The page's process ended: {reason:?}"));
    });
}

const GDK_KEY_PRESS: i32 = 8;
const GDK_KEY_RELEASE: i32 = 9;

fn modifier_state(modifiers: i32) -> u32 {
    let mut state = 0;
    if modifiers & MOD_SHIFT != 0 {
        state |= gtk::gdk::ffi::GDK_SHIFT_MASK;
    }
    if modifiers & MOD_CTRL != 0 {
        state |= gtk::gdk::ffi::GDK_CONTROL_MASK;
    }
    if modifiers & MOD_ALT != 0 {
        state |= gtk::gdk::ffi::GDK_MOD1_MASK;
    }
    if modifiers & MOD_GUI != 0 {
        state |= gtk::gdk::ffi::GDK_MOD4_MASK | gtk::gdk::ffi::GDK_SUPER_MASK;
    }
    state
}

/// Hands a key straight to the window, in order with the text typed through the same thread.
fn send_key(browser: &Browser, key: &Key) {
    if key.typed_character().is_some() {
        return;
    }
    let by_scancode = keys::lookup(key.scancode);
    // Shortcuts follow the layout: the key that types the same letter on a US layout, which the keymap uses
    let (keyval, info) = match char::from_u32(key.keycode as u32).filter(|c| c.is_ascii_graphic()) {
        Some(c) if by_scancode.is_none_or(|info| info.key.is_empty()) => {
            let c = c.to_ascii_lowercase();
            (unsafe { gtk::gdk::ffi::gdk_unicode_to_keyval(c as u32) }, keys::by_keysym(c as u32).or(by_scancode))
        }
        _ => match by_scancode {
            Some(info) => (info.keysym, Some(info)),
            None => return,
        },
    };
    // Keys that type, the numpad's among them, reach the page as text
    let typed = unsafe { char::from_u32(gtk::gdk::ffi::gdk_keyval_to_unicode(keyval)) };
    if key.modifiers & (MOD_CTRL | MOD_ALT | MOD_GUI) == 0 && typed.is_some_and(|c| c != '\0' && !c.is_control()) {
        return;
    }
    let Some(window) = browser.window.window() else { return };

    unsafe {
        use glib::translate::ToGlibPtr;
        let event = gtk::gdk::ffi::gdk_event_new(if key.pressed { GDK_KEY_PRESS } else { GDK_KEY_RELEASE });
        let key_event = &mut (*event).key;
        key_event.window = glib::gobject_ffi::g_object_ref(window.as_ptr() as *mut _) as *mut _;
        key_event.send_event = 1;
        key_event.time = gtk::gdk::ffi::GDK_CURRENT_TIME as u32;
        key_event.state = modifier_state(key.modifiers);
        key_event.keyval = keyval;
        key_event.string = glib::ffi::g_strdup(c"".as_ptr());
        key_event.hardware_keycode = info.map(|info| info.evdev as u16 + 8).unwrap_or(0);
        key_event.is_modifier = keys::is_modifier(key.scancode) as u32;
        let display: *mut gtk::gdk::ffi::GdkDisplay = window.display().to_glib_none().0;
        let seat = gtk::gdk::ffi::gdk_display_get_default_seat(display);
        if !seat.is_null() {
            gtk::gdk::ffi::gdk_event_set_device(event, gtk::gdk::ffi::gdk_seat_get_keyboard(seat));
        }
        gtk::ffi::gtk_main_do_event(event);
        gtk::gdk::ffi::gdk_event_free(event);
    }
}
