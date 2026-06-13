# Media Transfer & Liveview — Fujifilm PTP-IP Interoperability Reference

Clean-room interoperability reference for Frameport. Synthesized from open-source reverse-engineering corpus (fuji-cam-wifi-tool, libfuji/libpict, fujihack/camlib, filmkit, XApp 2.7.5 native stack analysis). All facts are observable on the wire or derivable from the PTP/PTP-IP specifications. No proprietary code or assets are reproduced. See the Caveats section before implementing.

Related Frameport documents: `docs/adr/0002-wifi-socket-fd-handoff.md`, `docs/adr/0004-media-import-pipeline.md`, `docs/protocol/media-transfer.md`, `docs/protocol/liveview.md`, `docs/protocol/wifi-ptp-ip.md`.

---

## 1. Transport Topology

Fujifilm cameras use three independent TCP connections, all to the fixed camera AP address `192.168.0.1`. They do not use the ISO PTP-IP standard port 15740.

| Channel | Port | Direction | When opened |
|---|---|---|---|
| Command | 55740 | bidirectional control | first, during handshake |
| Event | 55741 | camera → host async events | lazy: only after `InitiateOpenCapture` succeeds |
| Liveview / Through-Picture | 55742 | camera → host MJPEG stream | lazy: only after event channel is open, for liveview mode |

All three sockets must be created and bound to the Android `Network` object representing the camera Wi-Fi before `connect()` is called (ADR-0002). Rust receives pre-connected, Android-owned file descriptors. Rust must not open TCP connections directly in production.

The XApp native stack names these slots indexed as `portNo - 0xD9BC` (0 = command, 1 = event, 2 = liveview). The Java enum labels for ports 55741 and 55742 are swapped relative to their native usage; the port numbers above are the ground-truth values confirmed from multiple independent sources.

---

## 2. Session Lifecycle Prerequisite

Media enumeration, thumbnail retrieval, and full-object download all require a live PTP-IP command session. The session must reach the `Ready` state through the following ordered steps before any transfer operation is issued:

1. TCP connect to port 55740 (Android-side, Network-bound).
2. Send `Init_Command_Request` (82 bytes). Receive `Init_Command_Ack` (68 bytes). Retry on BUSY status `0x2019` up to 5 times with 500 ms between attempts. Fatal on codes `0x201D`, `0x201E`, `0x2000`.
3. Wait 50 ms (required for Wi-Fi connected-device mode before proceeding).
4. Send PTP `OpenSession` (opcode `0x1002`, `SessionID = 1` hardcoded — not negotiated). Transaction ID must be 0 for this call; subsequent calls use a monotonically incrementing counter that wraps `0xFFFFFFFF → 1`.
5. Call `SetFunctionMode` with the desired mode code. Poll with BUSY retry: up to 3 loops of 5 passes each, 100 ms between passes, retry trigger is error code 4102.
6. Call `GetFunctionVersion` (reads Fpcsh capability version codes in range `0xDF21`–`0xDF31`). These gate which operations are available.

For media import (receive mode), the function mode code is `IMAGE_RECEIVE = 1`. For remote shutter / liveview, it is `REMOTE = 5`. For through-picture streaming it is `IMAGE_LIVE_VIEW = 22`. The mode must match the intended operation class; issuing transfer commands without the correct mode results in errors.

Version negotiation properties (read then re-write to confirm support) — required by newer firmware, ignored safely on older firmware:
- `GetObjectVersion` / `ImageGetVersion` (`0xDF22`, `0xDF21`)
- `RemoteGetObjectVersion` (`0xDF25`)
- `RemoteVersion` (`0xDF24`) — Camera Connect app uses value `0x2000C`

If version negotiation is skipped on cameras that require it, downloads will fail silently.

---

## 3. Object Enumeration

### 3.1 Object Handle Model

Fujifilm cameras over Wi-Fi do not use the standard PTP `GetObjectHandles` (opcode `0x1007`) in the same way as USB PTP. Two distinct models exist depending on session mode:

**Normal receive mode (IMAGE_RECEIVE):** Object handles are sequential integers `1 … N`. The total count `N` is available from device property `ObjectCount` (`0xD222`), which appears in the event list polled via `GetDevicePropValue` on `EventsList` (`0xD212`) after session open. Do not call `GetObjectHandles`; enumerate by index.

