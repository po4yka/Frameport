//! `fuji-usb-ptp` — PTP-over-USB bulk transport for Frameport.
//!
//! # Architecture
//!
//! This crate provides two layers:
//!
//! 1. [`BulkTransport`] — owns an Android-handed-off file descriptor for the USB
//!    bulk endpoint pair.  Performs raw byte-level `read(2)` / `write(2)` calls on
//!    the fd.  Closes the fd on `Drop` (or on any constructor error path).
//!
//! 2. [`UsbPtpSession`] — wraps a `BulkTransport` and speaks the PTP-over-USB
//!    wire format: command → optional data → response sequencing using the
//!    `fuji_ptp` container codec.  Implements [`fuji_transfer::CommandTransport`]
//!    so the M05 `fuji_transfer` free functions (`list_media`, `get_thumbnail`,
//!    `download_to_owned_fd`) drive USB sessions unchanged.
//!
//! # Production path
//!
//! Android:
//!   1. Calls `UsbManager.openDevice()` to get `UsbDeviceConnection`.
//!   2. Obtains `connection.fileDescriptor`.
//!   3. Calls `Os.dup()` to produce an owned fd.
//!   4. Passes `(dupFd, rawDescriptors)` to `FujiNativeSdk.openUsbSession()` via JNI.
//!
//! Rust (`fuji-ffi`):
//!   1. Dups the fd again (single dup at JNI boundary per docs/adr/0001-android-rust-boundary.md).
//!   2. Calls [`open_from_owned_fd`] which constructs a [`UsbPtpSession`] and
//!      registers it in the session registry.
//!
//! # Wire format — PTP-over-USB
//!
//! PTP-over-USB uses the same length-prefixed packet framing as PTP-IP
//! (length: u32 LE, type: u32 LE, payload …).  The `fuji_ptp` codec handles
//! encode/decode.  On USB, packets are written/read in one or more bulk transfers;
//! this crate assembles/fragments as needed.
//!
//! # Synchronicity
//!
//! This crate is fully synchronous (blocking POSIX I/O).  There are no async fns
//! and therefore no cancel-safety annotations are needed.  Cancellation is
//! provided via an `AtomicBool` cancel flag polled inside
//! [`UsbPtpSession::read_object_chunk`].
//!
//! # Safety
//!
//! One `unsafe` block exists in [`open_from_owned_fd`]:
//! `OwnedFd::from_raw_fd(fd)` — caller guarantees the fd is valid and owned.
//!
//! `BulkTransport` I/O is safe Rust: the owned fd is wrapped as a `File` via
//! `File::from(OwnedFd)` (safe std API) and read/written through `std::io`.
//!
//! # Privacy
//!
//! No raw USB descriptors, serial numbers, or hardware identifiers are logged
//! at `tracing::debug!` or above.  Category-level diagnostic events only.
//! Raw identifiers are confined to `tracing::trace!` gated behind the
//! `frameport-dev-logs` feature flag (not enabled in release builds).

use std::fs::File;
use std::io::{Read, Write};
use std::os::unix::io::{FromRawFd, OwnedFd};
use std::sync::atomic::AtomicBool;

use fuji_core::{FujiError, FujiResult, ProtocolError, TransferError, TransportKind, UsbError};
use fuji_ptp::constants::{SESSION_ID, opcode, response_code};
use fuji_ptp::{PtpIpPacket, decode_packet, encode_packet};
use fuji_transfer::CommandTransport;

// ── USB descriptor byte offsets and type codes ────────────────────────────────

/// Minimum byte length of a USB descriptor header (bLength + bDescriptorType).
const USB_DESC_HEADER_LEN: usize = 2;

/// USB descriptor type: Interface (0x04).
const USB_DESC_TYPE_INTERFACE: u8 = 0x04;

/// USB descriptor type: Endpoint (0x05).
const USB_DESC_TYPE_ENDPOINT: u8 = 0x05;

/// Minimum byte length of an Interface descriptor.
const USB_INTERFACE_DESC_LEN: usize = 9;

/// Minimum byte length of an Endpoint descriptor.
const USB_ENDPOINT_DESC_LEN: usize = 7;

/// USB endpoint direction mask: bit 7 set = IN (device-to-host).
const USB_ENDPOINT_DIR_IN: u8 = 0x80;

/// USB endpoint transfer type mask (bits 1:0).
const USB_ENDPOINT_TRANSFER_TYPE_MASK: u8 = 0x03;

/// USB endpoint transfer type: Bulk (0x02).
const USB_ENDPOINT_TRANSFER_TYPE_BULK: u8 = 0x02;

/// USB Interface class code for Still Image Capture (PTP/MTP): 0x06.
const USB_CLASS_STILL_IMAGE: u8 = 0x06;

// ── BulkTransport ─────────────────────────────────────────────────────────────

/// Owns an Android-provided file descriptor for USB bulk endpoint I/O.
///
/// # OWNERSHIP
///
/// `BulkTransport` takes **exclusive ownership** of `fd` at construction.
/// - The Android side must have called `Os.dup()` to produce a fresh fd before
///   passing it to Rust; Android retains and closes its original fd independently.
/// - Rust calls `close(fd)` exactly once via `Drop` (through `OwnedFd`).
/// - No other code path closes this fd — see `docs/rust/fd-ownership.md`.
///
/// # Thread safety
///
/// `BulkTransport` is `!Send + !Sync` because `RawFd` must not be shared across
/// threads.  Use it from a single thread (Android foreground-service worker).
pub struct BulkTransport {
    /// Wraps the owned fd as a `File` for safe std::io-based read/write.
    ///
    /// `File` takes ownership of the fd and calls `close(2)` on `Drop`.
    // OWNERSHIP: this File is the sole owner of the fd; Android has already
    // dup'd its original fd and handed this copy to Rust.  Rust closes it
    // via File's Drop.  No other path closes this fd.
    file: File,
}

