use std::collections::{BTreeMap, BTreeSet};
use std::os::fd::{BorrowedFd, OwnedFd};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::Mutex;
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};

use fuji_core::SDK_VERSION;
use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jbyteArray, jint, jlong, jstring};

const OK: i32 = 0;
const ERR_NOT_INITIALIZED: i32 = -1;
const ERR_INVALID_SESSION: i32 = -2;
const ERR_PANIC: i32 = -100;

static INITIALIZED: AtomicBool = AtomicBool::new(false);
static NEXT_SESSION_ID: AtomicI64 = AtomicI64::new(1);
/// Noop sessions: tracks session IDs with no associated resources.
static SESSIONS: Mutex<BTreeSet<i64>> = Mutex::new(BTreeSet::new());
/// Wi-Fi sessions: maps session ID -> dup'd OwnedFd for the command socket.
/// Dropping an entry closes Rust's dup; Android keeps and closes the original.
static WIFI_SESSIONS: Mutex<BTreeMap<i64, OwnedFd>> = Mutex::new(BTreeMap::new());
/// Active transfer IDs. Populated by download operations once downstream is wired.
static TRANSFERS: Mutex<BTreeSet<i64>> = Mutex::new(BTreeSet::new());

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

pub fn native_version() -> String {
    SDK_VERSION.to_owned()
}

pub fn native_initialize() -> i32 {
    INITIALIZED.store(true, Ordering::SeqCst);
    OK
}

