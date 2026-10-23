use crate::api;
use jni::objects::{GlobalRef, JClass, JObject, JString, JValue};
use jni::JavaVM;
use std::fs;
use std::path::Path;

const DEX: &[u8] = include_bytes!("../../android-bridge.dex");
const CLASS: &str = "net.ccbluex.liquidbounce.wry.android.WryBridge";
const FOLDER: &str = "liquidbounce-wry";

macro_rules! jni {
    ($env:expr, $expression:expr, $context:expr) => {
        match $expression {
            Ok(value) => value,
            Err(error) => {
                return Err(format!("{}: {} ({})", $context, error, take_exception(&mut $env)));
            }
        }
    };
}

pub struct Bridge {
    vm: JavaVM,
    class: GlobalRef,
}

impl Bridge {
    pub fn attach(vm: JavaVM, data_dir: &Path) -> api::Result<Self> {
        let mut env = vm
            .attach_current_thread_permanently()
            .map_err(|error| format!("attaching to ART failed: {error}"))?;

        let folder = if std::env::temp_dir().is_dir() { std::env::temp_dir() } else { data_dir.to_path_buf() }
            .join(FOLDER);
        fs::create_dir_all(&folder).map_err(|error| format!("creating {} failed: {error}", folder.display()))?;

        let dex = folder.join("bridge.dex");
        let stale = match fs::read(&dex) {
            Ok(bytes) => bytes.as_slice() != &DEX[..],
            Err(_) => true,
        };
        if stale {
            let _ = fs::remove_file(&dex);
            fs::write(&dex, DEX).map_err(|error| format!("writing {} failed: {error}", dex.display()))?;
        }

        let mut permissions = fs::metadata(&dex)
            .map_err(|error| format!("reading {} failed: {error}", dex.display()))?
            .permissions();
        if !permissions.readonly() {
            permissions.set_readonly(true);
            fs::set_permissions(&dex, permissions)
                .map_err(|error| format!("making {} read-only failed: {error}", dex.display()))?;
        }

        let application = match env.call_static_method(
            "android/app/ActivityThread",
            "currentApplication",
            "()Landroid/app/Application;",
            &[],
        ) {
            Ok(value) => value.l().ok().filter(|value| !value.is_null()),
            Err(error) => {
                let detail = take_exception(&mut env);
                return Err(format!("ActivityThread.currentApplication failed: {error} ({detail})"));
            }
        };

        let parent = match &application {
            Some(application) => jni!(
                env,
                env.call_method(application, "getClassLoader", "()Ljava/lang/ClassLoader;", &[]),
                "Application.getClassLoader"
            )
            .l()
            .map_err(|error| format!("Application.getClassLoader returned nothing: {error}"))?,
            None => jni!(
                env,
                env.call_static_method(
                    "java/lang/ClassLoader",
                    "getSystemClassLoader",
                    "()Ljava/lang/ClassLoader;",
                    &[],
                ),
                "ClassLoader.getSystemClassLoader"
            )
            .l()
            .map_err(|error| format!("ClassLoader.getSystemClassLoader returned nothing: {error}"))?,
        };

        let dex_name = jni!(env, env.new_string(dex.to_string_lossy().as_ref()), "creating the dex path");

        let loader = jni!(
            env,
            env.new_object(
                "dalvik/system/DexClassLoader",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/ClassLoader;)V",
                &[
                    JValue::Object(&JObject::from(dex_name)),
                    JValue::Object(&JObject::null()),
                    JValue::Object(&JObject::null()),
                    JValue::Object(&parent),
                ],
            ),
            "creating a DexClassLoader"
        );

        let class_name = jni!(env, env.new_string(CLASS), "creating the class name");
        let class = jni!(
            env,
            env.call_method(&loader, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;", &[
                JValue::Object(&JObject::from(class_name)),
            ]),
            "loading the bridge class"
        )
        .l()
        .map_err(|error| format!("loading {CLASS} returned nothing: {error}"))?;
        let class = jni!(env, env.new_global_ref(class), "keeping the bridge class");

        Ok(Self { vm, class })
    }

    pub fn probe(&self) -> api::Result<String> {
        let mut env = self
            .vm
            .attach_current_thread_permanently()
            .map_err(|error| format!("attaching to ART failed: {error}"))?;

        let application = jni!(
            env,
            env.call_static_method(
                "android/app/ActivityThread",
                "currentApplication",
                "()Landroid/app/Application;",
                &[],
            ),
            "ActivityThread.currentApplication"
        )
        .l()
        .map_err(|error| format!("ActivityThread.currentApplication returned nothing: {error}"))?;
        if application.is_null() {
            return Err("ActivityThread.currentApplication returned null".into());
        }

        let class = unsafe { JClass::from_raw(self.class.as_raw()) };
        let started = jni!(
            env,
            env.call_static_method(class, "start", "(Landroid/app/Application;)Ljava/lang/String;", &[
                JValue::Object(&application),
            ]),
            "WryBridge.start"
        )
        .l()
        .map_err(|error| format!("WryBridge.start returned nothing: {error}"))?;

        let class = unsafe { JClass::from_raw(self.class.as_raw()) };
        let probed = jni!(
            env,
            env.call_static_method(class, "probe", "()Ljava/lang/String;", &[]),
            "WryBridge.probe"
        )
        .l()
        .map_err(|error| format!("WryBridge.probe returned nothing: {error}"))?;

        Ok(format!("{} | {}", read(&mut env, started)?, read(&mut env, probed)?))
    }
}

fn take_exception(env: &mut jni::JNIEnv) -> String {
    let throwable = match env.exception_occurred() {
        Ok(throwable) if !throwable.is_null() => throwable,
        _ => return "no Java exception".into(),
    };
    let _ = env.exception_clear();
    let text = env
        .call_method(&throwable, "toString", "()Ljava/lang/String;", &[])
        .map_err(|error| error.to_string())
        .and_then(|value| value.l().map_err(|error| error.to_string()))
        .and_then(|value| {
            env.get_string(&JString::from(value))
                .map(|text| -> String { text.into() })
                .map_err(|error| error.to_string())
        });
    let _ = env.exception_clear();
    text.unwrap_or_else(|error| format!("unreadable exception: {error}"))
}

fn read(env: &mut jni::JNIEnv, value: JObject) -> api::Result<String> {
    let value = JString::from(value);
    let text = env
        .get_string(&value)
        .map_err(|error| format!("reading the bridge's answer failed: {error}"))?;
    Ok(text.into())
}
