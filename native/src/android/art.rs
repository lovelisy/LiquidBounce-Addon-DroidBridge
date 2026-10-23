use crate::api;
use jni::JavaVM;
use std::ffi::{c_char, c_int, c_void, CString};

const RTLD_NOW: c_int = 2;
const LIBRARIES: [&str; 2] = ["libnativehelper.so", "libart.so"];

#[link(name = "dl")]
extern "C" {
    fn dlopen(filename: *const c_char, flags: c_int) -> *mut c_void;
    fn dlsym(handle: *mut c_void, symbol: *const c_char) -> *mut c_void;
}

type GetCreatedJavaVMs =
    unsafe extern "C" fn(*mut *mut jni::sys::JavaVM, jni::sys::jsize, *mut jni::sys::jsize) -> jni::sys::jint;

fn find_symbol() -> api::Result<GetCreatedJavaVMs> {
    let name = CString::new("JNI_GetCreatedJavaVMs").expect("the symbol name has no NUL");
    for library in LIBRARIES {
        let path = CString::new(library).expect("the library name has no NUL");
        let handle = unsafe { dlopen(path.as_ptr(), RTLD_NOW) };
        if handle.is_null() {
            continue;
        }
        let address = unsafe { dlsym(handle, name.as_ptr()) };
        if !address.is_null() {
            return Ok(unsafe { std::mem::transmute::<*mut c_void, GetCreatedJavaVMs>(address) });
        }
    }
    Err("JNI_GetCreatedJavaVMs is in neither libnativehelper.so nor libart.so".into())
}

pub fn find() -> api::Result<JavaVM> {
    let created = find_symbol()?;

    let mut pointers: [*mut jni::sys::JavaVM; 8] = [std::ptr::null_mut(); 8];
    let mut count: jni::sys::jsize = 0;
    let status = unsafe { created(pointers.as_mut_ptr(), pointers.len() as jni::sys::jsize, &mut count) };
    if status != 0 {
        return Err(format!("JNI_GetCreatedJavaVMs returned {status}"));
    }

    for pointer in pointers.iter().take(count.max(0) as usize) {
        if pointer.is_null() {
            continue;
        }
        let vm = match unsafe { JavaVM::from_raw(*pointer) } {
            Ok(vm) => vm,
            Err(_) => continue,
        };
        let android = match vm.attach_current_thread_permanently() {
            Ok(mut env) => env.find_class("android/os/Build").is_ok(),
            Err(_) => false,
        };
        if android {
            return Ok(vm);
        }
    }

    Err(format!("none of the {count} created VMs has android/os/Build"))
}