**MULTIPLE_TRANSFER mode (camera state = 1):** The handle is always literally `1`. After each successful download, the camera advances its internal pointer. Polling `EventsList` after each download tells you whether more objects remain. When the camera has no more images, it closes the connection.

The XApp native stack uses Fujifilm-extension wrappers named `GetSpecifiedObjectHandles` and `GetExtensionObjectInfo` for Wi-Fi enumeration rather than bare standard PTP calls. These map to the same underlying opcodes but with Fujifilm-specific parameter conventions.

### 3.2 Event List Polling

The camera does not deliver events as unsolicited PTP events over port 55741 during normal image-receive sessions. Instead, poll `GetDevicePropValue` on property `EventsList` (`0xD212`). The response is a `u16` count followed by `count × (u16 property_code, u32 value)` pairs. This must also be called periodically during long transfers to keep the session alive — the camera will time out and close the connection if no activity occurs.

The response for `EventsList` can include:
- `ObjectCount` (`0xD222`) — number of objects available for transfer
- `FreeSDRAMImages` (`0xD20E`) — nonzero value signals a newly captured image is available for wireless tether download
- `CameraState` (`0xDF00`) — current camera state (see below)

Camera state values: `0 = WAIT_FOR_ACCESS`, `1 = MULTIPLE_TRANSFER`, `2 = FULL_ACCESS`, `3 = PC_AUTO_SAVE`, `6 = REMOTE_ACCESS`. Poll until `CameraState != WAIT_FOR_ACCESS` before issuing transfer operations.

### 3.3 Object Info Request

To obtain metadata for a specific object by index (1-based), use:

**Standard PTP path (image_info_by_index style):** Send `GetObjectInfo` (`0x1008`) with the object handle as `param[0]`. The response is a PTP `ObjectInfo` structure containing:
- `ObjectFormat` — u16 format code (see Section 4)
- `ObjectCompressedSize` — u32 file size (note: this is unreliable by default — see Section 3.5)
- `Filename` — PTP string (u8 char count including null + UCS-2LE code units)
- `CaptureDate`, `ModificationDate` — PTP strings in ISO 8601-like format
- `ParentObject`, `StorageID`, `ThumbFormat`, `ThumbCompressedSize`, `ThumbPixWidth`, `ThumbPixHeight`, `ImagePixWidth`, `ImagePixHeight`

The `Filename` field from the camera should be treated as untrusted input. Sanitize before use in MediaStore paths.

From the fuji-cam-wifi-tool corpus, the older Fuji-framed `image_info_by_index` opcode (`0x1008` with Fuji container framing) returns a ~154-byte response including a `u32 image_id` field that is distinct from the sequential index. This `image_id` is required as the second parameter when calling `full_image` (`0x101b`) on some camera/firmware combinations (see Section 5.2).

### 3.4 EnableCorrectFileSize

Before calling `GetObjectInfo` or initiating a download, set device property `EnableCorrectFileSize` (`0xD227`) to `1`. Without this, the camera reports a placeholder size (approximately 100 KB regardless of actual file size). Reset to `0` after the download completes. Omitting this step makes transfer-progress calculations completely wrong.

```text
SetDevicePropValue(0xD227, value=1)   // before GetObjectInfo
GetObjectInfo(handle)                  // now returns accurate size
[transfer sequence]
SetDevicePropValue(0xD227, value=0)   // after download
```

---

## 4. Object Format Codes

PTP object format codes observed from Fujifilm cameras (standard PTP codes, not Fujifilm-specific):

| Format | PTP Wire Code | XSDK Internal Code | Description |
|---|---|---|---|
| JPEG | `0x3801` (14337) or `0x3808` (14344) | 7 | Standard JPEG |
| RAF (Fuji RAW) | `0xB103` (45315) | 1 | Fujifilm RAF raw format |
| MOV | `0x300D` (12301) | 8 | QuickTime movie |
| HEIF | `0xB982` (47490) | 18 | High Efficiency Image Format |

The XSDK internal codes carry rotation in the high byte: `0x06xx = 90°`, `0x03xx = 180°`, `0x08xx = 270°`. The low byte carries the format. This encoding is internal to the XSDK translation layer; the wire format uses the standard PTP object format codes.

For RAW conversion and upload workflows (not applicable to Frameport v1 image import), the special object format code `0xF802` is used with the filename `FUP_FILE.dat`.

---

## 5. Transfer Sequence

### 5.1 Thumbnail Retrieval

