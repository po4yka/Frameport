use std::collections::BTreeSet;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::Mutex;
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};

use fuji_core::SDK_VERSION;
use jni::JNIEnv;
use jni::objects::JClass;
use jni::sys::{jint, jlong, jstring};

const OK: i32 = 0;
const ERR_NOT_INITIALIZED: i32 = -1;
const ERR_INVALID_SESSION: i32 = -2;
const ERR_PANIC: i32 = -100;

static INITIALIZED: AtomicBool = AtomicBool::new(false);
static NEXT_SESSION_ID: AtomicI64 = AtomicI64::new(1);
static SESSIONS: Mutex<BTreeSet<i64>> = Mutex::new(BTreeSet::new());

pub fn native_version() -> String {
    SDK_VERSION.to_owned()
}

pub fn native_initialize() -> i32 {
    INITIALIZED.store(true, Ordering::SeqCst);
    OK
}

pub fn native_shutdown() -> i32 {
    INITIALIZED.store(false, Ordering::SeqCst);
    match SESSIONS.lock() {
        Ok(mut sessions) => {
            sessions.clear();
            OK
        }
        Err(_) => ERR_PANIC,
    }
}

pub fn native_open_noop_session() -> i64 {
    if !INITIALIZED.load(Ordering::SeqCst) {
        return i64::from(ERR_NOT_INITIALIZED);
    }
    let session_id = NEXT_SESSION_ID.fetch_add(1, Ordering::SeqCst);
    match SESSIONS.lock() {
        Ok(mut sessions) => {
            sessions.insert(session_id);
            session_id
        }
        Err(_) => i64::from(ERR_PANIC),
    }
}

pub fn native_close_session(session_id: i64) -> i32 {
    if session_id <= 0 {
        return ERR_INVALID_SESSION;
    }
    match SESSIONS.lock() {
        Ok(mut sessions) => {
            if sessions.remove(&session_id) {
                OK
            } else {
                ERR_INVALID_SESSION
            }
        }
        Err(_) => ERR_PANIC,
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_frameport_nativebridge_NativeFujiJni_nativeVersion(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        match env.new_string(native_version()) {
            Ok(value) => value.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    }))
    .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_frameport_nativebridge_NativeFujiJni_nativeInitialize(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jint {
    catch_unwind(native_initialize).unwrap_or(ERR_PANIC) as jint
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_frameport_nativebridge_NativeFujiJni_nativeShutdown(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jint {
    catch_unwind(native_shutdown).unwrap_or(ERR_PANIC) as jint
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_frameport_nativebridge_NativeFujiJni_nativeOpenNoopSession(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jlong {
    catch_unwind(native_open_noop_session).unwrap_or(i64::from(ERR_PANIC)) as jlong
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_frameport_nativebridge_NativeFujiJni_nativeCloseSession(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    session_id: jlong,
) -> jint {
    catch_unwind(|| native_close_session(session_id)).unwrap_or(ERR_PANIC) as jint
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn version_is_workspace_package_version() {
        assert_eq!(native_version(), "0.1.0");
    }

    #[test]
    fn noop_session_lifecycle_requires_initialization() {
        native_shutdown();
        assert_eq!(native_open_noop_session(), -1);
        assert_eq!(native_initialize(), 0);
        let session_id = native_open_noop_session();
        assert!(session_id > 0);
        assert_eq!(native_close_session(session_id), 0);
        assert_eq!(native_close_session(session_id), -2);
        assert_eq!(native_shutdown(), 0);
    }
}
