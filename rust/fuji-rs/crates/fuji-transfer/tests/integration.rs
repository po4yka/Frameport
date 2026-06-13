//! Integration tests for `fuji-transfer` against `fuji-sim` over ephemeral TCP ports.
//!
//! Each test:
//!  1. Builds a `FakeCameraServer` config, binds it on `127.0.0.1:0` (OS-assigned ports).
//!  2. Runs the server in a background tokio task via `srv.run()`.
//!  3. Connects a `PtpIpTcpClient` (blocking `spawn_blocking`), performs the event-channel
//!     handshake on the async side, then calls `open_session`.
//!  4. Exercises one `fuji-transfer` public function against the live TCP connection.
//!  5. Tears down via `handle.shutdown()`.
//!
//! No fixed ports, no `thread::sleep`. All tests are hermetic and parallel-safe.
//!
//! # Note on SetFunctionMode
//!
//! Real-hardware sessions require `SetFunctionMode(IMAGE_RECEIVE=1)` and a 50 ms wait
//! before transfer operations. The sim accepts transfer operations after `OpenSession`
//! without this step — no sleeps in hermetic tests (deferred to the production Android path).
//!
//! # Note on GetObject opcode
//!
//! `download_to_owned_fd` uses `GetObject` (0x1009) — single data phase, streamed in
//! 64 KiB socket reads. For production against a real X-T5 over Wi-Fi, prefer
//! `GetPartialObject` (0x101B) per `transfer-liveview.md` section 5.2 (stall caveat).
//!
//! ## Note: download-cancelled test (scenario 4) — fd-closed assertion requires unix
//!
//! `libc::fcntl(F_GETFD)` is POSIX/unix-only. The `#[cfg(unix)]` guard on the EBADF assertion
//! is intentional and correct; the test still compiles on all targets.

#![allow(clippy::unwrap_used)]

use std::fs::File;
use std::net::SocketAddr;
use std::os::fd::OwnedFd;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use fuji_core::{CameraMediaFormat, CameraObjectId, FujiError, ProtocolError, TransferError};
use fuji_ptp::constants::object_format;
use fuji_ptpip::PtpIpTcpClient;
use fuji_sim::{FakeCameraBuilder, FakeCameraServer};
use fuji_transfer::{DEFAULT_MAX_THUMBNAIL_BYTES, download_to_owned_fd, get_thumbnail, list_media};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::time::timeout;

use fuji_ptp::{PtpIpPacket, decode_packet, encode_packet};

// ── Helper: connect + open session ───────────────────────────────────────────

/// Connects a `PtpIpTcpClient` to `cmd_addr`, completes the event-channel
/// handshake on `event_addr`, then calls `open_session`.
///
/// Protocol order (ptp-ptpip.md section 5.1):
///   1. `PtpIpTcpClient::connect(cmd_addr)` → sends InitCommandRequest → receives InitCommandAck.
///   2. After InitCommandAck the server accepts the event channel.
///   3. Client connects `event_addr` → sends InitEventRequest → receives InitEventAck.
///   4. Client sends `OpenSession` on the command channel.
///
/// # cancel-safe annotation
///
/// NOT cancel-safe: sequential .await points on TCP streams; never used under
/// `tokio::select!` — always driven to completion by the `#[tokio::test]` runtime.
async fn connect_and_open(cmd_addr: SocketAddr, event_addr: SocketAddr) -> PtpIpTcpClient {
    // Step 1: blocking connect on cmd channel (sends InitCommandRequest, receives InitCommandAck).
    let client = tokio::task::spawn_blocking(move || {
        PtpIpTcpClient::connect(cmd_addr).expect("PtpIpTcpClient::connect must succeed")
    })
    .await
    .expect("spawn_blocking must not panic");

    // Step 2: event-channel handshake — server accepts after sending InitCommandAck.
    let mut event_stream = TcpStream::connect(event_addr)
        .await
        .expect("event channel connect must succeed");
    let event_req = encode_packet(&PtpIpPacket::InitEventRequest {
        connection_number: 1,
    });
    event_stream.write_all(&event_req).await.unwrap();

    // Read the 4-byte length prefix of the response.
    let mut len_buf = [0u8; 4];
    event_stream.read_exact(&mut len_buf).await.unwrap();
    let len = u32::from_le_bytes(len_buf) as usize;
    let mut buf = vec![0u8; len];
    buf[..4].copy_from_slice(&len_buf);
    event_stream.read_exact(&mut buf[4..]).await.unwrap();
    let ack = decode_packet(&buf).expect("InitEventAck must decode");
    assert_eq!(
        ack,
        PtpIpPacket::InitEventAck,
        "event channel ack must be InitEventAck"
    );

    // Step 3: OpenSession on command channel.
    let mut client = client;
    client.open_session().expect("OpenSession must succeed");
    client
}