Thumbnails are retrieved with standard PTP `GetThumb` (opcode `0x100A`):

```text
GetThumb(param[0] = object_handle)
→ DataStart packet (20 bytes: length u32, type=0x9 u32, txn u32, payload_length u64)
→ DataEnd packet (12-byte header + JPEG bytes)
→ Response packet
```

The response payload is raw JPEG data. For RAF, MOV, and HEIF objects larger than 10 MB, the XApp extracts thumbnails from embedded EXIF data rather than relying on the PTP thumbnail field. The threshold is 10 485 760 bytes (`0xA00000`).

The older Fuji-framed path uses opcode `0x100a` (`thumbnail_by_index`) with a 4-byte image index payload. The `camera_last_image` opcode (`0x9022`) retrieves the thumbnail of the most recently captured image with no parameters; the first 8 bytes of the response are a header, JPEG data starts at byte 8.

### 5.2 Full Object Download — Chunked via GetPartialObject

Full-resolution objects are downloaded using `GetPartialObject` (opcode `0x101B`) in chunks. Do not use `GetObject` (`0x1009`) for Wi-Fi transfers — it may stall on some cameras.

```text
loop:
  GetPartialObject(
    param[0] = object_handle,
    param[1] = byte_offset,           // starts at 0
    param[2] = min(remaining, chunk)  // chunk = 0x100000 (1 MB)
  )
  → DataStart + DataEnd + Response
  write DataEnd payload to output fd
  offset += bytes_received
  if offset >= total_size: break
  call GetDevicePropValue(EventsList) to keep session alive
```

**Chunk size:** The maximum safe chunk size is `0x100000` bytes (1 048 576, 1 MB). Some cameras (confirmed on X-A2) stall permanently with a single large-object request even when the total file size is under 1 MB. Always chunk regardless of file size.

**Alternative Fuji-framed path:** The older fuji-cam-wifi-tool corpus uses opcode `0x101b` (`full_image`) with an 8-byte payload: `[4-byte image index (u32 LE)][4-byte image_id (u32 LE)]`. The `image_id` comes from the `image_info_by_index` response. This path may be required on older camera firmware that does not support the standard PTP `GetPartialObject` chunking sequence.

### 5.3 fd-Streaming Pattern (ADR-0002 + ADR-0004)

The Rust transfer engine receives an owned output file descriptor from Android (duplicated on the Android side before handoff). The download loop writes each chunk directly to the fd using standard POSIX write semantics:

```text
Android:                          Rust:
  create MediaStore pending item
  open ParcelFileDescriptor
  dup(fd) → owned_fd
  pass owned_fd via JNI ──────► receive owned i32 fd
                                  wrap as BufWriter<File>
                                  loop GetPartialObject chunks:
                                    write chunk bytes to fd
                                    emit TransferProgress
                                    check cancellation token
                                  close fd
  ◄─── TransferProgress events
  ◄─── Success / typed error
  finalize IS_PENDING = 0
  or delete pending item on failure
```

Rust must not hold the entire object in memory. Write each chunk as received. The fd must be closed by Rust on completion, cancellation, and error. Android must not close the Rust-owned fd while Rust is writing.

### 5.4 Progress Reporting

Progress events should be throttled to avoid JNI overhead. A reasonable strategy: emit a progress event no more than once per 100 KB written, or at a fixed time interval (e.g., 250 ms), whichever is more frequent. The final event at 100% must always be emitted.

```text
TransferProgress {
    bytes_written: u64,
    total_bytes: Option<u64>,   // None if EnableCorrectFileSize was not set
}
```

---

## 6. Liveview (Through-Picture) Channel

### 6.1 Mode Setup and Channel Open Sequence

Liveview requires a specific socket open sequence that differs from the normal receive session. The event and liveview channels are opened lazily, only after the command session is ready.

Confirmed sequence from XApp native analysis:

```text
1. Establish command session (port 55740), complete OpenSession + SetFunctionMode.
2. Send InitiateOpenCapture (opcode 0x101C, params = [0, 0]).
   Save the returned TransactionID.
3. Poll EventsList (0xD212) until camera acknowledges.
4. Connect event socket to port 55741.
   Open EventReceiver thread.
5. Connect liveview socket to port 55742.
   Open ThroughPictureReceiver thread.
6. Send TerminateOpenCapture (opcode 0x1018, param = saved TransactionID).
```

