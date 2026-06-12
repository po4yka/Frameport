---
name: fd-handoff
description: Use when authoring or reviewing any JNI function in fuji-ffi or the NativeFujiJni bridge that accepts, duplicates, or transfers a file descriptor between Kotlin and Rust; when adding Os.dup() / ParcelFileDescriptor.detachFd() / adoptFd() call sites in :camera:wifi, :camera:data, or :camera:usb; when wrapping a raw fd in OwnedFd / BorrowedFd inside any fuji-rs crate; or when debugging a double-close, EBADF, fdsan abort, or fd-leak in the camera Wi-Fi socket handoff or MediaStore import pipeline.
---

# fd-handoff — File-Descriptor Ownership Across the JNI Boundary

## When to use

- Adding or reviewing a JNI function in `fuji-ffi` (crate `fuji-ffi`, JNI symbol namespace `dev.po4yka.frameport.nativebridge.NativeFujiJni`) that accepts an `int` / `jint` fd parameter.
- Writing or reviewing the Android side of the Wi-Fi socket handoff in `:camera:wifi` (class `CameraWifiConnector`) or `:camera:data`.
- Writing or reviewing the MediaStore import fd handoff in `:camera:media` or `:core:storage`.
- Wrapping an fd received from JNI in `OwnedFd`, `BorrowedFd`, or a session struct inside any crate under `rust/fuji-rs/crates/`.
- Debugging a process abort caused by fdsan (fatal on minSdk 31 / Android 12, Frameport's baseline).
- Debugging `EBADF`, double-close, or fd-leak symptoms in the camera stack.
- Reviewing any diff that touches `Os.dup`, `ParcelFileDescriptor.detachFd()`, `ParcelFileDescriptor.adoptFd()`, `OwnedFd::from_raw_fd`, or `AFileDescriptor_getFd`.

---

## Core invariant (non-negotiable)

**Exactly one owner closes each fd. Android owns setup; Rust owns protocol.**

The fd-ownership rule from `docs/rust/fd-ownership.md`:

> Android must duplicate descriptors before transferring ownership if the Android side keeps its own object. Rust must close only descriptors it explicitly owns. Kotlin must not reuse a descriptor after ownership moves.

ADR 0002 (`docs/adr/0002-wifi-socket-fd-handoff.md`, Accepted 2026-06-11) makes the Wi-Fi socket fd handoff a first-class architecture primitive, not an implementation detail.

---

## Android API reference

| API | Effect on fd ownership |
|-----|------------------------|
| `ParcelFileDescriptor.detachFd()` | Transfers ownership to the caller. PFD is marked closed (`mClosed = true`), `STATUS_DETACHED` is sent to any peer. Caller must `close(fd)` (Rust `OwnedFd` Drop handles this). Do NOT call `pfd.close()` afterwards. |
| `ParcelFileDescriptor.adoptFd(int)` | Inverse of `detachFd()`. Takes ownership without dup. Caller must NOT close the original fd after this call. |
| `ParcelFileDescriptor.dup()` | POSIX dup; returns a new PFD that independently owns its fd. The original PFD is unaffected. |
| `ParcelFileDescriptor.fromFd(int)` | Wraps by duplication. The original fd is NOT consumed; caller still owns and must close it. |
| `ParcelFileDescriptor.fromSocket(Socket)` | Internally dups the socket fd. Original `Socket` object must still be closed independently. |
| `android.system.Os.dup(FileDescriptor)` | Kotlin-accessible dup; result has `O_CLOEXEC` set. Use to keep an Android-side copy while transferring the dup to Rust. |
| `AFileDescriptor_getFd(JNIEnv*, jobject)` | NDK API 31+ (available on all Frameport devices). Reads the raw fd int from a `java.io.FileDescriptor`. Returns -1 for an invalid descriptor. Prefer over reflection. |
| `AFileDescriptor_setFd(JNIEnv*, jobject, int)` | NDK API 31+. Writes fd int into a `java.io.FileDescriptor` with no validation. Only use when you know the fd is valid and unowned by Java. |
| `AFileDescriptor_create(JNIEnv*)` | NDK API 31+. Creates a `java.io.FileDescriptor` initialized to -1. |

---

## Rust API reference

| API | Ownership semantics |
|-----|---------------------|
| `OwnedFd::from_raw_fd(fd)` | `unsafe`. Takes sole ownership. Panics if `fd == -1`. If the same fd int is wrapped twice, `Drop` will double-close it — causing an OS error or fatal fdsan abort; there is no Rust-level double-wrap detection at `Drop`. |
| `OwnedFd::into_raw_fd(self)` | Consumes `self` and returns the raw fd without closing it. Caller takes ownership and must eventually close the returned `RawFd`. |
| `OwnedFd::as_raw_fd(&self)` | Borrows fd int. Does NOT transfer ownership. Never pass the result to `from_raw_fd`. |
| `OwnedFd::try_clone(&self)` | Dups via `fcntl(F_DUPFD_CLOEXEC)`. Both resulting `OwnedFd` values are independent. |
| `BorrowedFd<'a>` | Lifetime-bound; does NOT close on drop. For short-lived read-only FFI access that does not transfer ownership. |

All four types are stable since Rust 1.63.0 (RFC 3128). Frameport's `edition = "2024"` workspace has unconditional access to them.

---

## Canonical patterns

### Pattern 1 — Wi-Fi socket ownership transfer (the primary path)

Android opens and connects the socket, then transfers a dup to Rust. Android retains the original socket for its own lifecycle management. This is the pattern mandated by ADR 0002.

**Kotlin (`:camera:wifi` — `CameraWifiConnector`):**

```kotlin
// Android owns: request network, bind socket to Network, connect.
// After connect succeeds, dup the fd so Rust owns its copy independently.
val socket: Socket = network.socketFactory.createSocket()
network.bindSocket(socket)
socket.connect(InetSocketAddress(cameraIp, COMMAND_PORT), CONNECT_TIMEOUT_MS)

// Os.dup produces a new fd with O_CLOEXEC. The original socket is NOT transferred.
val dupFd: FileDescriptor = Os.dup(socket.fileDescriptor)
val rawFd: Int = dupFd.fd          // reflect or use AFileDescriptor_getFd from NDK side
                                   // (see note on jni 0.21 below)

try {
    nativeSdk.openWifiSession(rawFd, endpointMetadata)  // Rust now owns rawFd
    // rawFd must not be closed here; OwnedFd Drop in Rust will close it.
    Os.close(dupFd)                // WRONG — already transferred; delete this line
} catch (e: Exception) {
    Os.close(dupFd)                // Only close the dup on error, before Rust ever saw it
    throw e
}
// socket.close() at end of Android lifecycle — closes the original fd, independent of Rust's dup.
```

> Note: `dupFd.fd` uses Java reflection on the `FileDescriptor.descriptor` field. In JNI C/C++ code inside `fuji-ffi`, prefer `AFileDescriptor_getFd(env, fdObject)` (NDK API 31+, available on all Frameport devices) over reflection. The `jni` crate 0.21 does not ship a `FileDescriptor` helper; use a raw `unsafe` NDK call or a thin JNI helper function.

**Rust (`fuji-ffi` crate, `fn Java_dev_po4yka_frameport_nativebridge_NativeFujiJni_openWifiSession`):**

```rust
use std::os::fd::{FromRawFd, OwnedFd, RawFd};

/// # Ownership
/// `command_fd` — TAKES OWNERSHIP. Caller must not close this fd after the call.
/// The fd is wrapped in `OwnedFd`; `Drop` closes it when the session ends.
#[no_mangle]
pub extern "system" fn Java_dev_po4yka_frameport_nativebridge_NativeFujiJni_openWifiSession(
    env: JNIEnv,
    _class: JClass,
    command_fd: jint,
    // ... other params
) -> jlong {
    // Guard against -1 (invalid fd from AFileDescriptor_getFd on a closed descriptor).
    if command_fd < 0 {
        // map to a typed error; do not panic
        return SESSION_ID_INVALID;
    }

    // SAFETY: command_fd is an open, valid, OS-returned fd passed from Android
    // via Os.dup(). Android transferred ownership by passing the dup; Android
    // still owns the original socket separately. No other OwnedFd wraps this fd.
    let owned: OwnedFd = unsafe { OwnedFd::from_raw_fd(command_fd as RawFd) };

    match catch_unwind(AssertUnwindSafe(|| {
        FujiSession::open(owned, /* ... */)
    })) {
        Ok(Ok(session)) => register_session(session),
        Ok(Err(e)) => { map_error_to_jni(&env, e); SESSION_ID_INVALID }
        Err(_) => { map_panic_to_jni(&env); SESSION_ID_INVALID }
    }
}
```

### Pattern 2 — MediaStore import fd (detachFd ownership transfer)

For MediaStore write-through, Android opens a `ParcelFileDescriptor` from the pending URI, then detaches it to give Rust sole ownership.

**Kotlin:**

```kotlin
// Android creates the pending MediaStore entry and opens the PFD.
val pfd: ParcelFileDescriptor = contentResolver.openFileDescriptor(pendingUri, "w")
    ?: error("Failed to open output fd for $pendingUri")

// detachFd() transfers ownership. PFD.mClosed is set to true internally.
// STATUS_DETACHED is sent to any ParcelFileDescriptor.OnCloseListener peer.
val rawFd: Int = pfd.detachFd()

// Do NOT call pfd.close() after detachFd() — pfd is already marked closed.
// Do NOT let pfd become unreachable before detachFd() is called (GC finalizer
// would attempt close() on the same fd Rust will close — fatal fdsan abort).

nativeSdk.downloadObjectToFd(sessionId, objectId, rawFd)  // Rust owns rawFd
```

**Rust:**

```rust
/// # Ownership
/// `output_fd` — TAKES OWNERSHIP. Corresponds to a writable MediaStore PFD
/// that was detachFd()'d on the Kotlin side. Rust closes it on completion or error.
pub fn download_object_to_fd(session: &FujiSession, object_id: u32, output_fd: jint) -> Result<()> {
    assert!(output_fd >= 0, "caller must validate fd before passing");
    // SAFETY: Kotlin called pfd.detachFd(); no other owner holds this fd.
    let owned = unsafe { OwnedFd::from_raw_fd(output_fd as RawFd) };
    session.stream_object_to_fd(object_id, owned)
    // OwnedFd::Drop closes the fd when stream_object_to_fd returns.
}
```

### Pattern 3 — Short-lived borrow (Rust reads from an Android-owned fd, no ownership transfer)

Use `BorrowedFd` when Rust needs to call a syscall on an fd without taking ownership. This is appropriate for diagnostic reads or ping/probe operations where Android retains the fd.

```rust
use std::os::fd::BorrowedFd;

/// # Ownership
/// `probe_fd` — BORROWED. Android retains ownership. Rust must not store this fd.
/// The borrow is valid only for the duration of this call.
pub fn probe_connection(probe_fd: jint) -> bool {
    if probe_fd < 0 { return false; }
    // SAFETY: probe_fd is valid for the duration of this synchronous JNI call.
    // Android holds the PFD open; this function does not outlive the call frame.
    let borrowed = unsafe { BorrowedFd::borrow_raw(probe_fd as RawFd) };
    is_fd_readable(borrowed)
}
```

---

## Layer routing (mandatory — from `docs/rust/ffi-boundary.md` and ADR 0001/0007)

```
Composable
  └── ViewModel
        └── CameraWifiConnector (interface in :camera:wifi or :camera:data)
              └── JniNativeFujiSdk.openWifiSession(rawFd, …)
                    └── fuji-ffi JNI export
                          └── OwnedFd → FujiSession in fuji-ptpip / fuji-ptp
```

- Composables never touch fds. No fd argument must ever appear in a `@Composable` function or be stored in UI state.
- ViewModels must not call JNI fd-passing functions directly. All fd handoff goes through `CameraWifiConnector` or an equivalent interface in `:camera:wifi` / `:camera:data`.
- `JniNativeFujiSdk` checks that `libfuji_ffi.so` loaded before calling any JNI function. The load-check gate in `NativeLibraryLoader` must not be bypassed.
- `NativeFujiSdk` (Kotlin interface) must carry a KDoc ownership tag on every method that accepts a `FileDescriptor` or raw `Int` fd — either `@param fooFd TAKES OWNERSHIP` or `@param fooFd BORROWED`.
- `fuji-ffi` Rust doc comments must carry a `# Ownership` section (as shown in the patterns above) for every `jint` fd parameter.

---

## fdsan discipline (critical — minSdk 31 means fatal from day one)

Android 10 (API 29) introduced fdsan in warn-once mode. Android 11 (API 30) changed the default to **fatal**. Frameport's minSdk is 31 (Android 12). Every device Frameport runs on enforces fatal fdsan. There is no grace period.

Fatal fdsan triggers on:
- Double-close of the same fd number (even if OS has reused it between closes).
- Closing an fd with a mismatched owner tag.

**The GC finalizer trap.** If a `ParcelFileDescriptor` is created and not explicitly closed before it becomes unreachable, the GC finalizer will call `close()` on the underlying fd. If `detachFd()` was already called and Rust already closed that fd (and the OS reused the fd number), the finalizer closes an unrelated fd — potentially triggering fdsan fatal abort (NDK issue #1517). Fix: always call `pfd.close()` explicitly (or use Kotlin's `use {}` extension) in every code path where `detachFd()` has NOT been called. After `detachFd()` is called, do not call `close()`.

```kotlin
// Correct: explicit lifecycle on both branches.
val pfd = contentResolver.openFileDescriptor(uri, "w") ?: error("null pfd")
val rawFd = try {
    pfd.detachFd()           // ownership moves to Rust; do NOT close pfd after this
} catch (e: Exception) {
    pfd.close()              // detachFd failed; close normally
    throw e
}
// rawFd is now Rust's responsibility.
```

```kotlin
// Correct: use{} for the dup path where Android retains the original.
Os.dup(socket.fileDescriptor).use { dupFd ->
    // dupFd is a FileDescriptor; call Os.close(dupFd) in finally if transferring fails.
}
// Note: FileDescriptor does not implement Closeable directly; use try/finally with Os.close().
```

---

## Pitfalls checklist (review every diff against this list)

| # | Pitfall | How to catch it |
|---|---------|-----------------|
| 1 | `OwnedFd::from_raw_fd(fd)` called twice on the same fd int | Any JNI call that converts a jint to OwnedFd must have exactly one call site. Search `from_raw_fd` in the diff. |
| 2 | `OwnedFd::as_raw_fd()` result wrapped in a second `OwnedFd` | Use `try_clone()` when you need two owners. |
| 3 | `pfd.detachFd()` + `pfd.close()` both called | After `detachFd()`, PFD is internally closed. Calling `close()` will double-close. |
| 4 | `ParcelFileDescriptor.fromFd(fd)` or `fromSocket()` called, original fd leaked | Both APIs dup internally. Caller still owns the original and must close it. |
| 5 | `adoptFd(fd)` + caller closes original fd | `adoptFd` takes ownership without dup. Caller's close triggers fdsan fatal abort. |
| 6 | Kotlin and Rust both close the same fd (no dup before handoff) | Always dup on the Android side when Android also needs to keep using its object. |
| 7 | PFD allowed to be GC-finalized after `detachFd()` without explicit `close()` on the non-detach path | Use `use {}` / `try-finally` with explicit `close()` on error paths. |
| 8 | `OwnedFd::from_raw_fd(-1)` called without fd validity check | `AFileDescriptor_getFd` returns -1 for invalid descriptors. Check `fd >= 0` before the `unsafe` call; a panic across JNI is UB. |
| 9 | `BorrowedFd` borrow outlives the `OwnedFd` or the JNI call frame | Only use `BorrowedFd::borrow_raw` within the synchronous JNI call that received the fd. |
| 10 | `jobject` (FileDescriptor object) stored across JNI call frames | JNI local refs are valid only within the call frame. Store the fd `int`, not the `jobject`. Promote to global ref only if the `jobject` lifetime is explicitly managed. |
| 11 | `AFileDescriptor_setFd` used with an invalid or already-closed fd | The function performs no validation. Only call with a confirmed open, unowned fd. |
| 12 | Plain `dup2` used instead of `Os.dup` or `F_DUPFD_CLOEXEC` | Both `Os.dup` (Kotlin) and `OwnedFd::try_clone` (Rust) set `O_CLOEXEC`. Camera session fds must not leak into forked child processes. |

---

## fuji-ffi implementation checklist

For every new JNI function added to `fuji-ffi` that accepts a descriptor:

- [ ] Rust doc comment has `# Ownership` section specifying TAKES OWNERSHIP or BORROWED for each fd parameter.
- [ ] `NativeFujiSdk` Kotlin interface KDoc has `@param <name> TAKES OWNERSHIP` or `BORROWED`.
- [ ] `fd >= 0` checked before `OwnedFd::from_raw_fd`; error is mapped to a typed Kotlin domain error (not a panic).
- [ ] `catch_unwind` wraps all Rust logic; panics are mapped to JNI exceptions, not propagated across the boundary.
- [ ] The function is coarse-grained (session-level, not packet-level) per ADR 0002 JNI rules.
- [ ] The `JniNativeFujiSdk` load-check gate is not bypassed.
- [ ] ViewModel layer does not call this function directly; it goes through a `CameraWifiConnector` or `:camera:data` interface.

---

## Testing guidance

**Kotlin unit tests (`:camera:wifi`, `:camera:data`):**
- Fake `CameraWifiConnector` must verify that `Os.dup` is called before ownership handoff when the socket object is retained.
- Cancellation tests must verify that fds passed to Rust before cancellation are not double-closed.

**Rust unit tests (`fuji-ffi`, `fuji-ptpip`):**
- Use `OwnedFd` wrapping a pipe (`pipe2(O_CLOEXEC)`) to test fd-backed session open/close.
- Verify `OwnedFd` Drop closes the fd (assert `fcntl(fd, F_GETFD)` returns `EBADF` after drop).
- Test that `fd == -1` input is rejected with a typed error, not a panic.

**Physical hardware tests (from ADR 0002):**
- Fujifilm X-T5 with current firmware (verify exact port numbers and session handshake behavior against a real device — PTP-IP specifics not asserted here).
- Repeated connect/disconnect cycles to verify no fd accumulation (check `/proc/self/fd` count before and after).

---

## References

- [ParcelFileDescriptor API Reference — Android Developers](https://developer.android.com/reference/android/os/ParcelFileDescriptor)
- [ParcelFileDescriptor.java AOSP source (master)](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/os/ParcelFileDescriptor.java)
- [File Descriptor NDK API Reference (AFileDescriptor_getFd etc.) — Android NDK](https://developer.android.com/ndk/reference/group/file-descriptor)
- [fdsan.md — Android bionic (platform/bionic master)](https://android.googlesource.com/platform/bionic/+/master/docs/fdsan.md)
- [std::os::fd::OwnedFd — Rust std docs](https://doc.rust-lang.org/std/os/fd/struct.OwnedFd.html)
- [RFC 3128: I/O Safety — The Rust RFC Book](https://rust-lang.github.io/rfcs/3128-io-safety.html)
- [NDK issue #1517: fdsan false report when Java finalizer closes fd that JNI reused](https://github.com/android/ndk/issues/1517)
- `docs/rust/fd-ownership.md` — Frameport fd ownership rule (primary source of truth for this project)
- `docs/adr/0002-wifi-socket-fd-handoff.md` — accepted ADR mandating the fd handoff architecture
- `docs/rust/ffi-boundary.md` — current fuji-ffi export inventory and layer rules