/// Creates a temporary file and returns `(OwnedFd, PathBuf)`.
///
/// The caller is responsible for deleting the file after the test.
fn make_temp_fd() -> (OwnedFd, std::path::PathBuf) {
    let path = std::env::temp_dir().join(format!(
        "frameport-integ-{}-{}.bin",
        std::process::id(),
        // Use a counter to avoid collision when multiple tests use make_temp_fd in the same process.
        {
            use std::sync::atomic::{AtomicU64, Ordering};
            static CTR: AtomicU64 = AtomicU64::new(0);
            CTR.fetch_add(1, Ordering::Relaxed)
        }
    ));
    let file = File::create(&path).expect("temp file create must succeed");
    let fd: OwnedFd = file.into();
    (fd, path)
}

// ── Scenario 1: enumeration-success ──────────────────────────────────────────

/// Scenario 1: list_media returns 3 objects with correct format codes, sizes, and filename hashes.
///
/// Sim has handles 1 (JPEG 0x3801), 2 (RAF 0xB103), 3 (MOV 0x300D).
/// Asserts:
///   - `Vec<CameraMediaObject>` has length 3.
///   - Formats are `Jpeg`, `Raf`, `Mov` respectively.
///   - `size_bytes` matches the `compressed_size` passed to `add_object`.
///   - `filename_opaque_hash` is `Some(_)` for objects that have a filename.
///
/// cancel-safe: test driver — driven to completion by `#[tokio::test]`; never under `tokio::select!`.
// multi_thread flavor: the test body calls blocking sync I/O (list_media etc.) on the
// calling thread; the second worker thread services the async server tasks concurrently.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn enumeration_success() {
    let jpeg_size: u32 = 4_194_304; // 4 MiB
    let raf_size: u32 = 52_428_800; // 50 MiB
    let mov_size: u32 = 104_857_600; // 100 MiB

    let config = FakeCameraBuilder::new()
        .add_object(
            object_format::JPEG,
            jpeg_size,
            "DSCF0001.JPG",
            "20241225T120000",
            vec![0xAAu8; 64], // minimal object bytes (not downloaded in this test)
            vec![0u8; 128],   // thumb bytes
        )
        .add_object(
            object_format::RAF,
            raf_size,
            "DSCF0001.RAF",
            "",
            vec![0xBBu8; 64],
            vec![],
        )
        .add_object(
            object_format::MOV,
            mov_size,
            "DSCF0001.MOV",
            "20241225T130000",
            vec![0xCCu8; 64],
            vec![],
        )
        .build();

    let srv = FakeCameraServer::bind(config).await.expect("bind");
    let cmd_addr = srv.bound_addr();
    let event_addr = srv.event_addr();
    let handle = srv.run();

    let result = timeout(Duration::from_secs(10), async {
        let mut client = connect_and_open(cmd_addr, event_addr).await;
        let objects = list_media(&mut client).expect("list_media must succeed");

        assert_eq!(objects.len(), 3, "expected 3 objects");

        // Handle 1 — JPEG
        assert_eq!(objects[0].object_handle, CameraObjectId::new(1));
        assert_eq!(objects[0].format, CameraMediaFormat::Jpeg);
        assert_eq!(objects[0].size_bytes, Some(jpeg_size as u64));
        assert!(
            objects[0].filename_opaque_hash.is_some(),
            "JPEG object must have a filename hash"
        );
        let hash0 = objects[0].filename_opaque_hash.as_deref().unwrap();
        assert_eq!(hash0.len(), 16, "filename hash must be 16 hex chars");
        assert!(
            hash0.chars().all(|c| c.is_ascii_hexdigit()),
            "filename hash must be lowercase hex"
        );

        // Handle 2 — RAF
        assert_eq!(objects[1].object_handle, CameraObjectId::new(2));
        assert_eq!(objects[1].format, CameraMediaFormat::Raf);
        assert_eq!(objects[1].size_bytes, Some(raf_size as u64));
        assert!(
            objects[1].filename_opaque_hash.is_some(),
            "RAF object must have a filename hash"
        );

        // Handle 3 — MOV
        assert_eq!(objects[2].object_handle, CameraObjectId::new(3));
        assert_eq!(objects[2].format, CameraMediaFormat::Mov);
        assert_eq!(objects[2].size_bytes, Some(mov_size as u64));
        assert!(
            objects[2].filename_opaque_hash.is_some(),
            "MOV object must have a filename hash"
        );

        // Filename hashes must be distinct for distinct filenames.
        assert_ne!(
            objects[0].filename_opaque_hash, objects[1].filename_opaque_hash,
            "JPEG and RAF filename hashes must differ"
        );
        assert_ne!(
            objects[1].filename_opaque_hash, objects[2].filename_opaque_hash,
            "RAF and MOV filename hashes must differ"
        );
    })
    .await;

    result.expect("enumeration_success timed out");
    handle.shutdown();
}