pub fn native_shutdown() -> i32 {
    INITIALIZED.store(false, Ordering::SeqCst);
    let sessions_ok = match SESSIONS.lock() {
        Ok(mut s) => {
            s.clear();
            true
        }
        Err(_) => false,
    };
    let wifi_ok = match WIFI_SESSIONS.lock() {
        Ok(mut w) => {
            // Dropping the map entries closes each OwnedFd (Rust's dup).
            w.clear();
            true
        }
        Err(_) => false,
    };
    let transfers_ok = match TRANSFERS.lock() {
        Ok(mut t) => {
            t.clear();
            true
        }
        Err(_) => false,
    };
    if sessions_ok && wifi_ok && transfers_ok {
        OK
    } else {
        ERR_PANIC
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
    // Remove from noop set (may or may not be present).
    let in_noop = match SESSIONS.lock() {
        Ok(mut sessions) => sessions.remove(&session_id),
        Err(_) => return ERR_PANIC,
    };
    // Remove from wifi map (dropping the OwnedFd closes Rust's dup).
    let in_wifi = match WIFI_SESSIONS.lock() {
        Ok(mut wifi) => wifi.remove(&session_id).is_some(),
        Err(_) => return ERR_PANIC,
    };
    if in_noop || in_wifi {
        OK
    } else {
        ERR_INVALID_SESSION
    }
}

/// Open a Wi-Fi PTP-IP session backed by an Android-supplied command socket fd.
///
/// # Fd ownership
/// `command_fd` is Android-owned. Rust dups it immediately and stores the
/// OwnedFd keyed by the new session id. Android keeps and closes the original;
/// Rust closes its dup when the session is closed or the library shuts down.
pub fn native_open_wifi_session(command_fd: i32) -> i64 {
    if !INITIALIZED.load(Ordering::SeqCst) {
        return i64::from(ERR_NOT_INITIALIZED);
    }
    if command_fd < 0 {
        return i64::from(ERR_INVALID_SESSION);
    }
    // SAFETY: command_fd is supplied by Android and is valid for the duration
    // of this call. We immediately dup it via try_clone_to_owned() and never
    // retain the BorrowedFd beyond this scope.
    let owned_fd: OwnedFd = match unsafe { BorrowedFd::borrow_raw(command_fd) }.try_clone_to_owned()
    {
        Ok(fd) => fd,
        Err(_) => return i64::from(ERR_PANIC),
    };
    let session_id = NEXT_SESSION_ID.fetch_add(1, Ordering::SeqCst);
    match WIFI_SESSIONS.lock() {
        Ok(mut wifi) => {
            wifi.insert(session_id, owned_fd);
            session_id
        }
        Err(_) => i64::from(ERR_PANIC),
    }
}

/// Internal error type for operations that can fail with mutex poison.
#[derive(Debug, PartialEq)]
pub struct InternalError;

/// Stub: returns an empty byte slice representing an empty media object list.
///
/// Return semantics:
/// - `Ok(Some(bytes))` — session found; bytes is the (stub-empty) payload.
/// - `Ok(None)`        — session not found (unknown or invalid id).
/// - `Err(InternalError)` — mutex poison; caller should treat as an internal error.
pub fn native_list_media(session_id: i64) -> Result<Option<Vec<u8>>, InternalError> {
    if session_id <= 0 {
        return Ok(None);
    }
    let in_noop = match SESSIONS.lock() {
        Ok(s) => s.contains(&session_id),
        Err(_) => return Err(InternalError),
    };
    let in_wifi = match WIFI_SESSIONS.lock() {
        Ok(w) => w.contains_key(&session_id),
        Err(_) => return Err(InternalError),
    };
    if in_noop || in_wifi {
        // TODO(frameport): encode a real MediaObjectList protobuf/CBOR payload
        // once fuji-transfer::list_media is wired through the JNI boundary.
        Ok(Some(Vec::new()))
    } else {
        Ok(None)
    }
}

/// Stub: returns an empty byte slice representing a thumbnail payload.
///
/// Return semantics:
/// - `Ok(Some(bytes))` — session found; bytes is the (stub-empty) thumbnail.
/// - `Ok(None)`        — session not found (unknown or invalid id).
/// - `Err(InternalError)` — mutex poison; caller should treat as an internal error.
pub fn native_get_thumbnail(
    session_id: i64,
    _object_handle: i64,
) -> Result<Option<Vec<u8>>, InternalError> {
    if session_id <= 0 {
        return Ok(None);
    }
    let in_noop = match SESSIONS.lock() {
        Ok(s) => s.contains(&session_id),
        Err(_) => return Err(InternalError),
    };
    let in_wifi = match WIFI_SESSIONS.lock() {
        Ok(w) => w.contains_key(&session_id),
        Err(_) => return Err(InternalError),
    };
    if in_noop || in_wifi {
        // TODO(frameport): call fuji-transfer::get_thumbnail once the PTP-IP
        // client path is wired through the fd->session handle.
        Ok(Some(Vec::new()))
    } else {
        Ok(None)
    }
}

/// Stub: dups output_fd and immediately drops Rust's copy (closes the dup).
/// Android retains and closes the original fd.
///
/// # Fd ownership
/// `output_fd` is Android-owned (a ParcelFileDescriptor from a pending
/// MediaStore row). Rust dups it for the transfer, then closes the dup when
/// done. Android closes the original after this call returns.
///
/// TODO(frameport): hand the dup OwnedFd to fuji_transfer::download_to_owned_fd
/// once the Android-fd->fuji-ptpip client path lands.
pub fn native_download_object_to_fd(session_id: i64, _object_handle: i64, output_fd: i32) -> i32 {
    if session_id <= 0 {
        return ERR_INVALID_SESSION;
    }
    let in_noop = match SESSIONS.lock() {
        Ok(s) => s.contains(&session_id),
        Err(_) => return ERR_PANIC,
    };
    let in_wifi = match WIFI_SESSIONS.lock() {
        Ok(w) => w.contains_key(&session_id),
        Err(_) => return ERR_PANIC,
    };
    if !in_noop && !in_wifi {
        return ERR_INVALID_SESSION;
    }
    if output_fd < 0 {
        return ERR_INVALID_SESSION;
    }
    // SAFETY: output_fd is supplied by Android and valid for this call.
    // We immediately dup it and drop the dup at end of scope (closes Rust copy).
    // Android retains and closes the original.
    let _owned: OwnedFd = match unsafe { BorrowedFd::borrow_raw(output_fd) }.try_clone_to_owned() {
        Ok(fd) => fd,
        Err(_) => return ERR_PANIC,
    };
    // Stub: drop _owned here; no bytes transferred yet.
    OK
}

/// Cancel an in-flight transfer by id. For the stub, the transfer registry is
/// always empty so unknown ids return ERR_INVALID_SESSION.
pub fn native_cancel_transfer(transfer_id: i64) -> i32 {
    if transfer_id <= 0 {
        return ERR_INVALID_SESSION;
    }
    match TRANSFERS.lock() {
        Ok(mut t) => {
            if t.remove(&transfer_id) {
                OK
            } else {
                ERR_INVALID_SESSION
            }
        }
        Err(_) => ERR_PANIC,
    }
}

// ---------------------------------------------------------------------------
// JNI throw helper
// ---------------------------------------------------------------------------

/// Throw a NativeException. Must be called before returning from a JNI entry
/// point on an exceptional (panic) path. After calling this function, return
/// the sentinel immediately — make NO further JNI calls while an exception is
/// pending (only exception_check/clear/occurred and ref-deletion are safe).
///
/// Class path is SLASH-separated; dot-separated paths cause FindClass to return
/// null silently on Android.
fn throw_native(env: &mut JNIEnv, message: &str) {
    // Clear any already-pending exception before throwing a new one;
    // a second throw_new while one is pending is silently ignored.
    if env.exception_check().unwrap_or(false) {
        let _ = env.exception_clear();
    }
    // jni 0.21: throw_new takes &self (no mut needed on this call, but the
    // borrow of env as &mut already satisfies the shared-ref requirement).
    let _ = env.throw_new("dev/po4yka/frameport/nativebridge/NativeException", message);
    // Return to caller; caller returns the sentinel. No further JNI calls here.
}

// ---------------------------------------------------------------------------
// JNI exports — existing (unchanged)
// ---------------------------------------------------------------------------

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

// ---------------------------------------------------------------------------
// JNI exports — new
// ---------------------------------------------------------------------------

/// Open a Wi-Fi PTP-IP session.
///
/// `command_fd` must be a valid Android-owned socket fd (>= 0). Rust dups it
/// immediately and stores the OwnedFd. Returns the session id (> 0) on
/// success, or a negative ERR_* sentinel on expected failure. Panics are
/// caught and reported via NativeException + ERR_PANIC.
///
/// `endpoint_metadata` is accepted for future use (parsed by fuji-ptpip once
/// wired); currently unused in the stub.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_frameport_nativebridge_NativeFujiJni_nativeOpenWifiSession(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    command_fd: jint,
    // endpoint_metadata is intentionally unused in this stub. fuji-ptpip will
    // parse it (camera IP, port, and session token) once the PTP-IP client is
    // wired through the JNI boundary. The JLocal reference is freed on return.
    _endpoint_metadata: JString<'_>,
) -> jlong {
    let result = catch_unwind(AssertUnwindSafe(|| native_open_wifi_session(command_fd)));
    match result {
        Ok(id) => id as jlong,
        Err(_) => {
            throw_native(&mut env, "native panic in nativeOpenWifiSession");
            i64::from(ERR_PANIC) as jlong
        }
    }
}

