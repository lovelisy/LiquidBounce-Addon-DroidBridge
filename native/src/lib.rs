//! The web view of Android, called from `net.ccbluex.liquidbounce.wry.WryNative`.

pub mod api;

mod android;
pub use android::Engine;

use api::{Key, MouseButton, Pointer, StartOptions};
use jni::objects::{JClass, JLongArray, JObject, JString};
use jni::sys::{jboolean, jdouble, jint, jlong, jobjectArray};
use jni::JNIEnv;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::{Mutex, MutexGuard};

struct Shared(Option<Engine>);

unsafe impl Send for Shared {}

static ENGINE: Mutex<Shared> = Mutex::new(Shared(None));

fn engine() -> MutexGuard<'static, Shared> {
    ENGINE.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}

/// Runs `call` and turns an error or a panic into an `IllegalStateException`.
fn guard<T: Default>(env: &mut JNIEnv, call: impl FnOnce(&mut JNIEnv) -> api::Result<T>) -> T {
    let error = match catch_unwind(AssertUnwindSafe(|| call(env))) {
        Ok(Ok(value)) => return value,
        Ok(Err(error)) => error,
        Err(panic) => panic.downcast_ref::<&str>().map(|s| s.to_string())
            .or_else(|| panic.downcast_ref::<String>().cloned())
            .unwrap_or_else(|| "unknown panic".into()),
    };
    if !env.exception_check().unwrap_or(false) {
        env.throw_new("java/lang/IllegalStateException", error).ok();
    }
    T::default()
}

/// Runs `call` with the started engine.
fn with_engine<T: Default>(env: &mut JNIEnv, call: impl FnOnce(&mut Engine, &mut JNIEnv) -> api::Result<T>) -> T {
    guard(env, |env| {
        let mut shared = engine();
        let engine = shared.0.as_mut().ok_or("DroidBridge is not started")?;
        call(engine, env)
    })
}

fn string(env: &mut JNIEnv, value: &JString) -> api::Result<String> {
    env.get_string(value).map(Into::into).map_err(|e| e.to_string())
}

#[no_mangle]
pub extern "system" fn Java_net_ccbluex_liquidbounce_wry_WryNative_start(mut env: JNIEnv, _: JClass, data_dir: JString,
    _gpu_frames: jboolean, _render_node: JString, _formats: JLongArray) {
    guard(&mut env, |env| {
        let mut shared = engine();
        if shared.0.is_some() {
            return Err("DroidBridge is already started".into());
        }
        let data_dir = string(env, &data_dir)?.into();
        shared.0 = Some(Engine::start(StartOptions { data_dir })?);
        Ok(())
    })
}

#[no_mangle]
pub extern "system" fn Java_net_ccbluex_liquidbounce_wry_WryNative_stop(mut env: JNIEnv, _: JClass) {
    guard(&mut env, |_| {
        let engine = engine().0.take();
        if let Some(mut engine) = engine {
            engine.stop();
        }
        Ok(())
    })
}

#[no_mangle]
pub extern "system" fn Java_net_ccbluex_liquidbounce_wry_WryNative_update(mut env: JNIEnv, _: JClass) {
    with_engine(&mut env, |engine, _| {
        engine.update();
        Ok(())
    })
}

#[no_mangle]
pub extern "system" fn Java_net_ccbluex_liquidbounce_wry_WryNative_createBrowser(mut env: JNIEnv, _: JClass, url: JString,
    width: jint, height: jint, zoom: jdouble, incognito: jboolean, fps: jint) -> jlong {
    with_engine(&mut env, |engine, env| {
        let options = api::BrowserOptions {
            url: string(env, &url)?,
            width: width.max(1) as u32,
            height: height.max(1) as u32,
            zoom,
            incognito: incognito != 0,
            fps: fps.max(1) as u32,
        };
        engine.create_browser(options).map(|id| id as jlong)
    })
}

macro_rules! browser_call {
    ($name:ident($($arg:ident: $ty:ty),*) => |$engine:ident, $env:ident, $id:ident| $body:expr) => {
        #[no_mangle]
        pub extern "system" fn $name(mut env: JNIEnv, _: JClass, id: jlong $(, $arg: $ty)*) {
            with_engine(&mut env, |$engine, $env| {
                let $id = id as api::BrowserId;
                let _ = &$env;
                $body;
                Ok(())
            })
        }
    };
}