// ── Scenario 2: thumbnail-success ────────────────────────────────────────────

/// Scenario 2: get_thumbnail returns identical bytes for a 1 KiB JPEG blob.
///
/// The sim serves exactly `thumb_bytes` for the handle. The test asserts byte-for-byte
/// equality and that the returned `Vec<u8>` length is 1024.
///
/// cancel-safe: test driver — driven to completion by `#[tokio::test]`; never under `tokio::select!`.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn thumbnail_success() {
    // 1 KiB deterministic JPEG-magic-prefixed blob.
    let mut thumb = vec![0u8; 1024];
    thumb[0] = 0xFF;
    thumb[1] = 0xD8;
    thumb[2] = 0xFF;
    thumb[3] = 0xE0;

    let config = FakeCameraBuilder::new()
        .add_object(
            object_format::JPEG,
            4096, // compressed_size — not used in this test
            "DSCF0001.JPG",
            "",
            vec![0u8; 4096], // object bytes — not downloaded in this test
            thumb.clone(),
        )
        .build();

    let srv = FakeCameraServer::bind(config).await.expect("bind");
    let cmd_addr = srv.bound_addr();
    let event_addr = srv.event_addr();
    let handle = srv.run();

    let result = timeout(Duration::from_secs(10), async {
        let mut client = connect_and_open(cmd_addr, event_addr).await;
        let bytes = get_thumbnail(&mut client, 1, DEFAULT_MAX_THUMBNAIL_BYTES)
            .expect("get_thumbnail must succeed");
        assert_eq!(bytes.len(), 1024, "thumbnail must be 1024 bytes");
        assert_eq!(bytes, thumb, "thumbnail bytes must match exactly");
    })
    .await;

    result.expect("thumbnail_success timed out");
    handle.shutdown();
}

// ── Scenario 3: download-success ─────────────────────────────────────────────

