use crate::api;
use jni::objects::{GlobalRef, JClass, JObject, JString, JValue};
use jni::JavaVM;
use std::fs;
use std::path::Path;

const DEX: &[u8] = include_bytes!("../../android-bridge.dex");
const CLASS: &str = "net.ccbluex.liquidbounce.wry.android.WryBridge";
const FOLDER: &str = "liquidbounce-wry";

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
            fs::write(&dex, DEX).map_err(|error| format!("writing {} failed: {error}", dex.display()))?;
        }

        let optimized = folder.join("optimized");
        fs::create_dir_all(&optimized).map_err(|error| format!("creating {} failed: {error}", optimized.display()))?;

        let parent = env
            .call_static_method("java/lang/ClassLoader", "getSystemClassLoader", "()Ljava/lang/ClassLoader;", &[])
            .map_err(|error| format!("ClassLoader.getSystemClassLoader failed: {error}"))?
            .l()
            .map_err(|error| format!("ClassLoader.getSystemClassLoader returned nothing: {error}"))?;

        let dex_name = env
            .new_string(dex.to_string_lossy().as_ref())
            .map_err(|error| format!("creating the dex path failed: {error}"))?;
        let optimized_name = env
            .new_string(optimized.to_string_lossy().as_ref())
            .map_err(|error| format!("creating the optimized path failed: {error}"))?;

        let loader = env
            .new_object(
                "dalvik/system/DexClassLoader",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/ClassLoader;)V",
                &[
                    JValue::Object(&JObject::from(dex_name)),
                    JValue::Object(&JObject::from(optimized_name)),
                    JValue::Object(&JObject::null()),
                    JValue::Object(&parent),
                ],
            )
            .map_err(|error| format!("creating a DexClassLoader failed: {error}"))?;

        let class_name = env
            .new_string(CLASS)
            .map_err(|error| format!("creating the class name failed: {error}"))?;
        let class = env
            .call_method(&loader, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;", &[
                JValue::Object(&JObject::from(class_name)),
            ])
            .map_err(|error| format!("loading {CLASS} failed: {error}"))?
            .l()
            .map_err(|error| format!("loading {CLASS} returned nothing: {error}"))?;
        let class = env
            .new_global_ref(class)
            .map_err(|error| format!("keeping {CLASS} failed: {error}"))?;

        Ok(Self { vm, class })
    }

    pub fn probe(&self) -> api::Result<String> {
        let mut env = self
            .vm
            .attach_current_thread_permanently()
            .map_err(|error| format!("attaching to ART failed: {error}"))?;

        let application = env
            .call_static_method(
                "android/app/ActivityThread",
                "currentApplication",
                "()Landroid/app/Application;",
                &[],
            )
            .map_err(|error| format!("ActivityThread.currentApplication failed: {error}"))?
            .l()
            .map_err(|error| format!("ActivityThread.currentApplication returned nothing: {error}"))?;
        if application.is_null() {
            return Err("ActivityThread.currentApplication returned null".into());
        }

        let class = unsafe { JClass::from_raw(self.class.as_raw()) };
        let started = env
            .call_static_method(class, "start", "(Landroid/app/Application;)Ljava/lang/String;", &[
                JValue::Object(&application),
            ])
            .map_err(|error| format!("WryBridge.start failed: {error}"))?
            .l()
            .map_err(|error| format!("WryBridge.start returned nothing: {error}"))?;

        let class = unsafe { JClass::from_raw(self.class.as_raw()) };
        let probed = env
            .call_static_method(class, "probe", "()Ljava/lang/String;", &[])
            .map_err(|error| format!("WryBridge.probe failed: {error}"))?
            .l()
            .map_err(|error| format!("WryBridge.probe returned nothing: {error}"))?;

        Ok(format!("{} | {}", read(&mut env, &started)?, read(&mut env, &probed)?))
    }
}

fn read(env: &mut jni::JNIEnv, value: &JObject) -> api::Result<String> {
    let value = JString::from(value.clone());
    let text = env
        .get_string(&value)
        .map_err(|error| format!("reading the bridge's answer failed: {error}"))?;
    Ok(text.into())
}