impl BulkTransport {
    /// Constructs a `BulkTransport` from an already-owned file descriptor.
    ///
    /// `fd` ownership is transferred into this struct via `File::from(OwnedFd)`.
    /// The caller must not use, dup, or close `fd` after this call.
    fn from_owned_fd(fd: OwnedFd) -> Self {
        // File::from(OwnedFd) is the safe std API — no unsafe needed here.
        Self {
            file: File::from(fd),
        }
    }

    /// Writes all bytes in `buf` to the USB bulk-OUT endpoint.
    ///
    /// Uses `Write::write_all` — loops internally until all bytes are written
    /// or an OS error is returned.  Maps errors to [`UsbError::BulkWriteFailed`].
    pub fn bulk_write(&mut self, buf: &[u8]) -> FujiResult<()> {
        self.file
            .write_all(buf)
            .map_err(|_| FujiError::Usb(UsbError::BulkWriteFailed))
    }

    /// Reads up to `buf.len()` bytes from the USB bulk-IN endpoint.
    ///
    /// Returns the number of bytes actually read (may be less than `buf.len()`).
    /// Returns 0 on EOF/device detach; the caller should treat 0 as
    /// [`UsbError::DeviceDetached`].
    pub fn bulk_read(&mut self, buf: &mut [u8]) -> FujiResult<usize> {
        self.file
            .read(buf)
            .map_err(|_| FujiError::Usb(UsbError::BulkReadFailed))
    }

    /// Reads exactly `buf.len()` bytes from the bulk-IN endpoint.
    ///
    /// Loops until the buffer is full, mapping short reads and errors.
    ///
    /// # Errors
    ///
    /// - [`UsbError::DeviceDetached`] — endpoint returned 0 bytes (EOF).
    /// - [`UsbError::BulkReadFailed`] — OS read error.
    pub fn bulk_read_exact(&mut self, buf: &mut [u8]) -> FujiResult<()> {
        let mut total = 0usize;
        while total < buf.len() {
            let n = self.bulk_read(&mut buf[total..])?;
            if n == 0 {
                return Err(FujiError::Usb(UsbError::DeviceDetached));
            }
            total += n;
        }
        Ok(())
    }
}

// OWNERSHIP: OwnedFd's Drop impl calls close(2) exactly once.
// No explicit drop impl needed; the field drop handles cleanup.

// ── Descriptor parser — endpoint identification ───────────────────────────────

/// Identifies the bulk-IN and bulk-OUT endpoint addresses from raw USB
/// descriptor bytes supplied by Android.
///
/// Iterates the descriptor byte stream looking for an Interface descriptor
/// with `bInterfaceClass == 0x06` (Still Image / PTP) and then collecting
/// bulk endpoint addresses from the following Endpoint descriptors.
///
/// # Validation
///
/// Every `bLength` field is validated before indexing.  Descriptors that are
/// too short or whose `bLength` exceeds the remaining buffer are mapped to
/// [`UsbError::DescriptorInvalid`].
///
/// # Returns
///
/// `(bulk_in_addr, bulk_out_addr)` — the USB endpoint addresses (not indices).
fn parse_endpoints(descriptors: &[u8]) -> FujiResult<(u8, u8)> {
    let mut pos = 0usize;
    let mut in_ptp_interface = false;
    let mut bulk_in: Option<u8> = None;
    let mut bulk_out: Option<u8> = None;

    while pos < descriptors.len() {
        // Need at least bLength + bDescriptorType.
        if descriptors.len() - pos < USB_DESC_HEADER_LEN {
            return Err(FujiError::Usb(UsbError::DescriptorInvalid));
        }

        let b_length = descriptors[pos] as usize;
        let b_descriptor_type = descriptors[pos + 1];

        // bLength must be at least 2 and must not exceed remaining bytes.
        if b_length < USB_DESC_HEADER_LEN || pos + b_length > descriptors.len() {
            return Err(FujiError::Usb(UsbError::DescriptorInvalid));
        }

        let desc_bytes = &descriptors[pos..pos + b_length];

        match b_descriptor_type {
            USB_DESC_TYPE_INTERFACE => {
                // Interface descriptor: bLength >= 9.
                if b_length < USB_INTERFACE_DESC_LEN {
                    return Err(FujiError::Usb(UsbError::DescriptorInvalid));
                }
                // bInterfaceClass is at offset 5 within the descriptor.
                let b_interface_class = desc_bytes[5];
                in_ptp_interface = b_interface_class == USB_CLASS_STILL_IMAGE;
                // Reset endpoint search when entering a new interface.
                if in_ptp_interface {
                    bulk_in = None;
                    bulk_out = None;
                }
            }
            USB_DESC_TYPE_ENDPOINT if in_ptp_interface => {
                // Endpoint descriptor: bLength >= 7.
                if b_length < USB_ENDPOINT_DESC_LEN {
                    return Err(FujiError::Usb(UsbError::DescriptorInvalid));
                }
                // bEndpointAddress at offset 2, bmAttributes at offset 3.
                let b_endpoint_address = desc_bytes[2];
                let bm_attributes = desc_bytes[3];
                let transfer_type = bm_attributes & USB_ENDPOINT_TRANSFER_TYPE_MASK;

                if transfer_type == USB_ENDPOINT_TRANSFER_TYPE_BULK {
                    if b_endpoint_address & USB_ENDPOINT_DIR_IN != 0 {
                        bulk_in = Some(b_endpoint_address);
                    } else {
                        bulk_out = Some(b_endpoint_address);
                    }
                }
            }
            _ => {}
        }

        // If we have both endpoints for the PTP interface, we are done.
        if bulk_in.is_some() && bulk_out.is_some() {
            break;
        }

        pos += b_length;
    }

    match (bulk_in, bulk_out) {
        (Some(i), Some(o)) => Ok((i, o)),
        _ => Err(FujiError::Usb(UsbError::EndpointNotFound)),
    }
}

