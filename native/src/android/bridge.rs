use crate::api;
use jni::objects::{GlobalRef, JClass, JObject, JString, JValue};
use jni::JavaVM;
use std::fs;
use std::path::Path;

const DEX: &[u8] = include_bytes!("../../android-bridge.dex");
const CLASS: &str = "liquidbounce.DroidBridge.WryBridge";
const FOLDER: &str = "liquidbounce-droidbridge";

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

    pub fn start(&self) -> api::Result<String> {
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

        let suffix = jni!(env, env.new_string("wry"), "creating the data suffix");
        let class = unsafe { JClass::from_raw(self.class.as_raw()) };
        let started = jni!(
            env,
            env.call_static_method(
                class,
                "start",
                "(Landroid/app/Application;Ljava/lang/String;)Ljava/lang/String;",
                &[
                    JValue::Object(&application),
                    JValue::Object(&JObject::from(suffix)),
                ],
            ),
            "WryBridge.start"
        )
        .l()
        .map_err(|error| format!("WryBridge.start returned nothing: {error}"))?;

        read(&mut env, started)
    }

    pub fn create(&self, id: i64, url: &str) -> api::Result<String> {
        let mut env = self
            .vm
            .attach_current_thread_permanently()
            .map_err(|error| format!("attaching to ART failed: {error}"))?;

        let activity = self.activity(&mut env)?;
        let url = jni!(env, env.new_string(url), "creating the url");
        let class = unsafe { JClass::from_raw(self.class.as_raw()) };
        let created = jni!(
            env,
            env.call_static_method(
                class,
                "create",
                "(Landroid/app/Activity;JLjava/lang/String;)Ljava/lang/String;",
                &[
                    JValue::Object(&activity),
                    JValue::Long(id),
                    JValue::Object(&JObject::from(url)),
                ],
            ),
            "WryBridge.create"
        )
        .l()
        .map_err(|error| format!("WryBridge.create returned nothing: {error}"))?;

        read(&mut env, created)
    }

    pub fn close(&self, id: i64) -> api::Result<String> {
        let mut env = self
            .vm
            .attach_current_thread_permanently()
            .map_err(|error| format!("attaching to ART failed: {error}"))?;

        let class = unsafe { JClass::from_raw(self.class.as_raw()) };
        let closed = jni!(
            env,
            env.call_static_method(class, "close", "(J)Ljava/lang/String;", &[JValue::Long(id)]),
            "WryBridge.close"
        )
        .l()
        .map_err(|error| format!("WryBridge.close returned nothing: {error}"))?;

        read(&mut env, closed)
    }

    pub fn close_all(&self) -> api::Result<String> {
        let mut env = self
            .vm
            .attach_current_thread_permanently()
            .map_err(|error| format!("attaching to ART failed: {error}"))?;

        let class = unsafe { JClass::from_raw(self.class.as_raw()) };
        let closed = jni!(
            env,
            env.call_static_method(class, "closeAll", "()Ljava/lang/String;", &[]),
            "WryBridge.closeAll"
        )
        .l()
        .map_err(|error| format!("WryBridge.closeAll returned nothing: {error}"))?;

        read(&mut env, closed)
    }

    pub fn set_visible(&self, id: i64, visible: bool) -> api::Result<String> {
        let mut env = self
            .vm
            .attach_current_thread_permanently()
            .map_err(|error| format!("attaching to ART failed: {error}"))?;

        let class = unsafe { JClass::from_raw(self.class.as_raw()) };
        let report = jni!(
            env,
            env.call_static_method(
                class,
                "setVisible",
                "(JZ)Ljava/lang/String;",
                &[JValue::Long(id), JValue::Bool(visible as u8)],
            ),
            "WryBridge.setVisible"
        )
        .l()
        .map_err(|error| format!("WryBridge.setVisible returned nothing: {error}"))?;

        read(&mut env, report)
    }

    pub fn pointer(&self, id: i64, nx: f64, ny: f64, action: i32, button: i32, amount: f64)
        -> api::Result<()> {
        let mut env = self
            .vm
            .attach_current_thread_permanently()
            .map_err(|error| format!("attaching to ART failed: {error}"))?;

        let class = unsafe { JClass::from_raw(self.class.as_raw()) };
        jni!(
            env,
            env.call_static_method(
                class,
                "pointer",
                "(JFFIIF)V",
                &[
                    JValue::Long(id),
                    JValue::Float(nx as f32),
                    JValue::Float(ny as f32),
                    JValue::Int(action),
                    JValue::Int(button),
                    JValue::Float(amount as f32),
                ],
            ),
            "WryBridge.pointer"
        );

        Ok(())
    }

    pub fn key(&self, id: i64, pressed: bool, keycode: i32) -> api::Result<()> {
        let mut env = self
            .vm
            .attach_current_thread_permanently()
            .map_err(|error| format!("attaching to ART failed: {error}"))?;

        let class = unsafe { JClass::from_raw(self.class.as_raw()) };
        jni!(
            env,
            env.call_static_method(
                class,
                "key",
                "(JIZ)V",
                &[JValue::Long(id), JValue::Int(keycode), JValue::Bool(pressed as u8)],
            ),
            "WryBridge.key"
        );

        Ok(())
    }

    pub fn text(&self, id: i64, value: &str) -> api::Result<()> {
        let mut env = self
            .vm
            .attach_current_thread_permanently()
            .map_err(|error| format!("attaching to ART failed: {error}"))?;

        let class = unsafe { JClass::from_raw(self.class.as_raw()) };
        let text = jni!(env, env.new_string(value), "creating the typed text");
        let text = JObject::from(text);
        jni!(
            env,
            env.call_static_method(
                class,
                "text",
                "(JLjava/lang/String;)V",
                &[JValue::Long(id), JValue::Object(&text)],
            ),
            "WryBridge.text"
        );

        Ok(())
    }

    pub fn enter(&self, id: i64) -> api::Result<()> {
        let mut env = self
            .vm
            .attach_current_thread_permanently()
            .map_err(|error| format!("attaching to ART failed: {error}"))?;

        let class = unsafe { JClass::from_raw(self.class.as_raw()) };
        jni!(
            env,
            env.call_static_method(class, "enter", "(J)V", &[JValue::Long(id)]),
            "WryBridge.enter"
        );

        Ok(())
    }

    pub fn loaded(&self, id: i64) -> api::Result<bool> {
        let mut env = self
            .vm
            .attach_current_thread_permanently()
            .map_err(|error| format!("attaching to ART failed: {error}"))?;

        let class = unsafe { JClass::from_raw(self.class.as_raw()) };
        let value = jni!(
            env,
            env.call_static_method(class, "loaded", "(J)Z", &[JValue::Long(id)]),
            "WryBridge.loaded"
        )
        .z()
        .map_err(|error| format!("WryBridge.loaded returned nothing: {error}"))?;

        Ok(value)
    }

    pub fn poll_logs(&self) -> api::Result<Vec<String>> {
        let mut env = self
            .vm
            .attach_current_thread_permanently()
            .map_err(|error| format!("attaching to ART failed: {error}"))?;

        let class = unsafe { JClass::from_raw(self.class.as_raw()) };
        let drained = jni!(
            env,
            env.call_static_method(class, "drainLogs", "()Ljava/lang/String;", &[]),
            "WryBridge.drainLogs"
        )
        .l()
        .map_err(|error| format!("WryBridge.drainLogs returned nothing: {error}"))?;

        let text = read(&mut env, drained)?;
        if text.is_empty() {
            return Ok(Vec::new());
        }
        Ok(text.split('\n').map(str::to_string).collect())
    }

    fn activity<'a>(&self, mut env: &mut jni::JNIEnv<'a>) -> api::Result<JObject<'a>> {
        let thread = jni!(
            env,
            env.call_static_method(
                "android/app/ActivityThread",
                "currentActivityThread",
                "()Landroid/app/ActivityThread;",
                &[],
            ),
            "ActivityThread.currentActivityThread"
        )
        .l()
        .map_err(|error| format!("ActivityThread.currentActivityThread returned nothing: {error}"))?;
        if thread.is_null() {
            return Err("ActivityThread.currentActivityThread returned null".into());
        }

        let activities = jni!(
            env,
            env.get_field(&thread, "mActivities", "Landroid/util/ArrayMap;"),
            "ActivityThread.mActivities"
        )
        .l()
        .map_err(|error| format!("ActivityThread.mActivities returned nothing: {error}"))?;
        if activities.is_null() {
            return Err("ActivityThread.mActivities returned null".into());
        }

        let size = jni!(env, env.call_method(&activities, "size", "()I", &[]), "ArrayMap.size")
            .i()
            .map_err(|error| format!("ArrayMap.size returned nothing: {error}"))?;
        if size <= 0 {
            return Err("no activity is running in this process".into());
        }

        let record = jni!(
            env,
            env.call_method(
                &activities,
                "valueAt",
                "(I)Ljava/lang/Object;",
                &[JValue::Int(0)],
            ),
            "ArrayMap.valueAt"
        )
        .l()
        .map_err(|error| format!("ArrayMap.valueAt returned nothing: {error}"))?;

        let activity = jni!(
            env,
            env.get_field(&record, "activity", "Landroid/app/Activity;"),
            "ActivityClientRecord.activity"
        )
        .l()
        .map_err(|error| format!("ActivityClientRecord.activity returned nothing: {error}"))?;
        if activity.is_null() {
            return Err("the first activity record has no activity".into());
        }

        Ok(activity)
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