Both sockets retry every 1 second on connect failure and return `0xFFFFFFFF` on timeout. Connecting the sockets before sending `TerminateOpenCapture` causes the camera to stall — the order is strict.

Before starting the frame polling loop, set function mode to `IMAGE_LIVE_VIEW = 22`.

In the fuji-cam-wifi-tool model, liveview starts implicitly after completing the remote-mode init handshake (mode selector `0x24` in the two-part negotiation sequence). The explicit `InitiateOpenCapture` / `TerminateOpenCapture` framing from the XApp is the confirmed production path for modern firmware.

### 6.2 Frame Wire Format

Each liveview frame is delivered as a complete framed payload on the port-55742 socket.

**Fuji-cam-wifi-tool model (simpler cameras / older firmware):**

```text
[4-byte Fuji transport length prefix (LE, inclusive)]
[14-byte frame header]
  bytes 0–3:  u32 = 0 (constant)
  bytes 4–7:  u32 frame counter (increments per frame)
  bytes 8–13: zeros
[JPEG data starting with 0xFF 0xD8]
```

Skip the 14-byte header to reach raw JPEG. The Wireshark filter `data.data[18:2] == ff:d8` confirms JPEG at raw TCP offset 18 (4-byte Fuji prefix + 14-byte header).

**XApp native model (confirmed from Ghidra analysis of libFTLPTPIP.so):**

The receive buffer is 204 800 bytes (`REMOTE_RECEIVE_FILE_SIZE`). The JPEG data offset is not fixed; it is computed from a variable-length secondary header:

```text
receive_buffer[0..N]:
  byte 1 (not 0): outerLen field (read position)
  bytes 12–15:    secondaryLen (u32 LE)

JPEG starts at: secondaryLen + 18
```

The constant `18` corresponds to the PTP-IP DataEnd packet header overhead (12 bytes) plus the 6-byte Fuji liveview header prefix. The `secondaryLen` field is variable and must be read from each packet.

A double-slot ring buffer with 200 000 bytes per slot is used at the native layer. The producer writes into one slot while the consumer renders from the other, with a condvar for signalling. The Java polling loop allocates 204 800 bytes per frame and trims the result with `Arrays.copyOf`.

**Frame counter / drop detection:** The frame counter in bytes 4–7 of the Fuji-framed header increments by 1 per frame. Gaps in the counter indicate dropped frames. This can be checked without parsing JPEG content.

**JPEG validation:** Check that the first two bytes of the extracted frame data are `0xFF 0xD8` (JPEG SOI marker) before passing the frame to the Android decoder. Discard and log (without frame bytes) if the marker is absent.

### 6.3 Frame Cadence

Frame cadence (rate) is not specified by the protocol; it depends on camera model, firmware, subject content, and encoding settings. No frame rate is guaranteed. The liveview stream should be treated as a push stream with unknown inter-frame timing. Use a latest-frame-wins policy: if the Android renderer is behind, drop the older frame and render the newest.

---

## 7. Recommended Rust API Shape

Consistent with Frameport's coarse FFI boundary (CLAUDE.md, ADR-0001, ADR-0002).

### 7.1 fuji-transfer crate

```rust
/// Enumerate available media objects in the current session.
/// Returns a list of metadata structs; does not transfer bytes.
/// cancel-safe: yes — no owned resources allocated before return
pub async fn list_media(
    session: &FujiSession,
    cancel: CancellationToken,
) -> Result<Vec<MediaObjectInfo>, TransferError>;

/// Retrieve the thumbnail for a single object.
/// Returns raw JPEG bytes bounded to a safe maximum size.
/// cancel-safe: yes — drops channel read on cancel
pub async fn get_thumbnail(
    session: &FujiSession,
    object_id: ObjectHandle,
    cancel: CancellationToken,
) -> Result<ThumbnailBytes, TransferError>;

/// Stream a full camera object into an owned output file descriptor.
/// `output_fd` is owned by Rust after this call;
/// Rust closes it on completion, cancellation, and error.
/// The caller (Android JNI) must pass a dup'd fd.
/// cancel-safe: yes — AtomicBool checked between chunks; fd closed on cancel
pub async fn download_object_to_fd(
    session: &FujiSession,
    object_id: ObjectHandle,
    output_fd: OwnedFd,           // ownership transferred to Rust
    options: DownloadOptions,
    progress: impl Fn(TransferProgress) + Send + 'static,
    cancel: CancellationToken,
) -> Result<DownloadSummary, TransferError>;

#[derive(Debug)]
pub struct MediaObjectInfo {
    pub handle: ObjectHandle,
    pub image_id: Option<u32>,    // required for older firmware full_image path
    pub format: ObjectFormat,
    pub size_bytes: Option<u64>,  // None if EnableCorrectFileSize not supported
    pub filename_raw: Option<String>, // sanitize before MediaStore use
    pub capture_date_raw: Option<String>,
    pub thumb_size_bytes: u32,
    pub image_width: u32,
    pub image_height: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ObjectFormat {
    Jpeg,
    Raf,
    Mov,
    Heif,
    Unknown(u16),                 // preserve raw PTP code
}

#[derive(Debug, Clone, Copy)]
pub struct DownloadOptions {
    /// Maximum bytes per GetPartialObject request.
    /// Must not exceed 0x100000. Default: 0x100000.
    pub chunk_size: u32,
    /// Interval between keep-alive EventsList polls during transfer.
    /// Default: every 4 chunks.
    pub keepalive_interval_chunks: u32,
}

#[derive(Debug, Clone, Copy)]
pub struct TransferProgress {
    pub bytes_written: u64,
    pub total_bytes: Option<u64>,
}
```

