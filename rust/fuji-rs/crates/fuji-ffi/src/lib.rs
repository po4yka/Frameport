use std::collections::{BTreeMap, BTreeSet};
use std::os::fd::{AsRawFd, BorrowedFd, FromRawFd, OwnedFd};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::{Arc, Mutex};

use fuji_core::SDK_VERSION;
use fuji_liveview::{LiveViewParser, run_liveview_loop};
use fuji_usb_ptp::UsbPtpSession;
use jni::objects::{JByteArray, JClass, JObject, JString, JValue};
use jni::signature::{MethodSignature, RuntimeMethodSignature};
use jni::strings::JNIString;
use jni::sys::{JavaVM as RawJavaVM, jbyteArray, jint, jlong, jstring};
use jni::{Env, EnvUnowned, JavaVM, Outcome};

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
/// USB sessions: maps session ID -> the live `UsbPtpSession` (owns the USB device fd).
/// OWNERSHIP: Android keeps + closes the original UsbDeviceConnection fd. The fd handed
///            to Rust is an Android `Os.dup()`; Rust takes exclusive ownership of that dup
///            (no second dup) via the session's `BulkTransport`, and closes it when the
///            `UsbPtpSession` is dropped on session close or shutdown.
static USB_SESSIONS: Mutex<BTreeMap<i64, UsbPtpSession>> = Mutex::new(BTreeMap::new());
/// Active transfer IDs. Populated by download operations once downstream is wired.
static TRANSFERS: Mutex<BTreeSet<i64>> = Mutex::new(BTreeSet::new());

/// Live-view session handle: stop flag + worker thread join handle.
struct LiveViewHandle {
    /// Set to true to signal the worker read loop to exit cleanly.
    stop_flag: Arc<AtomicBool>,
    /// Duplicate of the live-view socket fd retained only to wake blocked reads
    /// during stop/shutdown. Dropping it closes this duplicate; the worker owns
    /// and closes the independent fd used by its TcpStream.
    wake_fd: OwnedFd,
    /// Join handle for the dedicated worker std::thread.
    /// Wrapped in Option so we can take() it in stop to join without consuming self.
    worker: Option<std::thread::JoinHandle<()>>,
}

/// Active live-view sessions: session_id -> LiveViewHandle.
/// Access serialised by Mutex; the worker thread is separate (std::thread).
static LIVEVIEW_SESSIONS: Mutex<BTreeMap<i64, LiveViewHandle>> = Mutex::new(BTreeMap::new());