// ── Transaction counter ───────────────────────────────────────────────────────

/// Monotonically incrementing PTP transaction ID.
///
/// Mirrors `TxnCounter` in `fuji-ptpip`.  `0` is reserved for `OpenSession`
/// only (fixed by spec); wraps `0xFFFF_FFFE → 1` to avoid the sentinel
/// `0xFFFF_FFFF` (never transmitted).
#[derive(Debug)]
struct TxnCounter(u32);

impl TxnCounter {
    fn new() -> Self {
        Self(0)
    }

    fn next(&mut self) -> u32 {
        self.0 = if self.0 >= 0xFFFF_FFFE { 1 } else { self.0 + 1 };
        self.0
    }
}

// ── Object streaming state ────────────────────────────────────────────────────

/// Tracks position within a streaming `GetObject` data phase over USB.
///
/// Mirrors `ObjectStream` in `fuji-ptpip`.
#[derive(Debug)]
struct ObjectStream {
    /// Total payload bytes declared in the StartData packet.
    total: u64,
    /// Bytes still to read from the bulk-IN endpoint.
    remaining: u64,
    /// Transaction ID of the in-flight `GetObject` operation.
    txn_id: u32,
    /// Whether the EndData frame header (12 bytes) has been consumed.
    end_header_read: bool,
    /// Remaining payload bytes inside the current EndData bulk packet.
    ///
    /// USB may deliver the EndData payload in multiple bulk reads.  This
    /// tracks how many payload bytes of the current EndData frame remain.
    frame_payload_remaining: u64,
}

// ── UsbPtpSession ─────────────────────────────────────────────────────────────

// BulkTransport wraps File which is not Debug; provide a manual impl.
impl std::fmt::Debug for BulkTransport {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("BulkTransport").finish_non_exhaustive()
    }
}

/// Synchronous PTP-over-USB session.
// Manual Debug impl because BulkTransport does not derive Debug.
#[derive(Debug)]
pub struct UsbPtpSession {
    transport: BulkTransport,
    txn: TxnCounter,
    /// State for an in-progress streaming `GetObject` operation, if any.
    object_stream: Option<ObjectStream>,
    /// The USB transport kind — always `TransportKind::UsbPtp`.
    pub transport_kind: TransportKind,
    /// Bulk-IN endpoint address (informational; stored for diagnostics).
    #[allow(dead_code)]
    bulk_in_addr: u8,
    /// Bulk-OUT endpoint address (informational; stored for diagnostics).
    #[allow(dead_code)]
    bulk_out_addr: u8,
}

impl UsbPtpSession {
    // ── Packet I/O ────────────────────────────────────────────────────────────

    /// Encodes `pkt` and writes it to the bulk-OUT endpoint.
    fn write_packet(&mut self, pkt: &PtpIpPacket) -> FujiResult<()> {
        let bytes = encode_packet(pkt);
        self.transport.bulk_write(&bytes)
    }

    /// Reads one length-prefixed PTP packet from the bulk-IN endpoint.
    ///
    /// Reads the 4-byte length prefix first, then reads the rest of the
    /// packet into a Vec and calls `decode_packet`.  Safe for small packets
    /// (operation responses, StartData).  NOT for bulk object payloads —
    /// use `open_object` + `read_object_chunk` for streaming.
    fn read_packet(&mut self) -> FujiResult<PtpIpPacket> {
        let mut len_buf = [0u8; 4];
        self.transport.bulk_read_exact(&mut len_buf)?;

        let declared = u32::from_le_bytes(len_buf) as usize;
        if declared < 8 {
            return Err(FujiError::Protocol(ProtocolError::InvalidPacketLength {
                declared: declared as u32,
                minimum: 8,
            }));
        }

        let mut buf = vec![0u8; declared];
        buf[..4].copy_from_slice(&len_buf);
        self.transport.bulk_read_exact(&mut buf[4..])?;

        decode_packet(&buf).map_err(|_| FujiError::Protocol(ProtocolError::UnexpectedPacket))
    }

    /// Reads a complete small data phase: StartData → EndData → OperationResponse(OK).
    ///
    /// Buffers the EndData payload — only safe for bounded payloads
    /// (property values, ObjectInfo).
    fn read_data_phase_small(&mut self, _expected_txn: u32) -> FujiResult<Vec<u8>> {
        match self.read_packet()? {
            PtpIpPacket::StartData { .. } => {}
            _ => return Err(FujiError::Protocol(ProtocolError::UnexpectedPacket)),
        }

        let payload = match self.read_packet()? {
            PtpIpPacket::EndData { payload, .. } => payload,
            _ => return Err(FujiError::Protocol(ProtocolError::UnexpectedPacket)),
        };

        let resp = self.read_packet()?;
        self.expect_ok_response(resp, "data-phase")?;

        Ok(payload)
    }