### 7.2 fuji-liveview crate

```rust
/// Open the liveview channel and return a frame stream.
/// Caller must have already established the command session and
/// completed the InitiateOpenCapture / socket-connect /
/// TerminateOpenCapture sequence (session.open_liveview_channel).
/// cancel-safe: stream is dropped on cancel; sockets closed
pub fn liveview_frames(
    session: &FujiSession,
    cancel: CancellationToken,
) -> impl Stream<Item = Result<JpegFrame, LiveviewError>>;

#[derive(Debug)]
pub struct JpegFrame {
    /// Monotonic counter from frame header; gaps indicate drops.
    pub sequence: u64,
    /// Raw JPEG bytes starting with 0xFF 0xD8.
    /// Validated before delivery; malformed frames are Err, not Ok.
    pub jpeg_bytes: Bytes,        // zero-copy bytes handle, not Vec<u8>
}

#[derive(Debug, thiserror::Error)]
pub enum LiveviewError {
    #[error("liveview channel closed by camera")]
    ChannelClosed,
    #[error("frame header invalid: {reason}")]
    InvalidFrameHeader { reason: &'static str },
    #[error("frame exceeded maximum size: {size_bytes}")]
    FrameTooLarge { size_bytes: usize },
    #[error("JPEG SOI marker absent")]
    NotJpeg,
    #[error("transport error: {0}")]
    Transport(#[from] std::io::Error),
    #[error("cancelled")]
    Cancelled,
}
```

`Bytes` from the `bytes` crate allows the ring-buffer implementation to hand off a slice without copying; the consumer drops it when the frame is rendered, making the slot available for the next frame.

### 7.3 JNI boundary (fuji-ffi crate)

Coarse-grained entry points matching ADR-0002 guidance:

```text
fuji_open_wifi_session(command_fd, event_fd, liveview_fd) -> session_id
fuji_close_wifi_session(session_id)
fuji_list_media(session_id) -> [MediaObjectInfo]
fuji_get_thumbnail(session_id, object_handle) -> bytes
fuji_download_object_to_fd(session_id, object_handle, output_fd, options) -> transfer_id
fuji_cancel_transfer(transfer_id)
fuji_start_liveview(session_id) -> liveview_handle
fuji_stop_liveview(liveview_handle)
fuji_poll_liveview_frame(liveview_handle) -> Option<JpegFrame>
```

All entry points must be wrapped in `std::panic::catch_unwind`. Return the `ERR_PANIC` sentinel (`-100`) on unwind. Refer to `.claude/rules/jni-error-mapping.md` for the full error-mapping contract.

---

## 8. Error Model Mapping

Typed errors that transfer and liveview operations must produce, consistent with `docs/protocol/error-model.md`:

| Protocol condition | Frameport typed error |
|---|---|
| `GetObjectInfo` returns `0x2009` (InvalidObjectHandle) | `Transfer.ObjectNotFound` |
| Camera busy during transfer | `Protocol.CameraBusy` |
| TCP connection reset mid-transfer | `Transfer.CameraDisconnected` |
| No response within timeout | `Transfer.Timeout` |
| Write to output fd fails | `Transfer.OutputWriteFailed` |
| Received size ≠ expected size | `Transfer.SizeMismatch` |
| Object format not in known set | `Media.UnsupportedFormat` |
| Cancellation token set | `Transfer.Cancelled` |
| Liveview frame header invalid | `LiveView.InvalidFrameHeader` |
| No frame received within timeout | `LiveView.FrameTimeout` |
| Channel closed by camera | `LiveView.ChannelClosed` |

User-facing diagnostics must distinguish protocol failure from storage failure from user cancellation. Raw packet bytes and frame bytes must not appear in any diagnostic output.

---

## 9. Caveats

**Per-model and firmware differences.** The primary validation sources for this document are fuji-cam-wifi-tool (tested on X-T10, X-T100), libfuji (tested on X-T2, X-T4, X-T5, X-T20, X-S10, X-H1), filmkit (tested exclusively on X100VI), and XApp 2.7.5 native analysis (primary target unspecified; X-M5 referenced in string tables). The X-T5 is Frameport's v1 target. While libfuji confirms X-T5 compatibility, specific property codes, version negotiation values, and session mode behavior must be validated with a project-owned packet capture on the target X-T5 firmware before any compatibility claim is made.

**Liveview frame format uncertainty.** Two distinct frame extraction models are documented here (fuji-cam-wifi-tool's fixed 14-byte header vs. XApp's variable `secondaryLen + 18` formula). These may represent different firmware generations, different session modes, or different levels of analysis accuracy. The correct extraction formula must be verified with a project-owned packet capture from an active liveview session before implementing the production frame parser. Do not assume either model without verification.

**Older vs. newer firmware paths.** Two object-download paths exist: the older Fuji-custom-container `full_image` (`0x101B`) that requires both an index and an `image_id`, and the standard PTP `GetPartialObject` (`0x101B`) chunked path. The same opcode number is used; the difference is in session mode and container framing. Newer cameras and the XApp production path use the standard PTP chunking sequence. Older firmware (fuji-cam-wifi-tool era) uses the Fuji-framed path with the two-parameter payload. Frameport should implement the standard PTP chunking path first and treat the Fuji-framed path as a fallback if needed.

**IP risk for XApp-derived facts.** Facts derived from the XApp 2.7.5 native stack analysis (port numbers, JPEG offset formula, ring-buffer size, function-mode codes, socket-open sequence) are protocol-observable constants that will appear in any packet capture of a real camera session. They are interoperability facts, not creative expression. However, because the source of these facts is static analysis of a proprietary application, Frameport's legal review should confirm the applicability of interoperability provisions in all target jurisdictions before v1 release. No decompiled code, binary assets, or string resources from XApp appear in Frameport.

**XApp-derived facts that require packet-capture verification.** The `secondaryLen + 18` JPEG extraction formula and the ring-buffer sizes (200 000 and 204 800 bytes) were derived from Ghidra pseudo-C decompilation. Several fields in the analysis are marked as gaps (init packet GUID bytes, full opcode/event name tables). These should be verified against a live packet capture before being treated as implementation ground truth.

**EventsList polling is mandatory during transfers.** The camera closes the session if no activity occurs for a camera-defined idle period. The EventsList poll (property `0xD212`) serves as a keep-alive. Do not assume TCP keepalive is sufficient; explicit application-layer polling is required.

**Do not use `GetObjectHandles` for standard Wi-Fi enumeration.** The standard `GetObjectHandles` path works on USB PTP. For Wi-Fi PTP-IP sessions, Fujifilm cameras expose objects through sequential 1-based integer handles derived from the `ObjectCount` in `EventsList`. Issuing `GetObjectHandles` may return unexpected results or not be supported in some session modes.

**EnableCorrectFileSize must be managed per transfer.** Set to `1` before `GetObjectInfo`, reset to `0` after download. Do not leave it set persistently; on some cameras it slows thumbnail operations.

**No liveview in Frameport v1.** Per `docs/protocol/liveview.md` and `docs/product/feature-scope.md`, liveview is later scope. The technical facts in Section 6 are provided as implementation preparation for future development. Do not implement liveview channel code in v1.

**Knowledge cutoff.** This reference synthesizes sources analyzed through mid-2026. Fujifilm may change protocol behavior, introduce new version negotiation handshakes, or alter property codes in firmware releases after the XApp 2.7.5 analysis date. Validate against real hardware before each major Frameport release.