/// Scenario 3: download_to_owned_fd streams 256 KiB of deterministic bytes correctly.
///
/// Asserts:
///   - `TransferProgress.bytes_transferred == 262144`
///   - `TransferProgress.total_bytes == 262144`
///   - File contents equal the original 256 KiB byte pattern exactly.
///   - At least one progress callback was received.
///
/// cancel-safe: test driver — driven to completion by `#[tokio::test]`; never under `tokio::select!`.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn download_success() {
    const SIZE: usize = 262_144; // 256 KiB
    // Deterministic pattern: byte at position i = (i % 251) as u8 (251 is prime, fills all 256 values).
    let data: Vec<u8> = (0..SIZE).map(|i| (i % 251) as u8).collect();

    let config = FakeCameraBuilder::new()
        .add_object(
            object_format::JPEG,
            SIZE as u32,
            "DSCF0001.JPG",
            "",
            data.clone(),
            vec![],
        )
        .build();

    let srv = FakeCameraServer::bind(config).await.expect("bind");
    let cmd_addr = srv.bound_addr();
    let event_addr = srv.event_addr();
    let handle = srv.run();

    let result = timeout(Duration::from_secs(15), async {
        let mut client = connect_and_open(cmd_addr, event_addr).await;
        let (fd, path) = make_temp_fd();
        let cancel = AtomicBool::new(false);
        let mut progress_snapshots = vec![];

        let final_progress =
            download_to_owned_fd(&mut client, CameraObjectId::new(1), fd, &cancel, &mut |p| {
                progress_snapshots.push(p)
            })
            .expect("download_to_owned_fd must succeed");

        assert_eq!(
            final_progress.bytes_transferred, SIZE as u64,
            "bytes_transferred must equal SIZE"
        );
        assert_eq!(
            final_progress.total_bytes, SIZE as u64,
            "total_bytes must equal SIZE"
        );
        assert_eq!(
            final_progress.object_handle,
            CameraObjectId::new(1),
            "object_handle must be 1"
        );
        assert_eq!(
            final_progress.transfer_id, 1u64,
            "transfer_id must equal object_handle.get() as u64"
        );
        assert!(
            !progress_snapshots.is_empty(),
            "must have received at least one progress callback"
        );

        // Verify written content byte-for-byte.
        let written = std::fs::read(&path).expect("must read temp file");
        assert_eq!(
            written, data,
            "downloaded bytes must equal original data exactly"
        );
        let _ = std::fs::remove_file(&path);
    })
    .await;

    result.expect("download_success timed out");
    handle.shutdown();
}

// ── Scenario 4: download-cancelled ───────────────────────────────────────────

/// Scenario 4: cancellation mid-transfer returns `TransferError::Cancelled` and closes the fd.
///
/// The sim serves 1 MiB. The cancel flag is flipped `true` inside the 3rd progress callback.
/// After the call returns:
///   - Error is exactly `FujiError::Transfer(TransferError::Cancelled)`.
///   - The raw fd (captured via `AsRawFd` BEFORE moving `OwnedFd`) returns `EBADF` from
///     `libc::fcntl(F_GETFD)` — proving the fd was closed by `download_to_owned_fd`.
///
/// cancel-safe: test driver — driven to completion by `#[tokio::test]`; never under `tokio::select!`.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn download_cancelled() {
    use std::os::unix::io::AsRawFd;

    const SIZE: usize = 1_048_576; // 1 MiB — large enough that we get multiple progress callbacks.
    let data: Vec<u8> = (0..SIZE).map(|i| (i % 251) as u8).collect();

    let config = FakeCameraBuilder::new()
        .add_object(
            object_format::JPEG,
            SIZE as u32,
            "DSCF0001.JPG",
            "",
            data,
            vec![],
        )
        .build();

    let srv = FakeCameraServer::bind(config).await.expect("bind");
    let cmd_addr = srv.bound_addr();
    let event_addr = srv.event_addr();
    let handle = srv.run();

    let result = timeout(Duration::from_secs(15), async {
        let mut client = connect_and_open(cmd_addr, event_addr).await;
        let (fd, path) = make_temp_fd();

        // Capture raw fd BEFORE moving OwnedFd into download_to_owned_fd.
        // After the call the OwnedFd is dropped (fd closed) on every exit path.
        let raw_fd = fd.as_raw_fd();

        let cancel = AtomicBool::new(false);
        let cancel_ref = &cancel;
        let mut callback_count = 0u32;

        let err = download_to_owned_fd(
            &mut client,
            CameraObjectId::new(1),
            fd,
            cancel_ref,
            &mut |_p| {
                callback_count += 1;
                // Flip cancel on the 3rd progress callback.
                if callback_count >= 3 {
                    cancel_ref.store(true, Ordering::Relaxed);
                }
            },
        )
        .expect_err("must return Err after cancellation");

        assert_eq!(
            err,
            FujiError::Transfer(TransferError::Cancelled),
            "error must be exactly TransferError::Cancelled"
        );

        // Verify the fd was closed: fcntl(F_GETFD) on a closed fd returns -1 with EBADF.
        // This is POSIX-only; cfg(unix) is always true on Android/Linux/macOS dev environments.
        #[cfg(unix)]
        {
            // SAFETY: We are calling fcntl with F_GETFD on the captured raw_fd integer.
            // The OwnedFd that originally backed this fd was moved into download_to_owned_fd
            // and is guaranteed to be dropped (fd closed) on every exit path per the function's
            // doc comment. We do not touch or dereference any pointer; we only pass an integer.
            // This does not constitute ownership of the fd — we are probing whether it is open.
            let ret = unsafe { libc::fcntl(raw_fd, libc::F_GETFD) };
            // Save errno immediately.
            let errno_val = std::io::Error::last_os_error().raw_os_error().unwrap_or(0);
            assert_eq!(ret, -1, "fcntl(F_GETFD) on closed fd must return -1");
            assert_eq!(
                errno_val,
                libc::EBADF,
                "errno after fcntl on closed fd must be EBADF"
            );
        }

        let _ = std::fs::remove_file(&path);
    })
    .await;

    result.expect("download_cancelled timed out");
    handle.shutdown();
}