    /// Validates that `pkt` is an `OperationResponse` with `response_code == OK`.
    fn expect_ok_response(&self, pkt: PtpIpPacket, _op: &str) -> FujiResult<()> {
        match pkt {
            PtpIpPacket::OperationResponse { response_code, .. }
                if response_code == response_code::OK =>
            {
                Ok(())
            }
            PtpIpPacket::OperationResponse { response_code, .. }
                if response_code == response_code::INVALID_OBJECT_HANDLE =>
            {
                Err(FujiError::Transfer(TransferError::ObjectNotFound))
            }
            PtpIpPacket::OperationResponse { response_code, .. }
                if response_code == response_code::SESSION_NOT_OPEN =>
            {
                Err(FujiError::Protocol(ProtocolError::SessionNotOpen))
            }
            PtpIpPacket::OperationResponse { .. } => {
                Err(FujiError::Protocol(ProtocolError::OperationRejected))
            }
            _ => Err(FujiError::Protocol(ProtocolError::UnexpectedPacket)),
        }
    }

    // ── Session management ────────────────────────────────────────────────────

    /// Sends `OpenSession` (opcode `0x1002`, params = `[SESSION_ID=1]`,
    /// `transaction_id = 0`).
    ///
    /// PTP spec reserves `transaction_id = 0` for `OpenSession`; subsequent
    /// operations use the auto-incrementing counter starting at 1.
    pub fn open_session(&mut self) -> FujiResult<()> {
        let req = PtpIpPacket::OperationRequest {
            data_phase: 1,
            opcode: opcode::OPEN_SESSION,
            transaction_id: 0, // fixed by spec for OpenSession
            params: vec![SESSION_ID],
        };
        self.write_packet(&req)?;

        let resp = self.read_packet()?;
        self.expect_ok_response(resp, "OpenSession")
    }
}

// ── impl CommandTransport for UsbPtpSession ───────────────────────────────────

/// Implements the `fuji_transfer::CommandTransport` trait for USB PTP sessions.
///
/// The orphan rule is satisfied: `CommandTransport` is defined in `fuji-transfer`
/// (foreign crate) and `UsbPtpSession` is local to this crate.
///
/// All five methods mirror the PTP-IP implementations in `fuji-ptpip`
/// (`PtpIpTcpClient`), substituting `bulk_write`/`bulk_read` for socket I/O.
/// The `fuji_transfer` free functions (`list_media`, `get_thumbnail`,
/// `download_to_owned_fd`) operate over `&mut dyn CommandTransport` and drive
/// USB transport unchanged — `fuji-transfer` is not modified.
impl CommandTransport for UsbPtpSession {
    /// Calls `GetDevicePropValue` (opcode `0x1015`) and returns raw payload bytes.
    fn get_device_prop_value(&mut self, prop_code: u16) -> FujiResult<Vec<u8>> {
        let txn = self.txn.next();
        let req = PtpIpPacket::OperationRequest {
            data_phase: 1,
            opcode: opcode::GET_DEVICE_PROP_VALUE,
            transaction_id: txn,
            params: vec![prop_code as u32],
        };
        self.write_packet(&req)?;
        self.read_data_phase_small(txn)
    }

    /// Calls `GetObjectInfo` (opcode `0x1008`) and returns raw ObjectInfo bytes.
    fn get_object_info(&mut self, handle: u32) -> FujiResult<Vec<u8>> {
        let txn = self.txn.next();
        let req = PtpIpPacket::OperationRequest {
            data_phase: 1,
            opcode: opcode::GET_OBJECT_INFO,
            transaction_id: txn,
            params: vec![handle],
        };
        self.write_packet(&req)?;
        self.read_data_phase_small(txn)
    }

    /// Calls `GetThumb` (opcode `0x100A`) and returns `(declared_len, bytes)`.
    ///
    /// Thumbnails are bounded; buffering is safe.  The cap is applied by the
    /// `fuji_transfer::get_thumbnail` wrapper.
    fn get_thumb(&mut self, handle: u32) -> FujiResult<(u64, Vec<u8>)> {
        let txn = self.txn.next();
        let req = PtpIpPacket::OperationRequest {
            data_phase: 1,
            opcode: opcode::GET_THUMB,
            transaction_id: txn,
            params: vec![handle],
        };
        self.write_packet(&req)?;

        let declared = match self.read_packet()? {
            PtpIpPacket::StartData {
                total_data_length, ..
            } => total_data_length,
            _ => return Err(FujiError::Protocol(ProtocolError::UnexpectedPacket)),
        };

        let payload = match self.read_packet()? {
            PtpIpPacket::EndData { payload, .. } => payload,
            _ => return Err(FujiError::Protocol(ProtocolError::UnexpectedPacket)),
        };

        let resp = self.read_packet()?;
        self.expect_ok_response(resp, "GetThumb")?;

        Ok((declared, payload))
    }

    /// Sends `GetObject` (opcode `0x1009`) and reads the `StartData` header.
    ///
    /// Returns the total object size from `StartData`.  Must be followed by
    /// repeated calls to [`read_object_chunk`](Self::read_object_chunk).
    fn open_object(&mut self, handle: u32) -> FujiResult<u64> {
        if self.object_stream.is_some() {
            return Err(FujiError::Protocol(ProtocolError::UnexpectedPacket));
        }

        let txn = self.txn.next();
        let req = PtpIpPacket::OperationRequest {
            data_phase: 1,
            opcode: opcode::GET_OBJECT,
            transaction_id: txn,
            params: vec![handle],
        };
        self.write_packet(&req)?;

        // Read StartData — small fixed packet (20 bytes), safe to buffer.
        let total = match self.read_packet()? {
            PtpIpPacket::StartData {
                total_data_length, ..
            } => total_data_length,
            _ => return Err(FujiError::Protocol(ProtocolError::UnexpectedPacket)),
        };

        self.object_stream = Some(ObjectStream {
            total,
            remaining: total,
            txn_id: txn,
            end_header_read: false,
            frame_payload_remaining: 0,
        });

        Ok(total)
    }