/// List media objects for a session.
///
/// Returns a serialized payload (empty Vec for the stub) on success, or null
/// on unknown session (with NativeException thrown) or panic. Unknown session
/// is reported via exception because jbyteArray has no integer error channel.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_frameport_nativebridge_NativeFujiJni_nativeListMedia(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    session_id: jlong,
) -> jbyteArray {
    let result = catch_unwind(AssertUnwindSafe(|| native_list_media(session_id)));
    match result {
        Ok(Ok(Some(bytes))) => match env.byte_array_from_slice(&bytes) {
            Ok(arr) => arr.into_raw(),
            Err(_) => {
                throw_native(&mut env, "native:allocation-failed in nativeListMedia");
                std::ptr::null_mut()
            }
        },
        Ok(Ok(None)) => {
            throw_native(&mut env, "native:invalid-session");
            std::ptr::null_mut()
        }
        Ok(Err(InternalError)) => {
            // Mutex poison: indistinguishable from a panic at this severity level.
            throw_native(&mut env, "native:internal-error in nativeListMedia");
            std::ptr::null_mut()
        }
        Err(_) => {
            throw_native(&mut env, "native panic in nativeListMedia");
            std::ptr::null_mut()
        }
    }
}

/// Fetch the thumbnail for an object handle within a session.
///
/// Returns a serialized thumbnail payload (empty Vec for the stub) on success,
/// or null + NativeException on unknown session or panic.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_frameport_nativebridge_NativeFujiJni_nativeGetThumbnail(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    session_id: jlong,
    object_handle: jlong,
) -> jbyteArray {
    let result = catch_unwind(AssertUnwindSafe(|| {
        native_get_thumbnail(session_id, object_handle)
    }));
    match result {
        Ok(Ok(Some(bytes))) => match env.byte_array_from_slice(&bytes) {
            Ok(arr) => arr.into_raw(),
            Err(_) => {
                throw_native(&mut env, "native:allocation-failed in nativeGetThumbnail");
                std::ptr::null_mut()
            }
        },
        Ok(Ok(None)) => {
            throw_native(&mut env, "native:invalid-session");
            std::ptr::null_mut()
        }
        Ok(Err(InternalError)) => {
            // Mutex poison: indistinguishable from a panic at this severity level.
            throw_native(&mut env, "native:internal-error in nativeGetThumbnail");
            std::ptr::null_mut()
        }
        Err(_) => {
            throw_native(&mut env, "native panic in nativeGetThumbnail");
            std::ptr::null_mut()
        }
    }
}