// ── Scenario 5: size-mismatch ────────────────────────────────────────────────

/// Scenario 5: sim advertises `compressed_size=102400` in ObjectInfo but streams 51200 bytes.
///
/// The sim's `StreamObject` path sets `total_data_length = object_bytes.len()` (51200) in
/// StartData — consistent on-wire framing.  The mismatch is expressed exclusively through
/// ObjectInfo: GetObjectInfo returns `object_compressed_size=102400` while only 51200 bytes
/// are actually streamed.  After the transfer loop, `download_to_owned_fd` compares
/// `bytes_written` (51200) against `expected` from ObjectInfo (102400) and returns
/// `TransferError::SizeMismatch { expected: 102400, actual: 51200 }`.
///
/// cancel-safe: test driver — driven to completion by `#[tokio::test]`; never under `tokio::select!`.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn size_mismatch() {
    const ADVERTISED: u32 = 102_400; // What ObjectInfo says (compressed_size)
    const ACTUAL: usize = 51_200; // What the sim actually streams (object_bytes.len())

    let config = FakeCameraBuilder::new()
        .add_object(
            object_format::JPEG,
            ADVERTISED, // compressed_size — advertised in ObjectInfo
            "DSCF0001.JPG",
            "",
            vec![0xABu8; ACTUAL], // actual bytes — fewer than advertised
            vec![],
        )
        .with_size_mismatch_fault(1) // records handle 1 in FaultConfig
        .build();

    let srv = FakeCameraServer::bind(config).await.expect("bind");
    let cmd_addr = srv.bound_addr();
    let event_addr = srv.event_addr();
    let handle = srv.run();

    let result = timeout(Duration::from_secs(10), async {
        let mut client = connect_and_open(cmd_addr, event_addr).await;
        let (fd, path) = make_temp_fd();
        let cancel = AtomicBool::new(false);

        let err = download_to_owned_fd(
            &mut client,
            CameraObjectId::new(1),
            fd,
            &cancel,
            &mut |_| {},
        )
        .expect_err("must return Err on size mismatch");

        assert_eq!(
            err,
            FujiError::Transfer(TransferError::SizeMismatch {
                expected: ADVERTISED as u64,
                actual: ACTUAL as u64,
            }),
            "size mismatch must produce TransferError::SizeMismatch{{expected:102400, actual:51200}}"
        );

        let _ = std::fs::remove_file(&path);
    })
    .await;

    result.expect("size_mismatch timed out");
    handle.shutdown();
}

// ── Scenario 6: invalid-packet-length ────────────────────────────────────────