    /// Streams bytes of the object data phase into `buf`.
    ///
    /// Returns the number of bytes written into `buf`, or `0` when the payload
    /// is exhausted (after consuming the trailing `OperationResponse`).
    ///
    /// # Cancel support
    ///
    /// Cancellation via `AtomicBool` is the caller's responsibility: the
    /// `fuji_transfer::download_to_owned_fd` function checks `cancel` between
    /// chunks and returns `Err(Transfer(Cancelled))`.  This method itself does
    /// not poll a cancel flag — it matches the `PtpIpTcpClient` contract.
    fn read_object_chunk(&mut self, buf: &mut [u8]) -> FujiResult<usize> {
        let state = self
            .object_stream
            .as_mut()
            .ok_or(FujiError::Protocol(ProtocolError::UnexpectedPacket))?;

        // Read the EndData frame header the first time.
        // EndData frame: length(4) | type(4) | txn(4) = 12 bytes.
        if !state.end_header_read {
            let mut hdr = [0u8; 12];
            self.transport.bulk_read_exact(&mut hdr)?;

            let declared_frame_len = u32::from_le_bytes([hdr[0], hdr[1], hdr[2], hdr[3]]);
            let pkt_type = u32::from_le_bytes([hdr[4], hdr[5], hdr[6], hdr[7]]);

            // Type 0x000C = DataPacketEnd.
            if pkt_type != 0x000C {
                return Err(FujiError::Protocol(ProtocolError::UnexpectedPacket));
            }
            if declared_frame_len < 12 {
                return Err(FujiError::Protocol(ProtocolError::InvalidPacketLength {
                    declared: declared_frame_len,
                    minimum: 12,
                }));
            }

            let payload_len = (declared_frame_len - 12) as u64;

            if payload_len != state.total {
                return Err(FujiError::Protocol(ProtocolError::ResponseMismatch));
            }

            // Re-borrow after bulk_read_exact (borrows self.transport mutably).
            let state = self
                .object_stream
                .as_mut()
                .ok_or(FujiError::Protocol(ProtocolError::UnexpectedPacket))?;
            state.end_header_read = true;
            state.frame_payload_remaining = payload_len;
        }

        let state = self
            .object_stream
            .as_mut()
            .ok_or(FujiError::Protocol(ProtocolError::UnexpectedPacket))?;

        if state.remaining == 0 {
            // Payload exhausted — read trailing OperationResponse.
            let txn_id = state.txn_id;
            self.object_stream = None;
            let _ = txn_id;

            let resp = self.read_packet()?;
            self.expect_ok_response(resp, "GetObject")?;
            return Ok(0);
        }

        let to_read = buf.len().min(state.remaining as usize);
        let n = self.transport.bulk_read(&mut buf[..to_read])?;

        if n == 0 {
            return Err(FujiError::Usb(UsbError::DeviceDetached));
        }

        state.remaining -= n as u64;
        Ok(n)
    }
}

// ── Public constructor ────────────────────────────────────────────────────────

/// Opens a PTP-over-USB session from an Android-owned file descriptor.
///
/// # Ownership
///
/// `fd` must be an **owned** file descriptor — the Android side must have
/// called `Os.dup()` before passing it here.  Rust takes exclusive ownership
/// and closes the fd when the returned [`UsbPtpSession`] is dropped (or if
/// this function returns an error).
///
/// # OWNERSHIP comment (JNI call-site mirror)
///
/// Android keeps + closes the original `connection.fileDescriptor`.
/// Android calls `Os.dup(original)` → `dupFd`.  Rust takes `dupFd` here
/// and closes it on drop.  No other path closes `dupFd`.
///
/// # Arguments
///
/// - `fd` — owned bulk-endpoint file descriptor.
/// - `descriptors` — raw USB descriptor bytes from `UsbDeviceConnection.getRawDescriptors()`.
///   Used to identify bulk-IN / bulk-OUT endpoint addresses.
///
/// # Errors
///
/// - [`UsbError::InvalidFd`] — `fd` is negative.
/// - [`UsbError::DescriptorInvalid`] — descriptor bytes are malformed.
/// - [`UsbError::EndpointNotFound`] — no PTP bulk endpoint pair found.
pub fn open_from_owned_fd(fd: i32, descriptors: &[u8]) -> FujiResult<UsbPtpSession> {
    if fd < 0 {
        // fd < 0 — nothing to close; Android supplied an invalid fd.
        return Err(FujiError::Usb(UsbError::InvalidFd));
    }

    // OWNERSHIP: take ownership of `fd` IMMEDIATELY so that every later error
    // path (notably descriptor parse failure below) drops `owned` and closes
    // the fd — no leak. This makes open_from_owned_fd responsible for `fd` on
    // ALL paths; the JNI caller (nativeUsbSessionOpen) transfers ownership and
    // must NOT close `fd` afterward.
    // SAFETY: fd is non-negative (checked above) and was produced by Android via
    // Os.dup(), giving Rust exclusive ownership. OwnedFd::from_raw_fd requires the
    // caller to guarantee ownership — satisfied by the JNI contract documented in
    // NativeFujiJni.nativeUsbSessionOpen.
    let owned = unsafe { OwnedFd::from_raw_fd(fd) };

    // Parse descriptor bytes to find bulk-IN / bulk-OUT addresses. On error,
    // `owned` drops at the `?` and closes the fd (no leak).
    let (bulk_in_addr, bulk_out_addr) = parse_endpoints(descriptors)?;

    let transport = BulkTransport::from_owned_fd(owned);

    Ok(UsbPtpSession {
        transport,
        txn: TxnCounter::new(),
        object_stream: None,
        transport_kind: TransportKind::UsbPtp,
        bulk_in_addr,
        bulk_out_addr,
    })
}