/// Cached JavaVM handle for attach_current_thread_as_daemon in worker threads.
/// Set once in nativeLiveViewStart (or JNI_OnLoad when wired). JavaVM is Send + Sync.
static JAVA_VM: Mutex<Option<JavaVM>> = Mutex::new(None);

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
    let usb_ok = match USB_SESSIONS.lock() {
        Ok(mut u) => {
            // Dropping each UsbPtpSession closes its BulkTransport fd (Rust's dup).
            u.clear();
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
    // Signal all live-view workers to stop; join them so the JVM can safely
    // unload. Entries are removed from the registry as we drain them.
    let liveview_ok = match LIVEVIEW_SESSIONS.lock() {
        Ok(mut lv) => {
            for handle in lv.values() {
                // Relaxed is correct here: the stop_flag carries no data payload;
                // it is a pure termination signal. The preceding Mutex::lock()
                // provides a full memory fence that makes all prior writes visible
                // to any thread that subsequently acquires the same mutex. The
                // following jh.join() provides an implicit acquire barrier that
                // ensures any stores made before join() are visible afterward.
                // The reader (run_liveview_loop) uses Relaxed load and only needs
                // eventual visibility — a few extra iterations before observing
                // the flag on a weakly-ordered CPU are acceptable.
                handle.stop_flag.store(true, Ordering::Relaxed);
                wake_liveview_reader(&handle.wake_fd);
            }
            // Collect keys first to avoid holding the guard while joining.
            let keys: Vec<i64> = lv.keys().copied().collect();
            for key in keys {
                if let Some(mut handle) = lv.remove(&key)
                    && let Some(jh) = handle.worker.take()
                {
                    wake_liveview_reader(&handle.wake_fd);
                    let _ = jh.join();
                }
            }
            true
        }
        Err(_) => false,
    };
    if sessions_ok && wifi_ok && usb_ok && transfers_ok && liveview_ok {
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
    // Remove from usb map (dropping the UsbPtpSession closes Rust's dup of the USB fd).
    let in_usb = match USB_SESSIONS.lock() {
        Ok(mut usb) => usb.remove(&session_id).is_some(),
        Err(_) => return ERR_PANIC,
    };
    if in_noop || in_wifi || in_usb {
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

/// Open a USB PTP session backed by an Android-supplied USB device fd.
///
/// # Fd ownership
/// `usb_fd` is the Android `Os.dup()` of `UsbDeviceConnection.fileDescriptor`.
/// // OWNERSHIP: Android keeps + closes the ORIGINAL via UsbDeviceConnection.close().
/// //            Rust takes exclusive ownership of the dup (no second dup) — the dup
/// //            lives in the stored UsbPtpSession and is closed on session close/shutdown.
/// Android may close the `UsbDeviceConnection` independently; the dup remains valid.
///
/// `descriptor_bytes` is a copy of the raw USB interface descriptor bytes from Android.
/// They are parsed by `fuji_usb_ptp::open_from_owned_fd`, which validates every `bLength`
/// field before indexing and locates the PTP bulk-IN / bulk-OUT endpoint pair.
///
/// # Fd ownership (no double-dup)
/// `usb_fd` is an Android `Os.dup()` of the `UsbDeviceConnection` fd; Android has
/// transferred exclusive ownership of that dup to Rust. Rust does NOT dup again — it
/// hands `usb_fd` straight to `open_from_owned_fd`, which wraps it in an `OwnedFd` and
/// owns it for the lifetime of the returned `UsbPtpSession`. Rust is therefore
/// responsible for closing `usb_fd` on EVERY path:
/// - success: the fd lives in the stored `UsbPtpSession` and is closed on session close/shutdown;
/// - `open_from_owned_fd` error: the fd is closed inside that call (its `OwnedFd` drops);
/// - pre-call error here (`!INITIALIZED`): closed explicitly via `libc_close`.
///
/// Android closes only the ORIGINAL connection fd via `UsbDeviceConnection.close()`.
///
/// Returns the new session id (> 0) on success, or a negative ERR_* sentinel on failure.
pub fn native_usb_session_open(usb_fd: i32, descriptor_bytes: &[u8]) -> i64 {
    if !INITIALIZED.load(Ordering::SeqCst) {
        // OWNERSHIP: Android transferred the dup; close it so it does not leak,
        // since we never reach open_from_owned_fd.
        if usb_fd >= 0 {
            // SAFETY: usb_fd is the Android dup, owned by Rust and not yet wrapped.
            unsafe { libc_close(usb_fd) };
        }
        return i64::from(ERR_NOT_INITIALIZED);
    }
    // open_from_owned_fd takes ownership of usb_fd and closes it on ALL of its
    // error paths (invalid fd, malformed descriptor, missing endpoint). On Ok it
    // owns the fd via the session's BulkTransport.
    let session = match fuji_usb_ptp::open_from_owned_fd(usb_fd, descriptor_bytes) {
        Ok(session) => session,
        Err(_) => return i64::from(ERR_INVALID_SESSION),
    };
    let session_id = NEXT_SESSION_ID.fetch_add(1, Ordering::SeqCst);
    match USB_SESSIONS.lock() {
        Ok(mut usb) => {
            usb.insert(session_id, session);
            session_id
        }
        // On mutex poison, `session` drops at end of scope and closes the fd. No leak.
        Err(_) => i64::from(ERR_PANIC),
    }
}

/// Close a USB PTP session by id.
///
/// Drops the UsbPtpSession from USB_SESSIONS, which closes Rust's dup of the USB device fd.
/// Android closes the original UsbDeviceConnection fd independently.
///
/// Idempotent: unknown session ids (not in USB_SESSIONS) return `OK`.
///
/// Returns `OK` (0) on success, or `ERR_PANIC` (-100) on mutex poison.
pub fn native_usb_session_close(session_id: i64) -> i32 {
    if session_id <= 0 {
        // Idempotent: invalid id treated as already-closed.
        return OK;
    }
    match USB_SESSIONS.lock() {
        Ok(mut usb) => {
            // Dropping the UsbPtpSession closes Rust's dup of the USB device fd.
            let _ = usb.remove(&session_id);
            OK
        }
        Err(_) => ERR_PANIC,
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
    let in_usb = match USB_SESSIONS.lock() {
        Ok(u) => u.contains_key(&session_id),
        Err(_) => return Err(InternalError),
    };
    if in_noop || in_wifi || in_usb {
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
    let in_usb = match USB_SESSIONS.lock() {
        Ok(u) => u.contains_key(&session_id),
        Err(_) => return Err(InternalError),
    };
    if in_noop || in_wifi || in_usb {
        // TODO(frameport): call fuji-transfer::get_thumbnail once the PTP-IP/USB
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
    let in_usb = match USB_SESSIONS.lock() {
        Ok(u) => u.contains_key(&session_id),
        Err(_) => return ERR_PANIC,
    };
    if !in_noop && !in_wifi && !in_usb {
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
fn throw_native(env: &mut Env<'_>, message: &str) {
    // Clear any already-pending exception before throwing a new one;
    // a second throw_new while one is pending is silently ignored.
    if env.exception_check() {
        env.exception_clear();
    }
    let _ = env.throw_new(
        JNIString::from("dev/po4yka/frameport/nativebridge/NativeException"),
        JNIString::from(message),
    );
    // Return to caller; caller returns the sentinel. No further JNI calls here.
}

fn throw_native_unowned(env: &mut EnvUnowned<'_>, message: &str) {
    let _ = env
        .with_env(|env| -> jni::errors::Result<()> {
            throw_native(env, message);
            Ok(())
        })
        .into_outcome();
}

// ---------------------------------------------------------------------------
// JNI_OnLoad — cache JavaVM at library load time
// ---------------------------------------------------------------------------

/// Called by the JVM immediately after the native library is loaded via
/// `System.loadLibrary`. Caches the `JavaVM` pointer in `JAVA_VM` so it is
/// available before any other JNI entry point is called (in particular before
/// `nativeLiveViewStart`, which previously was the only site that populated
/// `JAVA_VM`). The lazy fallback inside `nativeLiveViewStart` is retained as
/// defence-in-depth.
///
/// Returns `JNI_VERSION_1_6` on success. The JVM rejects any other value and
/// immediately unloads the library. Panics are caught; on the panic path the
/// library load still succeeds (returns `JNI_VERSION_1_6`) because the
/// `JAVA_VM` fallback inside `nativeLiveViewStart` covers the miss.
#[unsafe(no_mangle)]
#[allow(clippy::not_unsafe_ptr_arg_deref)]
pub extern "system" fn JNI_OnLoad(vm: *mut RawJavaVM, _reserved: *mut std::ffi::c_void) -> jint {
    // JNI_VERSION_1_6 = 0x00010006
    const JNI_VERSION_1_6: jint = 0x0001_0006;

    // Store the VM; ignore errors (mutex poison or panic) — the lazy fallback
    // in nativeLiveViewStart will populate JAVA_VM on first liveview call.
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if let Ok(mut guard) = JAVA_VM.lock() {
            // SAFETY: The JVM calls JNI_OnLoad with a valid JavaVM pointer for
            // the process lifetime. JavaVM::from_raw copies the pointer; the JVM
            // remains the owner and outlives the cached handle.
            let vm = unsafe { JavaVM::from_raw(vm) };
            *guard = Some(vm);
        }
    }));

    JNI_VERSION_1_6
}

// ---------------------------------------------------------------------------
// JNI exports — existing (unchanged)
// ---------------------------------------------------------------------------

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_frameport_nativebridge_NativeFujiJni_nativeVersion(
    mut env: EnvUnowned<'_>,
    _class: JClass<'_>,
) -> jstring {
    match env
        .with_env(|env| -> jni::errors::Result<jstring> {
            Ok(env.new_string(native_version())?.into_raw())
        })
        .into_outcome()
    {
        Outcome::Ok(value) => value,
        Outcome::Err(_) | Outcome::Panic(_) => std::ptr::null_mut(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_frameport_nativebridge_NativeFujiJni_nativeInitialize(
    _env: EnvUnowned<'_>,
    _class: JClass<'_>,
) -> jint {
    catch_unwind(native_initialize).unwrap_or(ERR_PANIC) as jint
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_frameport_nativebridge_NativeFujiJni_nativeShutdown(
    _env: EnvUnowned<'_>,
    _class: JClass<'_>,
) -> jint {
    catch_unwind(native_shutdown).unwrap_or(ERR_PANIC) as jint
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_frameport_nativebridge_NativeFujiJni_nativeOpenNoopSession(
    _env: EnvUnowned<'_>,
    _class: JClass<'_>,
) -> jlong {
    catch_unwind(native_open_noop_session).unwrap_or(i64::from(ERR_PANIC)) as jlong
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_frameport_nativebridge_NativeFujiJni_nativeCloseSession(
    _env: EnvUnowned<'_>,
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
    mut env: EnvUnowned<'_>,
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
            throw_native_unowned(&mut env, "native panic in nativeOpenWifiSession");
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
    mut env: EnvUnowned<'_>,
    _class: JClass<'_>,
    session_id: jlong,
) -> jbyteArray {
    match env
        .with_env(|env| -> jni::errors::Result<jbyteArray> {
            let result = catch_unwind(AssertUnwindSafe(|| native_list_media(session_id)));
            match result {
                Ok(Ok(Some(bytes))) => match env.byte_array_from_slice(&bytes) {
                    Ok(arr) => Ok(arr.into_raw()),
                    Err(_) => {
                        throw_native(env, "native:allocation-failed in nativeListMedia");
                        Ok(std::ptr::null_mut())
                    }
                },
                Ok(Ok(None)) => {
                    throw_native(env, "native:invalid-session");
                    Ok(std::ptr::null_mut())
                }
                Ok(Err(InternalError)) => {
                    // Mutex poison: indistinguishable from a panic at this severity level.
                    throw_native(env, "native:internal-error in nativeListMedia");
                    Ok(std::ptr::null_mut())
                }
                Err(_) => {
                    throw_native(env, "native panic in nativeListMedia");
                    Ok(std::ptr::null_mut())
                }
            }
        })
        .into_outcome()
    {
        Outcome::Ok(value) => value,
        Outcome::Err(_) | Outcome::Panic(_) => std::ptr::null_mut(),
    }
}

/// Fetch the thumbnail for an object handle within a session.
///
/// Returns a serialized thumbnail payload (empty Vec for the stub) on success,
/// or null + NativeException on unknown session or panic.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_frameport_nativebridge_NativeFujiJni_nativeGetThumbnail(
    mut env: EnvUnowned<'_>,
    _class: JClass<'_>,
    session_id: jlong,
    object_handle: jlong,
) -> jbyteArray {
    match env
        .with_env(|env| -> jni::errors::Result<jbyteArray> {
            let result = catch_unwind(AssertUnwindSafe(|| {
                native_get_thumbnail(session_id, object_handle)
            }));
            match result {
                Ok(Ok(Some(bytes))) => match env.byte_array_from_slice(&bytes) {
                    Ok(arr) => Ok(arr.into_raw()),
                    Err(_) => {
                        throw_native(env, "native:allocation-failed in nativeGetThumbnail");
                        Ok(std::ptr::null_mut())
                    }
                },
                Ok(Ok(None)) => {
                    throw_native(env, "native:invalid-session");
                    Ok(std::ptr::null_mut())
                }
                Ok(Err(InternalError)) => {
                    // Mutex poison: indistinguishable from a panic at this severity level.
                    throw_native(env, "native:internal-error in nativeGetThumbnail");
                    Ok(std::ptr::null_mut())
                }
                Err(_) => {
                    throw_native(env, "native panic in nativeGetThumbnail");
                    Ok(std::ptr::null_mut())
                }
            }
        })
        .into_outcome()
    {
        Outcome::Ok(value) => value,
        Outcome::Err(_) | Outcome::Panic(_) => std::ptr::null_mut(),
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
    mut env: EnvUnowned<'_>,
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
            throw_native_unowned(&mut env, "native panic in nativeDownloadObjectToFd");
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
    mut env: EnvUnowned<'_>,
    _class: JClass<'_>,
    transfer_id: jlong,
) -> jint {
    let result = catch_unwind(AssertUnwindSafe(|| native_cancel_transfer(transfer_id)));
    match result {
        Ok(code) => code as jint,
        Err(_) => {
            throw_native_unowned(&mut env, "native panic in nativeCancelTransfer");
            ERR_PANIC as jint
        }
    }
}

// ---------------------------------------------------------------------------
// JNI exports — live-view
// ---------------------------------------------------------------------------

/// Start the Rust live-view read loop for a PTP-IP session.
///
/// # Fd ownership
/// `live_view_fd` is Android-owned and already dup'd by the caller. Rust dups
/// it again immediately inside this function (via `BorrowedFd::try_clone_to_owned`),
/// takes ownership of the dup, and passes the dup's raw fd to the async read
/// loop via `TcpStream::from_raw_fd`. Android MUST NOT close or use
/// `live_view_fd` after this call returns `OK`.
///
/// # JNI / thread safety
/// `callback_obj` is registered as a global reference before spawning the worker
/// thread. The `Env` from this call is NOT captured — a fresh `Env` is
/// obtained inside the worker via `JavaVM::attach_current_thread_as_daemon`.
/// The `JavaVM` is cached in `JAVA_VM` for subsequent calls.
///
/// Returns `OK` (0) on success or a negative ERR_* sentinel on failure.
/// On panic, throws `NativeException` and returns `ERR_PANIC`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_frameport_nativebridge_NativeFujiJni_nativeLiveViewStart(
    mut env: EnvUnowned<'_>,
    _class: JClass<'_>,
    session_id: jlong,
    live_view_fd: jint,
    callback_obj: JObject<'_>,
) -> jint {
    match env
        .with_env(|env| -> jni::errors::Result<jint> {
            let result = catch_unwind(AssertUnwindSafe(|| {
                if !INITIALIZED.load(Ordering::SeqCst) {
                    return ERR_NOT_INITIALIZED;
                }
                if session_id <= 0 {
                    return ERR_INVALID_SESSION;
                }
                if live_view_fd < 0 {
                    return ERR_INVALID_SESSION;
                }

                // ── Dup the fd before registering the GlobalRef (fail fast on bad fd) ──
                // SAFETY: live_view_fd is supplied by Android, valid for the duration of
                // this JNI call. We immediately dup it and store only the OwnedFd; the
                // original fd remains Android-owned. The dup is later converted to a
                // TcpStream inside the worker thread via from_raw_fd (see worker below).
                let owned_fd: OwnedFd =
                    match unsafe { BorrowedFd::borrow_raw(live_view_fd) }.try_clone_to_owned() {
                        Ok(fd) => fd,
                        Err(_) => return ERR_PANIC,
                    };
                // Keep a separate fd duplicate for stop/shutdown. `shutdown(SHUT_RDWR)`
                // on this duplicate wakes any blocked read on the worker's socket fd,
                // allowing the stop flag to be observed before `join()`.
                // SAFETY: owned_fd is a live socket fd owned by this function. The
                // borrowed fd is used only for this immediate dup and is not retained.
                let wake_fd: OwnedFd = match unsafe { BorrowedFd::borrow_raw(owned_fd.as_raw_fd()) }
                    .try_clone_to_owned()
                {
                    Ok(fd) => fd,
                    Err(_) => return ERR_PANIC,
                };

                // ── Register callback as a global reference ───────────────────────────
                // Global references are Send; the worker thread owns it until exit.
                let callback_global = match env.new_global_ref(&callback_obj) {
                    Ok(r) => r,
                    Err(_) => return ERR_PANIC,
                };

                // ── Cache the JavaVM for use in the worker thread ─────────────────────
                // env.get_java_vm() is cheap and idempotent. Cache it in JAVA_VM so we
                // don't need to pass it through every call site.
                let vm: JavaVM = match env.get_java_vm() {
                    Ok(vm) => vm,
                    Err(_) => return ERR_PANIC,
                };
                match JAVA_VM.lock() {
                    Ok(mut guard) => *guard = Some(vm),
                    Err(_) => return ERR_PANIC,
                }

                // ── Stop flag shared between this scope and the worker ─────────────────
                let stop_flag = Arc::new(AtomicBool::new(false));
                let stop_flag_worker = Arc::clone(&stop_flag);

                // ── Spawn a dedicated std::thread for the read loop ───────────────────
                // A std::thread running a current_thread tokio runtime is the safest
                // pattern for JNI daemon attachment: we attach once at thread start,
                // run the full async loop, detach on exit. JNIEnv is NEVER moved across
                // thread boundaries — it is obtained fresh inside the worker.
                // OWNERSHIP: raw_fd is unguarded from into_raw_fd(); every error path
                // after it (including spawn failure) must close it explicitly.
                // The closure captures raw_fd by copy (RawFd: Copy), so if spawn()
                // returns Err the closure is dropped without running and no destructor
                // fires — we must call libc_close(raw_fd) on that branch ourselves.
                let raw_fd = {
                    use std::os::fd::IntoRawFd;
                    owned_fd.into_raw_fd()
                };

                let worker = std::thread::Builder::new()
                    .name("frameport-liveview-worker".to_owned())
                    .spawn(move || {
                        // Wrap the entire worker body in catch_unwind so that a panic
                        // anywhere inside (JVM attach, tokio build, read loop, on_frame
                        // callback) is contained at the thread boundary. Without this
                        // guard, a panic would either unwind silently past the thread
                        // boundary (leaking raw_fd and the GlobalRef) or abort the
                        // process if a downstream crate sets panic=abort.
                        //
                        // On the panic path: raw_fd is closed explicitly before
                        // returning so the fd does not leak for the process lifetime.
                        // The GlobalRef (callback_global) is captured by the closure;
                        // when the closure unwinds it is dropped by catch_unwind's
                        // internal unwind, releasing the JVM reference.
                        let result = catch_unwind(AssertUnwindSafe(move || {
                            // ── Obtain the cached JavaVM ───────────────────────────────────
                            let vm = match JAVA_VM.lock() {
                                Ok(guard) => match guard.as_ref() {
                                    Some(vm) => {
                                        // SAFETY: JavaVM::clone is a pointer copy; the JVM
                                        // process stays alive for the duration of this thread.
                                        // SAFETY: We need a JavaVM to attach. The pointer in
                                        // the global Mutex is valid for the process lifetime.
                                        //
                                        // jni 0.21 JavaVM does not implement Clone directly;
                                        // we rebuild from the raw pointer.
                                        let raw = vm.get_raw();
                                        // SAFETY: raw is a valid JavaVM pointer obtained from
                                        // the cached process-wide JavaVM. JavaVM::from_raw
                                        // copies the pointer; the JVM owns the lifetime and
                                        // outlives this worker thread.
                                        unsafe { JavaVM::from_raw(raw) }
                                    }
                                    None => {
                                        // Close the fd we took ownership of before returning.
                                        // SAFETY: raw_fd was produced by into_raw_fd() above;
                                        // we own it and must close it on error paths.
                                        unsafe { libc_close(raw_fd) };
                                        return;
                                    }
                                },
                                Err(_) => {
                                    unsafe { libc_close(raw_fd) };
                                    return;
                                }
                            };

                            let attach_result: jni::errors::Result<()> =
                                vm.attach_current_thread(|jni_env| -> jni::errors::Result<()> {
                                    // ── Build TcpStream from the raw fd ───────────────────────────
                                    // SAFETY: raw_fd is the result of OwnedFd::into_raw_fd() on a
                                    // valid, dup'd socket fd. We take exclusive ownership here;
                                    // Android owns and will close the original. The TcpStream will
                                    // close raw_fd when dropped (end of this thread's scope).
                                    let std_stream =
                                        unsafe { std::net::TcpStream::from_raw_fd(raw_fd) };
                                    // Convert to a non-blocking tokio TcpStream inside the runtime.

                                    // ── Run a current-thread tokio runtime ────────────────────────
                                    let rt = match tokio::runtime::Builder::new_current_thread()
                                        .enable_io()
                                        .build()
                                    {
                                        Ok(rt) => rt,
                                        Err(_) => {
                                            // std_stream will close raw_fd on drop.
                                            return Ok(());
                                        }
                                    };

                                    rt.block_on(async move {
                                        // Convert std TcpStream to tokio TcpStream (requires non-blocking).
                                        let _ = std_stream.set_nonblocking(true);
                                        let mut stream =
                                            match tokio::net::TcpStream::from_std(std_stream) {
                                                Ok(s) => s,
                                                Err(_) => return,
                                            };

                                        let session = match fuji_core::SessionId::new(session_id) {
                                            Ok(s) => s,
                                            Err(_) => return,
                                        };
                                        let mut parser = LiveViewParser::new(session);
                                        let runtime_signature =
                                            match RuntimeMethodSignature::from_str("([B)V") {
                                                Ok(signature) => signature,
                                                Err(_) => return,
                                            };

                                        // on_frame: called synchronously for each parsed JPEG frame.
                                        // Allocates a JVM byte[] (unavoidable JNI marshaling cost;
                                        // excluded from the Rust zero-alloc constraint per spec).
                                        // The explicit local frame prevents per-frame byte[] local
                                        // references from accumulating on Android's small JNI local
                                        // reference table during sustained live view.
                                        // No logging on the hot path per privacy-local-first.md and
                                        // the per-frame-no-log constraint.
                                        let on_frame = |jpeg: &[u8]| {
                                            let _: jni::errors::Result<()> = jni_env
                                                .with_local_frame(16, |env| {
                                                    // Allocate JVM byte[] from the JPEG slice.
                                                    let byte_arr =
                                                        env.byte_array_from_slice(jpeg)?;
                                                    let signature =
                                                        MethodSignature::from(&runtime_signature);
                                                    // Call callback.onLiveViewFrame([B)V on the callback GlobalRef.
                                                    let _ = env.call_method(
                                                        callback_global.as_obj(),
                                                        JNIString::from("onLiveViewFrame"),
                                                        signature,
                                                        &[JValue::Object(&byte_arr)],
                                                    );
                                                    // Clear any pending exception so the next frame can proceed.
                                                    if env.exception_check() {
                                                        env.exception_clear();
                                                    }
                                                    Ok(())
                                                });
                                        };

                                        // run_liveview_loop is cancel-safe (see fuji-liveview doc).
                                        let _ = run_liveview_loop(
                                            &mut parser,
                                            &mut stream,
                                            on_frame,
                                            &stop_flag_worker,
                                        )
                                        .await;
                                        // GlobalRef (callback_global) is dropped here -> JVM reference released.
                                    });

                                    Ok(())
                                });
                            if attach_result.is_err() {
                                // SAFETY: raw_fd was produced by into_raw_fd() above and attach
                                // failed before TcpStream::from_raw_fd took ownership.
                                unsafe { libc_close(raw_fd) };
                            }
                        })); // end catch_unwind

                        // On panic: raw_fd may still be open if the panic occurred before
                        // TcpStream::from_raw_fd took ownership. We attempt a best-effort
                        // close; closing an already-closed fd returns EBADF which we ignore.
                        if result.is_err() {
                            // SAFETY: raw_fd is RawFd (Copy). If from_raw_fd ran before the
                            // panic, the TcpStream's Drop already closed it and this close
                            // returns EBADF (ignored). If from_raw_fd had not yet run, this
                            // is the only close, preventing the fd from leaking.
                            unsafe { libc_close(raw_fd) };
                        }
                    });

                let join_handle = match worker {
                    Ok(jh) => jh,
                    Err(_) => {
                        // The closure was never executed, so raw_fd has not been closed.
                        // RawFd is Copy — no destructor fires on drop — so we must close
                        // it here to avoid leaking the fd for the process lifetime.
                        // SAFETY: raw_fd was produced by into_raw_fd() above; we hold
                        // the only copy at this point (spawn failed, closure was dropped).
                        unsafe { libc_close(raw_fd) };
                        return ERR_PANIC;
                    }
                };

                // ── Register the handle in the live-view registry ─────────────────────
                match LIVEVIEW_SESSIONS.lock() {
                    Ok(mut lv) => {
                        lv.insert(
                            session_id,
                            LiveViewHandle {
                                stop_flag,
                                wake_fd,
                                worker: Some(join_handle),
                            },
                        );
                        OK
                    }
                    Err(_) => {
                        // OWNERSHIP/SAFETY: The registry mutex is poisoned, so this
                        // session can never be stopped via nativeLiveViewStop. To
                        // prevent the worker from running forever — holding the
                        // duplicated socket fd and the JVM GlobalRef — we signal it
                        // to stop and block until it exits before returning the error
                        // sentinel. Dropping join_handle without joining would leave
                        // an orphaned thread that leaks the fd and the GlobalRef for
                        // the process lifetime.
                        stop_flag.store(true, Ordering::SeqCst);
                        wake_liveview_reader(&wake_fd);
                        // join() returns Err only if the thread panicked; either way
                        // the thread has exited and released its resources.
                        let _ = join_handle.join();
                        ERR_PANIC
                    }
                }
            }));

            match result {
                Ok(code) => Ok(code as jint),
                Err(_) => {
                    throw_native(env, "native panic in nativeLiveViewStart");
                    Ok(ERR_PANIC as jint)
                }
            }
        })
        .into_outcome()
    {
        Outcome::Ok(value) => value,
        Outcome::Err(_) | Outcome::Panic(_) => ERR_PANIC as jint,
    }
}

/// Stop the Rust live-view read loop for a session.
///
/// Sets the atomic stop flag, joins the worker thread (draining it), releases
/// the callback GlobalRef, and removes the session from the live-view registry.
///
/// Idempotent: unknown or already-stopped sessions return `OK` without error.
///
/// Returns `OK` (0) on clean stop, or `ERR_PANIC` + `NativeException` on panic.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_frameport_nativebridge_NativeFujiJni_nativeLiveViewStop(
    mut env: EnvUnowned<'_>,
    _class: JClass<'_>,
    session_id: jlong,
) -> jint {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if session_id <= 0 {
            // Idempotent: invalid id is not an error.
            return OK;
        }

        // Remove the handle from the registry while holding the lock.
        let maybe_handle = match LIVEVIEW_SESSIONS.lock() {
            Ok(mut lv) => lv.remove(&session_id),
            Err(_) => return ERR_PANIC,
        };

        // If no handle found, the session was already stopped (idempotent).
        let mut handle = match maybe_handle {
            Some(h) => h,
            None => return OK,
        };

        // Signal the worker to exit.
        // Relaxed is correct: the stop_flag is a pure termination signal with
        // no data payload. The preceding Mutex::lock() (LIVEVIEW_SESSIONS) acts
        // as a full fence; the following jh.join() provides acquire semantics
        // that make the store visible to the now-exited worker thread. The
        // reader (run_liveview_loop) uses Relaxed load and only requires
        // eventual visibility — extra loop iterations on weakly-ordered CPUs
        // before observing the flag are acceptable for a stop signal.
        handle.stop_flag.store(true, Ordering::Relaxed);
        wake_liveview_reader(&handle.wake_fd);

        // Join the worker thread; this waits for the read loop to drain and exit.
        // The GlobalRef inside the worker is dropped when the async block exits.
        if let Some(jh) = handle.worker.take() {
            let _ = jh.join();
        }

        OK
    }));

    match result {
        Ok(code) => code as jint,
        Err(_) => {
            throw_native_unowned(&mut env, "native panic in nativeLiveViewStop");
            ERR_PANIC as jint
        }
    }
}

// ---------------------------------------------------------------------------
// JNI exports — USB PTP
// ---------------------------------------------------------------------------

/// Open a USB PTP session for a camera device connected over UsbManager.
///
/// `fd` must be an Android-produced dup of `UsbDeviceConnection.fileDescriptor` (>= 0).
/// // OWNERSHIP: Android keeps + closes the original via UsbDeviceConnection.close().
/// //            Rust owns + closes the dup via the UsbPtpSession stored in USB_SESSIONS.
/// `descriptors` is a JVM byte[] containing the raw USB interface descriptor bytes;
/// Rust copies the bytes out via `convert_byte_array` before returning, so the JVM
/// is free to GC the array afterward.
///
/// Returns the session id (> 0) on success, or a negative ERR_* sentinel on expected
/// failure. On panic, throws `NativeException` and returns `ERR_PANIC`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_frameport_nativebridge_NativeFujiJni_nativeUsbSessionOpen(
    mut env: EnvUnowned<'_>,
    _class: JClass<'_>,
    fd: jint,
    descriptors: JByteArray<'_>,
) -> jlong {
    match env
        .with_env(|env| -> jni::errors::Result<jlong> {
            let result = catch_unwind(AssertUnwindSafe(|| {
                // Convert the JVM byte[] to an owned Vec<u8> before any Rust logic runs.
                // convert_byte_array copies bytes out; the JVM array is not pinned.
                let descriptor_bytes: Vec<u8> = match env.convert_byte_array(&descriptors) {
                    Ok(bytes) => bytes,
                    Err(_) => {
                        // OWNERSHIP: Android already transferred the dup'd fd to Rust. Since
                        // native_usb_session_open is never reached, close it here to avoid a leak.
                        if fd >= 0 {
                            // SAFETY: fd is the Android dup, owned by Rust and not yet wrapped.
                            unsafe { libc_close(fd) };
                        }
                        return i64::from(ERR_INVALID_SESSION);
                    }
                };
                native_usb_session_open(fd, &descriptor_bytes)
            }));
            match result {
                Ok(id) => Ok(id as jlong),
                Err(_) => {
                    throw_native(env, "native panic in nativeUsbSessionOpen");
                    Ok(i64::from(ERR_PANIC) as jlong)
                }
            }
        })
        .into_outcome()
    {
        Outcome::Ok(value) => value,
        Outcome::Err(_) | Outcome::Panic(_) => i64::from(ERR_PANIC) as jlong,
    }
}

/// Close a USB PTP session previously opened via `nativeUsbSessionOpen`.
///
/// Removes the UsbPtpSession from USB_SESSIONS (closing Rust's dup of the USB device fd).
/// Android closes the original UsbDeviceConnection fd independently.
///
/// Idempotent: unknown session ids return `OK` (0) without error.
///
/// Returns `OK` (0) on success, or `ERR_PANIC` (-100) + `NativeException` on panic.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_frameport_nativebridge_NativeFujiJni_nativeUsbSessionClose(
    mut env: EnvUnowned<'_>,
    _class: JClass<'_>,
    session_id: jlong,
) -> jint {
    let result = catch_unwind(AssertUnwindSafe(|| native_usb_session_close(session_id)));
    match result {
        Ok(code) => code as jint,
        Err(_) => {
            throw_native_unowned(&mut env, "native panic in nativeUsbSessionClose");
            ERR_PANIC as jint
        }
    }
}

/// Close a raw file descriptor via libc. Used on error paths where an OwnedFd
/// was converted to a raw fd via into_raw_fd() and must be closed manually.
///
/// # Safety
/// Caller must guarantee `fd` is a valid open file descriptor and that no other
/// code will use or close it after this call.
#[inline]
unsafe fn libc_close(fd: i32) {
    // SAFETY: precondition documented on callers above.
    unsafe extern "C" {
        fn close(fd: i32) -> i32;
    }
    // SAFETY: close() is safe to call with a valid fd; precondition on callers.
    let _ = unsafe { close(fd) };
}

/// Wake the live-view worker if it is blocked in a socket read.
///
/// The fd is a duplicate of the same socket description used by the worker's
/// TcpStream. `shutdown(SHUT_RDWR)` interrupts blocking reads on all duplicates
/// of that socket; dropping `wake_fd` later closes only this duplicate.
#[inline]
fn wake_liveview_reader(wake_fd: &OwnedFd) {
    const SHUT_RDWR: i32 = 2;

    unsafe extern "C" {
        fn shutdown(fd: i32, how: i32) -> i32;
    }

    // SAFETY: wake_fd is a live socket fd duplicate owned by LiveViewHandle.
    // shutdown() may fail if the worker already closed the socket; stop remains
    // idempotent and the error is intentionally ignored before join().
    let _ = unsafe { shutdown(wake_fd.as_raw_fd(), SHUT_RDWR) };
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
        USB_SESSIONS.lock().unwrap().clear();
        TRANSFERS.lock().unwrap().clear();
    }

    #[test]
    fn wake_liveview_reader_shuts_down_socket_duplicate() {
        use std::io::Read;
        use std::os::unix::net::UnixStream;
        use std::time::Duration;

        let (mut reader, _writer) = UnixStream::pair().expect("create socket pair");
        reader
            .set_read_timeout(Some(Duration::from_secs(1)))
            .expect("set read timeout");
        // SAFETY: reader.as_raw_fd() is a live socket fd for the duration of this
        // immediate dup. The returned OwnedFd is independent of reader.
        let wake_fd = unsafe { BorrowedFd::borrow_raw(reader.as_raw_fd()) }
            .try_clone_to_owned()
            .expect("dup reader fd");

        let read_thread = std::thread::spawn(move || {
            let mut byte = [0u8; 1];
            reader.read(&mut byte)
        });

        wake_liveview_reader(&wake_fd);

        let read_result = read_thread.join().expect("reader thread should not panic");
        assert_eq!(read_result.expect("read should complete"), 0);
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

    // ----- catch_unwind panic-interception contract -----

    /// Verify that the catch_unwind pattern used in every JNI entry point
    /// maps a panicking inner function to the ERR_PANIC sentinel.
    ///
    /// This test exercises the exact pattern used at each JNI boundary:
    ///   catch_unwind(inner_fn).unwrap_or(ERR_PANIC)
    /// and confirms that a panicking inner fn produces ERR_PANIC rather than
    /// unwinding past the catch site (which under panic=abort would abort the
    /// process, and under panic=unwind would silently lose the error code).
    #[test]
    fn catch_unwind_maps_panic_to_err_panic_sentinel() {
        fn always_panics() -> i32 {
            panic!("deliberate test panic");
        }

        let result: i32 = catch_unwind(always_panics).unwrap_or(ERR_PANIC);
        assert_eq!(
            result, ERR_PANIC,
            "a panicking inner fn must produce ERR_PANIC ({ERR_PANIC}), got {result}"
        );
    }

    /// Verify that a non-panicking inner fn still returns its real value
    /// through the same catch_unwind wrapper (i.e., the wrapper is transparent
    /// on the happy path and does not corrupt the return value).
    #[test]
    fn catch_unwind_is_transparent_on_success() {
        fn returns_ok() -> i32 {
            OK
        }

        let result: i32 = catch_unwind(returns_ok).unwrap_or(ERR_PANIC);
        assert_eq!(
            result, OK,
            "a successful inner fn must return OK ({OK}) through catch_unwind, got {result}"
        );
    }
}