/// Scenario 6: sim sends a malformed (4-byte) GetObjectInfo data phase; client returns
/// `ProtocolError::InvalidPacketLength`.
///
/// The `with_malformed_object_info_fault(1)` builder method configures the sim to send
/// a 4-byte EndData payload for handle 1 in response to GetObjectInfo. `decode_object_info`
/// returns `PtpCodecError::PacketTooShort`, which `fuji-transfer`'s `list_media` maps to
/// `FujiError::Protocol(ProtocolError::InvalidPacketLength { declared: 4, minimum: 52 })`.
///
/// cancel-safe: test driver — driven to completion by `#[tokio::test]`; never under `tokio::select!`.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn invalid_packet_length() {
    let config = FakeCameraBuilder::new()
        .add_object(
            object_format::JPEG,
            1024,
            "DSCF0001.JPG",
            "",
            vec![0u8; 1024],
            vec![],
        )
        .with_malformed_object_info_fault(1)
        .build();

    let srv = FakeCameraServer::bind(config).await.expect("bind");
    let cmd_addr = srv.bound_addr();
    let event_addr = srv.event_addr();
    let handle = srv.run();

    let result = timeout(Duration::from_secs(10), async {
        let mut client = connect_and_open(cmd_addr, event_addr).await;
        let err = list_media(&mut client).expect_err("must return Err on malformed ObjectInfo");

        // The sim sends 4 bytes; decode_object_info needs ≥ 52 bytes → PacketTooShort.
        // fuji-transfer maps this to InvalidPacketLength { declared: 4, minimum: 52 }.
        assert!(
            matches!(
                err,
                FujiError::Protocol(ProtocolError::InvalidPacketLength {
                    declared: 4,
                    minimum: 52,
                })
            ),
            "expected InvalidPacketLength {{ declared: 4, minimum: 52 }}, got {err:?}"
        );
    })
    .await;

    result.expect("invalid_packet_length timed out");
    handle.shutdown();
}

// ── Scenario 7: connection-reset ─────────────────────────────────────────────

/// Scenario 7: sim closes the TCP connection mid GetObject stream; client returns
/// `TransferError::CameraDisconnected` — not a panic.
///
/// `with_connection_reset_fault(1, 4096)` tells the sim to drop the TCP connection
/// after writing exactly 4096 bytes of the EndData body. The client's `read_object_chunk`
/// receives an EOF/reset while reading the remaining payload bytes → `CameraDisconnected`.
///
/// cancel-safe: test driver — driven to completion by `#[tokio::test]`; never under `tokio::select!`.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn connection_reset() {
    const TOTAL_SIZE: usize = 65536; // 64 KiB object
    const RESET_AFTER: usize = 4096; // sim drops connection after 4 KiB of body bytes

    let data: Vec<u8> = (0..TOTAL_SIZE).map(|i| (i % 251) as u8).collect();

    let config = FakeCameraBuilder::new()
        .add_object(
            object_format::JPEG,
            TOTAL_SIZE as u32,
            "DSCF0001.JPG",
            "",
            data,
            vec![],
        )
        .with_connection_reset_fault(1, RESET_AFTER)
        .build();

    let srv = FakeCameraServer::bind(config).await.expect("bind");
    let cmd_addr = srv.bound_addr();
    let event_addr = srv.event_addr();
    let handle = srv.run();

    let result = timeout(Duration::from_secs(10), async {
        let mut client = connect_and_open(cmd_addr, event_addr).await;
        let (fd, path) = make_temp_fd();
        let cancel = AtomicBool::new(false);

        let err = download_to_owned_fd(
            &mut client,
            CameraObjectId::new(1),
            fd,
            &cancel,
            &mut |_| {},
        )
        .expect_err("must return Err on connection reset");

        assert_eq!(
            err,
            FujiError::Transfer(TransferError::CameraDisconnected),
            "mid-transfer TCP reset must produce CameraDisconnected, not a panic"
        );

        let _ = std::fs::remove_file(&path);
    })
    .await;

    result.expect("connection_reset timed out");
    handle.shutdown();
}