browser_call!(Java_net_ccbluex_liquidbounce_wry_WryNative_closeBrowser() => |engine, env, id| engine.close_browser(id));
browser_call!(Java_net_ccbluex_liquidbounce_wry_WryNative_setVisible(visible: jboolean) => |engine, env, id| engine.set_visible(id, visible != 0));
browser_call!(Java_net_ccbluex_liquidbounce_wry_WryNative_navigate(url: JString) => |engine, env, id| {
    let url = string(env, &url)?;
    engine.navigate(id, url)
});
browser_call!(Java_net_ccbluex_liquidbounce_wry_WryNative_reload(ignore_cache: jboolean) => |engine, env, id|
    engine.reload(id, ignore_cache != 0));
browser_call!(Java_net_ccbluex_liquidbounce_wry_WryNative_goBack() => |engine, env, id| engine.go_back(id));
browser_call!(Java_net_ccbluex_liquidbounce_wry_WryNative_goForward() => |engine, env, id| engine.go_forward(id));
browser_call!(Java_net_ccbluex_liquidbounce_wry_WryNative_resize(width: jint, height: jint, zoom: jdouble) =>
    |engine, env, id| engine.resize(id, width.max(1) as u32, height.max(1) as u32, zoom));
browser_call!(Java_net_ccbluex_liquidbounce_wry_WryNative_setFps(fps: jint) => |engine, env, id|
    engine.set_fps(id, fps.max(1) as u32));
browser_call!(Java_net_ccbluex_liquidbounce_wry_WryNative_mouseMove(x: jdouble, y: jdouble) => |engine, env, id|
    engine.pointer(id, x, y, Pointer::Move));
browser_call!(Java_net_ccbluex_liquidbounce_wry_WryNative_mouseButton(x: jdouble, y: jdouble, button: jint,
    pressed: jboolean) => |engine, env, id| {
    let button = match button {
        1 => MouseButton::Middle,
        2 => MouseButton::Right,
        _ => MouseButton::Left,
    };
    engine.pointer(id, x, y, Pointer::Button(button, pressed != 0))
});
browser_call!(Java_net_ccbluex_liquidbounce_wry_WryNative_mouseScroll(x: jdouble, y: jdouble, steps: jdouble) =>
    |engine, env, id| engine.pointer(id, x, y, Pointer::Scroll(steps)));
browser_call!(Java_net_ccbluex_liquidbounce_wry_WryNative_focus() => |engine, env, id| engine.focus(id));
browser_call!(Java_net_ccbluex_liquidbounce_wry_WryNative_key(pressed: jboolean, keycode: jint, scancode: jint,
    modifiers: jint) => |engine, env, id| engine.key(id, Key { pressed: pressed != 0, keycode, scancode, modifiers }));
browser_call!(Java_net_ccbluex_liquidbounce_wry_WryNative_text(text: JString) => |engine, env, id| {
    let text = string(env, &text)?;
    engine.text(id, text)
});

/// Events since the last call as `WryEvent`s, or null when there are none.
#[no_mangle]
pub extern "system" fn Java_net_ccbluex_liquidbounce_wry_WryNative_pollEvents(mut env: JNIEnv, _: JClass) -> jobjectArray {
    guard(&mut env, |env| {
        let events = api::take_events();
        if events.is_empty() {
            return Ok(std::ptr::null_mut());
        }
        let err = |e: jni::errors::Error| e.to_string();
        let class = env.find_class("net/ccbluex/liquidbounce/wry/WryEvent").map_err(err)?;
        let array = env.new_object_array(events.len() as i32, &class, JObject::null()).map_err(err)?;
        for (index, event) in events.into_iter().enumerate() {
            let text = env.new_string(event.text).map_err(err)?;
            let detail = env.new_string(event.detail).map_err(err)?;
            let object = env.new_object(&class, "(JIIJLjava/lang/String;Ljava/lang/String;)V", &[
                (event.browser as jlong).into(),
                (event.kind as jint).into(),
                event.code.into(),
                event.value.into(),
                (&text).into(),
                (&detail).into(),
            ]).map_err(err)?;
            env.set_object_array_element(&array, index as i32, object).map_err(err)?;
            env.delete_local_ref(text).ok();
            env.delete_local_ref(detail).ok();
        }
        Ok(array.into_raw())
    })
}

/// The page is drawn by the system web view straight onto the game surface, so there is never a frame to hand out.
#[no_mangle]
pub extern "system" fn Java_net_ccbluex_liquidbounce_wry_WryNative_takeFrame(mut env: JNIEnv, _: JClass, id: jlong,
    out: JLongArray) -> jint {
    with_engine(&mut env, |engine, env| {
        let _ = engine.take_frame(id as api::BrowserId);
        let _ = env.set_long_array_region(&out, 0, &[0i64; 32]);
        Ok(0)
    })
}