/// Stream an object from the camera session into an Android-owned output fd.
///
/// # Fd ownership
/// `output_fd` is Android-owned. Rust dups it, performs the transfer (stub:
/// immediately drops the dup), then returns. Android closes the original.
///
/// Returns OK (0) on success or a negative ERR_* sentinel on failure.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_frameport_nativebridge_NativeFujiJni_nativeDownloadObjectToFd(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    session_id: jlong,
    object_handle: jlong,
    output_fd: jint,
) -> jint {
    let result = catch_unwind(AssertUnwindSafe(|| {
        native_download_object_to_fd(session_id, object_handle, output_fd)
    }));
    match result {
        Ok(code) => code as jint,
        Err(_) => {
            throw_native(&mut env, "native panic in nativeDownloadObjectToFd");
            ERR_PANIC as jint
        }
    }
}

/// Cancel an in-flight transfer by id.
///
/// Returns OK (0) if cancelled, ERR_INVALID_SESSION (-2) if unknown, or
/// ERR_PANIC (-100) + NativeException on a caught panic.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_frameport_nativebridge_NativeFujiJni_nativeCancelTransfer(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    transfer_id: jlong,
) -> jint {
    let result = catch_unwind(AssertUnwindSafe(|| native_cancel_transfer(transfer_id)));
    match result {
        Ok(code) => code as jint,
        Err(_) => {
            throw_native(&mut env, "native panic in nativeCancelTransfer");
            ERR_PANIC as jint
        }
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    // All tests that touch global state must hold this mutex for their entire
    // duration. Global statics are process-wide; Rust test threads run in
    // parallel by default, so without serialisation the shared AtomicBool /
    // Mutex registries race between tests and produce flaky results.
    static TEST_MUTEX: Mutex<()> = Mutex::new(());

    // Helper: reset all global state. Call while holding TEST_MUTEX.
    fn reset() {
        INITIALIZED.store(false, Ordering::SeqCst);
        SESSIONS.lock().unwrap().clear();
        WIFI_SESSIONS.lock().unwrap().clear();
        TRANSFERS.lock().unwrap().clear();
    }

    // ----- existing tests (unchanged logic, now serialised via TEST_MUTEX) -----

    #[test]
    fn version_is_workspace_package_version() {
        // version() is pure/stateless; no lock needed.
        assert_eq!(native_version(), "0.1.0");
    }

    #[test]
    fn noop_session_lifecycle_requires_initialization() {
        let _g = TEST_MUTEX.lock().unwrap();
        reset();
        assert_eq!(native_open_noop_session(), i64::from(ERR_NOT_INITIALIZED));
        assert_eq!(native_initialize(), OK);
        let session_id = native_open_noop_session();
        assert!(session_id > 0);
        assert_eq!(native_close_session(session_id), OK);
        assert_eq!(native_close_session(session_id), ERR_INVALID_SESSION);
        assert_eq!(native_shutdown(), OK);
    }

    // ----- wifi session registry -----

    #[test]
    fn open_wifi_session_stores_and_returns_id() {
        let _g = TEST_MUTEX.lock().unwrap();
        reset();
        native_initialize();
        // Use /dev/null as a safe fd source for the dup.
        let devnull = std::fs::File::open("/dev/null").expect("open /dev/null");
        use std::os::fd::AsRawFd;
        let raw = devnull.as_raw_fd();
        let id = native_open_wifi_session(raw);
        assert!(id > 0, "expected positive session id, got {id}");
        assert!(WIFI_SESSIONS.lock().unwrap().contains_key(&id));
        native_shutdown();
    }

    #[test]
    fn open_wifi_session_requires_initialization() {
        let _g = TEST_MUTEX.lock().unwrap();
        reset();
        let devnull = std::fs::File::open("/dev/null").expect("open /dev/null");
        use std::os::fd::AsRawFd;
        let id = native_open_wifi_session(devnull.as_raw_fd());
        assert_eq!(id, i64::from(ERR_NOT_INITIALIZED));
    }

    #[test]
    fn open_wifi_session_rejects_negative_fd() {
        let _g = TEST_MUTEX.lock().unwrap();
        reset();
        native_initialize();
        let id = native_open_wifi_session(-1);
        assert_eq!(id, i64::from(ERR_INVALID_SESSION));
        native_shutdown();
    }

    #[test]
    fn close_session_removes_wifi_session() {
        let _g = TEST_MUTEX.lock().unwrap();
        reset();
        native_initialize();
        let devnull = std::fs::File::open("/dev/null").expect("open /dev/null");
        use std::os::fd::AsRawFd;
        let id = native_open_wifi_session(devnull.as_raw_fd());
        assert!(id > 0);
        assert_eq!(native_close_session(id), OK);
        assert!(!WIFI_SESSIONS.lock().unwrap().contains_key(&id));
        // Second close: session gone -> ERR_INVALID_SESSION
        assert_eq!(native_close_session(id), ERR_INVALID_SESSION);
        native_shutdown();
    }

    #[test]
    fn shutdown_clears_wifi_sessions() {
        let _g = TEST_MUTEX.lock().unwrap();
        reset();
        native_initialize();
        let devnull = std::fs::File::open("/dev/null").expect("open /dev/null");
        use std::os::fd::AsRawFd;
        let id = native_open_wifi_session(devnull.as_raw_fd());
        assert!(id > 0, "expected positive session id, got {id}");
        assert_eq!(native_shutdown(), OK);
        assert!(WIFI_SESSIONS.lock().unwrap().is_empty());
    }

    // ----- list_media / get_thumbnail -----

    #[test]
    fn list_media_returns_empty_vec_for_valid_noop_session() {
        let _g = TEST_MUTEX.lock().unwrap();
        reset();
        native_initialize();
        let id = native_open_noop_session();
        assert!(id > 0);
        let result = native_list_media(id);
        assert_eq!(result, Ok(Some(Vec::<u8>::new())));
        native_shutdown();
    }

    #[test]
    fn list_media_returns_none_for_unknown_session() {
        let _g = TEST_MUTEX.lock().unwrap();
        reset();
        native_initialize();
        assert_eq!(native_list_media(9999), Ok(None));
        native_shutdown();
    }

    #[test]
    fn list_media_returns_empty_vec_for_valid_wifi_session() {
        let _g = TEST_MUTEX.lock().unwrap();
        reset();
        native_initialize();
        let devnull = std::fs::File::open("/dev/null").expect("open /dev/null");
        use std::os::fd::AsRawFd;
        let id = native_open_wifi_session(devnull.as_raw_fd());
        assert!(id > 0);
        let result = native_list_media(id);
        assert_eq!(result, Ok(Some(Vec::<u8>::new())));
        native_shutdown();
    }

    #[test]
    fn get_thumbnail_returns_empty_vec_for_valid_session() {
        let _g = TEST_MUTEX.lock().unwrap();
        reset();
        native_initialize();
        let id = native_open_noop_session();
        let result = native_get_thumbnail(id, 0x0001_0001);
        assert_eq!(result, Ok(Some(Vec::<u8>::new())));
        native_shutdown();
    }

    #[test]
    fn get_thumbnail_returns_none_for_unknown_session() {
        let _g = TEST_MUTEX.lock().unwrap();
        reset();
        native_initialize();
        assert_eq!(native_get_thumbnail(9999, 1), Ok(None));
        native_shutdown();
    }

    // ----- download_object_to_fd -----

    #[test]
    fn download_object_to_fd_ok_for_valid_session() {
        let _g = TEST_MUTEX.lock().unwrap();
        reset();
        native_initialize();
        let id = native_open_noop_session();
        let devnull = std::fs::OpenOptions::new()
            .write(true)
            .open("/dev/null")
            .expect("open /dev/null writable");
        use std::os::fd::AsRawFd;
        let code = native_download_object_to_fd(id, 0x0001_0001, devnull.as_raw_fd());
        assert_eq!(code, OK);
        native_shutdown();
    }

    #[test]
    fn download_object_to_fd_unknown_session_returns_err() {
        let _g = TEST_MUTEX.lock().unwrap();
        reset();
        native_initialize();
        let devnull = std::fs::OpenOptions::new()
            .write(true)
            .open("/dev/null")
            .expect("open /dev/null writable");
        use std::os::fd::AsRawFd;
        assert_eq!(
            native_download_object_to_fd(9999, 1, devnull.as_raw_fd()),
            ERR_INVALID_SESSION
        );
        native_shutdown();
    }

    #[test]
    fn download_object_to_fd_rejects_negative_fd() {
        let _g = TEST_MUTEX.lock().unwrap();
        reset();
        native_initialize();
        let id = native_open_noop_session();
        assert_eq!(native_download_object_to_fd(id, 1, -1), ERR_INVALID_SESSION);
        native_shutdown();
    }

    // ----- cancel_transfer -----

    #[test]
    fn cancel_transfer_unknown_id_returns_err_invalid_session() {
        let _g = TEST_MUTEX.lock().unwrap();
        reset();
        native_initialize();
        assert_eq!(native_cancel_transfer(42), ERR_INVALID_SESSION);
        native_shutdown();
    }

    #[test]
    fn cancel_transfer_negative_id_returns_err_invalid_session() {
        let _g = TEST_MUTEX.lock().unwrap();
        reset();
        assert_eq!(native_cancel_transfer(-1), ERR_INVALID_SESSION);
    }

    #[test]
    fn cancel_transfer_known_id_returns_ok() {
        let _g = TEST_MUTEX.lock().unwrap();
        reset();
        native_initialize();
        TRANSFERS.lock().unwrap().insert(7);
        assert_eq!(native_cancel_transfer(7), OK);
        assert!(!TRANSFERS.lock().unwrap().contains(&7));
        native_shutdown();
    }
}