// ── Compatibility with fuji-transfer's `download_to_owned_fd` cancel path ────

/// Drives a cancellable bulk download using the `fuji_transfer` free function.
///
/// This is a thin convenience wrapper that passes `cancel` through to
/// `fuji_transfer::download_to_owned_fd`.  The `TransferError::Cancelled` path
/// is owned by `fuji_transfer`; `fuji-usb-ptp` does not duplicate it.
///
/// The `UsbError::TransferCancelled` variant exists for USB-specific
/// cancellation observable at the transport layer (e.g. a USB reset while the
/// cancel flag fires); it is distinct from `TransferError::Cancelled` which is
/// the application-level cancel.
pub fn download_usb_object(
    session: &mut UsbPtpSession,
    object_handle: fuji_core::CameraObjectId,
    output_fd: std::os::unix::io::OwnedFd,
    cancel: &AtomicBool,
    progress_cb: &mut dyn FnMut(fuji_core::TransferProgress),
) -> FujiResult<fuji_core::TransferProgress> {
    fuji_transfer::download_to_owned_fd(session, object_handle, output_fd, cancel, progress_cb)
}

// ── Tests ─────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    // ── TxnCounter ────────────────────────────────────────────────────────────

    #[test]
    fn txn_counter_starts_at_one() {
        let mut c = TxnCounter::new();
        assert_eq!(c.next(), 1);
        assert_eq!(c.next(), 2);
        assert_eq!(c.next(), 3);
    }

    #[test]
    fn txn_counter_wraps_around_sentinel() {
        let mut c = TxnCounter(0xFFFF_FFFE);
        assert_eq!(c.next(), 1, "0xFFFFFFFE wraps to 1");
        assert_eq!(c.next(), 2);
    }

    // ── parse_endpoints ───────────────────────────────────────────────────────

    /// Builds a minimal synthetic descriptor with one Interface (class 0x06)
    /// followed by two Endpoint descriptors (bulk-IN 0x81, bulk-OUT 0x01).
    fn make_synthetic_descriptor(iface_class: u8, in_addr: u8, out_addr: u8) -> Vec<u8> {
        let mut d = Vec::new();

        // Interface descriptor (9 bytes).
        // bLength=9, bDescriptorType=0x04, bInterfaceNumber=0, bAlternateSetting=0,
        // bNumEndpoints=2, bInterfaceClass=<iface_class>, bInterfaceSubClass=1,
        // bInterfaceProtocol=1, iInterface=0
        d.extend_from_slice(&[9, 0x04, 0, 0, 2, iface_class, 1, 1, 0]);

        // Bulk-IN endpoint (7 bytes).
        // bLength=7, bDescriptorType=0x05, bEndpointAddress=<in_addr>,
        // bmAttributes=0x02 (bulk), wMaxPacketSize=512 LE, bInterval=0
        d.extend_from_slice(&[7, 0x05, in_addr, 0x02, 0x00, 0x02, 0]);

        // Bulk-OUT endpoint (7 bytes).
        d.extend_from_slice(&[7, 0x05, out_addr, 0x02, 0x00, 0x02, 0]);

        d
    }

    #[test]
    fn parse_endpoints_finds_bulk_in_and_out() {
        let desc = make_synthetic_descriptor(USB_CLASS_STILL_IMAGE, 0x81, 0x01);
        let (in_addr, out_addr) = parse_endpoints(&desc).unwrap();
        assert_eq!(in_addr, 0x81, "bulk-IN address");
        assert_eq!(out_addr, 0x01, "bulk-OUT address");
    }

    #[test]
    fn parse_endpoints_wrong_class_returns_endpoint_not_found() {
        // Class 0x03 (HID) — not PTP.
        let desc = make_synthetic_descriptor(0x03, 0x81, 0x01);
        let err = parse_endpoints(&desc).unwrap_err();
        assert_eq!(err, FujiError::Usb(UsbError::EndpointNotFound));
    }

    #[test]
    fn parse_endpoints_empty_returns_endpoint_not_found() {
        let err = parse_endpoints(&[]).unwrap_err();
        assert_eq!(err, FujiError::Usb(UsbError::EndpointNotFound));
    }

    #[test]
    fn parse_endpoints_truncated_header_returns_invalid() {
        // Only 1 byte — too short for any descriptor header.
        let err = parse_endpoints(&[9]).unwrap_err();
        assert_eq!(err, FujiError::Usb(UsbError::DescriptorInvalid));
    }

    #[test]
    fn parse_endpoints_blength_exceeds_buffer_returns_invalid() {
        // bLength=20 but only 9 bytes total.
        let mut d = make_synthetic_descriptor(USB_CLASS_STILL_IMAGE, 0x81, 0x01);
        d[0] = 20; // claim bLength = 20, but descriptor slice is 9 bytes
        let err = parse_endpoints(&d).unwrap_err();
        assert_eq!(err, FujiError::Usb(UsbError::DescriptorInvalid));
    }

    #[test]
    fn parse_endpoints_blength_zero_returns_invalid() {
        // bLength=0 is less than USB_DESC_HEADER_LEN=2.
        let desc = [0u8, 0x04];
        let err = parse_endpoints(&desc).unwrap_err();
        assert_eq!(err, FujiError::Usb(UsbError::DescriptorInvalid));
    }

    #[test]
    fn parse_endpoints_blength_one_returns_invalid() {
        // bLength=1 is less than USB_DESC_HEADER_LEN=2.
        let desc = [1u8, 0x04];
        let err = parse_endpoints(&desc).unwrap_err();
        assert_eq!(err, FujiError::Usb(UsbError::DescriptorInvalid));
    }

    #[test]
    fn parse_endpoints_endpoint_desc_too_short_returns_invalid() {
        let mut d = Vec::new();
        // Valid interface descriptor.
        d.extend_from_slice(&[9, 0x04, 0, 0, 1, USB_CLASS_STILL_IMAGE, 1, 1, 0]);
        // Endpoint descriptor claims bLength=3 (< USB_ENDPOINT_DESC_LEN=7).
        d.extend_from_slice(&[3, 0x05, 0x81]);
        let err = parse_endpoints(&d).unwrap_err();
        assert_eq!(err, FujiError::Usb(UsbError::DescriptorInvalid));
    }

    #[test]
    fn parse_endpoints_interrupt_endpoint_is_ignored() {
        let mut d = Vec::new();
        // Interface descriptor.
        d.extend_from_slice(&[9, 0x04, 0, 0, 3, USB_CLASS_STILL_IMAGE, 1, 1, 0]);
        // Interrupt-IN endpoint (bmAttributes=0x03, NOT bulk).
        d.extend_from_slice(&[7, 0x05, 0x82, 0x03, 0x00, 0x02, 0]);
        // Bulk-IN endpoint.
        d.extend_from_slice(&[7, 0x05, 0x81, 0x02, 0x00, 0x02, 0]);
        // Bulk-OUT endpoint.
        d.extend_from_slice(&[7, 0x05, 0x01, 0x02, 0x00, 0x02, 0]);
        let (in_addr, out_addr) = parse_endpoints(&d).unwrap();
        assert_eq!(in_addr, 0x81);
        assert_eq!(out_addr, 0x01);
    }

    // ── open_from_owned_fd validation ────────────────────────────────────────

    #[test]
    fn open_from_owned_fd_negative_fd_returns_invalid_fd() {
        let desc = make_synthetic_descriptor(USB_CLASS_STILL_IMAGE, 0x81, 0x01);
        let err = open_from_owned_fd(-1, &desc).unwrap_err();
        assert_eq!(err, FujiError::Usb(UsbError::InvalidFd));
    }

    // ── FakeUsbTransport — CommandTransport over in-memory buffers ────────────

    /// Minimal fake transport for testing the CommandTransport contract without
    /// a real USB fd.  All PTP packets are encoded/decoded through the fuji_ptp
    /// codec so the framing path is exercised.
    struct FakeUsbTransport {
        /// ObjectCount to return (u32 LE bytes from GetDevicePropValue).
        object_count: u32,
        /// Object metadata for each handle (1-indexed).
        objects: Vec<FakeObject>,
        /// Cancel flag — polled between chunks by download_to_owned_fd.
        #[allow(dead_code)]
        cancel: AtomicBool,
        /// Chunk index for multi-chunk streaming.
        chunk_idx: usize,
        /// Whether to emit the terminal 0 return from read_object_chunk.
        emit_terminal_zero: bool,
        /// Optional error to inject from read_object_chunk.
        chunk_error: Option<FujiError>,
    }

    struct FakeObject {
        format_code: u16,
        compressed_size: u32,
        filename: String,
        thumb: Vec<u8>,
        data: Vec<u8>,
    }

    impl FakeUsbTransport {
        fn new(objects: Vec<FakeObject>) -> Self {
            let object_count = objects.len() as u32;
            Self {
                object_count,
                objects,
                cancel: AtomicBool::new(false),
                chunk_idx: 0,
                emit_terminal_zero: true,
                chunk_error: None,
            }
        }
    }

    impl CommandTransport for FakeUsbTransport {
        fn get_device_prop_value(&mut self, prop_code: u16) -> FujiResult<Vec<u8>> {
            if prop_code == fuji_ptp::constants::prop_code::OBJECT_COUNT {
                Ok(self.object_count.to_le_bytes().to_vec())
            } else {
                Err(FujiError::Protocol(ProtocolError::UnsupportedOperation))
            }
        }

        fn get_object_info(&mut self, handle: u32) -> FujiResult<Vec<u8>> {
            let idx = (handle as usize).saturating_sub(1);
            let obj = self
                .objects
                .get(idx)
                .ok_or(FujiError::Transfer(TransferError::ObjectNotFound))?;
            let info = fuji_ptp::ObjectInfo {
                storage_id: 0x0001_0001,
                object_format: obj.format_code,
                protection_status: 0,
                object_compressed_size: obj.compressed_size,
                thumb_format: fuji_ptp::constants::object_format::JPEG,
                thumb_compressed_size: obj.thumb.len() as u32,
                thumb_pix_width: 160,
                thumb_pix_height: 120,
                image_pix_width: 0,
                image_pix_height: 0,
                image_bit_depth: 0,
                parent_object: 0,
                association_type: 0,
                association_desc: 0,
                sequence_number: handle,
                filename: obj.filename.clone(),
                capture_date: String::new(),
                modification_date: String::new(),
                keywords: String::new(),
            };
            Ok(fuji_ptp::encode_object_info(&info))
        }

        fn get_thumb(&mut self, handle: u32) -> FujiResult<(u64, Vec<u8>)> {
            let idx = (handle as usize).saturating_sub(1);
            let obj = self
                .objects
                .get(idx)
                .ok_or(FujiError::Transfer(TransferError::ObjectNotFound))?;
            Ok((obj.thumb.len() as u64, obj.thumb.clone()))
        }

        fn open_object(&mut self, handle: u32) -> FujiResult<u64> {
            let idx = (handle as usize).saturating_sub(1);
            let obj = self
                .objects
                .get(idx)
                .ok_or(FujiError::Transfer(TransferError::ObjectNotFound))?;
            Ok(obj.data.len() as u64)
        }

        fn read_object_chunk(&mut self, buf: &mut [u8]) -> FujiResult<usize> {
            if let Some(ref e) = self.chunk_error {
                return Err(e.clone());
            }
            if self.chunk_idx < self.objects.len() {
                let data = &self.objects[self.chunk_idx].data;
                let n = data.len().min(buf.len());
                buf[..n].copy_from_slice(&data[..n]);
                self.chunk_idx += 1;
                Ok(n)
            } else if self.emit_terminal_zero {
                self.emit_terminal_zero = false;
                Ok(0)
            } else {
                Ok(0)
            }
        }
    }

    // ── CommandTransport contract tests (via FakeUsbTransport) ───────────────

    #[test]
    fn fake_transport_enumerate_empty() {
        let mut t = FakeUsbTransport::new(vec![]);
        let objects = fuji_transfer::list_media(&mut t).unwrap();
        assert!(objects.is_empty());
    }

    #[test]
    fn fake_transport_enumerate_single_jpeg() {
        let mut t = FakeUsbTransport::new(vec![FakeObject {
            format_code: 0x3801, // JPEG
            compressed_size: 1024,
            filename: "DSCF0001.JPG".to_owned(),
            thumb: vec![0xFFu8, 0xD8, 0xFF, 0xE0],
            data: vec![0u8; 1024],
        }]);
        let objects = fuji_transfer::list_media(&mut t).unwrap();
        assert_eq!(objects.len(), 1);
        assert_eq!(objects[0].format, fuji_core::CameraMediaFormat::Jpeg);
        assert_eq!(objects[0].size_bytes, Some(1024));
    }

    #[test]
    fn fake_transport_get_thumbnail_succeeds() {
        let thumb = vec![0xFFu8, 0xD8, 0xFF, 0xE0];
        let mut t = FakeUsbTransport::new(vec![FakeObject {
            format_code: 0x3801,
            compressed_size: 1024,
            filename: "T.JPG".to_owned(),
            thumb: thumb.clone(),
            data: vec![0u8; 1024],
        }]);
        let result =
            fuji_transfer::get_thumbnail(&mut t, 1, fuji_transfer::DEFAULT_MAX_THUMBNAIL_BYTES)
                .unwrap();
        assert_eq!(result, thumb);
    }

    #[test]
    fn fake_transport_download_succeeds() {
        use std::fs::File;
        use std::os::unix::io::IntoRawFd;

        let data = vec![0xABu8; 256];
        let mut t = FakeUsbTransport::new(vec![FakeObject {
            format_code: 0x3801,
            compressed_size: data.len() as u32,
            filename: "D.JPG".to_owned(),
            thumb: vec![],
            data: data.clone(),
        }]);

        let dir = std::env::temp_dir();
        let path = dir.join(format!("fuji-usb-ptp-test-{}.bin", std::process::id()));
        let file = File::create(&path).unwrap();
        // SAFETY: File::into_raw_fd gives an owned fd that OwnedFd may take.
        let raw_fd = file.into_raw_fd();
        // SAFETY: We just created this fd above and have exclusive ownership.
        let owned_fd = unsafe { OwnedFd::from_raw_fd(raw_fd) };

        let cancel = AtomicBool::new(false);
        let progress = fuji_transfer::download_to_owned_fd(
            &mut t,
            fuji_core::CameraObjectId::new(1),
            owned_fd,
            &cancel,
            &mut |_| {},
        )
        .unwrap();

        assert_eq!(progress.bytes_transferred, 256);
        let written = std::fs::read(&path).unwrap();
        assert_eq!(written, data);
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn fake_transport_chunk_error_returns_camera_disconnected() {
        let mut t = FakeUsbTransport::new(vec![FakeObject {
            format_code: 0x3801,
            compressed_size: 64,
            filename: "E.JPG".to_owned(),
            thumb: vec![],
            data: vec![0u8; 64],
        }]);
        t.chunk_error = Some(FujiError::Transfer(TransferError::CameraDisconnected));

        use std::fs::File;
        use std::os::unix::io::IntoRawFd;
        let dir = std::env::temp_dir();
        let path = dir.join(format!("fuji-usb-ptp-err-{}.bin", std::process::id()));
        let file = File::create(&path).unwrap();
        let raw_fd = file.into_raw_fd();
        // SAFETY: exclusive ownership from File::into_raw_fd.
        let owned_fd = unsafe { OwnedFd::from_raw_fd(raw_fd) };

        let cancel = AtomicBool::new(false);
        let err = fuji_transfer::download_to_owned_fd(
            &mut t,
            fuji_core::CameraObjectId::new(1),
            owned_fd,
            &cancel,
            &mut |_| {},
        )
        .unwrap_err();
        assert!(
            matches!(err, FujiError::Transfer(TransferError::CameraDisconnected)),
            "unexpected: {err:?}"
        );
        let _ = std::fs::remove_file(&path);
    }

    // ── transport_kind ────────────────────────────────────────────────────────

    #[test]
    fn usb_transport_kind_is_usb_ptp() {
        assert_eq!(TransportKind::UsbPtp.to_string(), "usb-ptp");
    }
}
