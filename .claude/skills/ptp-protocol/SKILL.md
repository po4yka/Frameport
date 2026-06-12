---
name: ptp-protocol
description: Use when authoring or reviewing code in fuji-ptp, fuji-ptpip, or fuji-transfer crates; encoding/decoding PTP USB Bulk Container or PTP-IP packet frames; implementing the two-socket PTP-IP handshake (INIT_COMMAND_REQUEST / INIT_COMMAND_ACK / INIT_EVENT_REQUEST / INIT_EVENT_ACK); mapping PTP operation codes (GetDeviceInfo 0x1001, OpenSession 0x1002, GetObjectHandles 0x1007, GetObjectInfo 0x1008, GetObject 0x1009, GetThumb 0x100A, GetPartialObject 0x101B) to the Frameport transaction flow; assembling multi-packet data phases (START_DATA / DATA / END_DATA); handling PTP response codes (PTP_RC_*); or mapping wire errors to fuji-diagnostics typed error states.
---

# PTP Protocol Skill

## When to use

Invoke this skill when:
- Authoring or reviewing code in `fuji-ptp`, `fuji-ptpip`, or `fuji-transfer` crates.
- Encoding or decoding PTP USB Bulk Container frames or PTP-IP framed packets.
- Implementing the two-socket PTP-IP handshake or data-phase assembly.
- Mapping PTP operation codes to the Frameport transaction flow.
- Mapping PTP response codes to `fuji-diagnostics` typed error states.
- Deciding which PTP operations require an open session vs. are pre-session.
- Working with Fujifilm vendor extension identification (VendorExtensionID) — vendor-specific opcodes need separate handling; see the legal/verification note below.

---

## Crate ownership map

| Concern | Crate |
|---|---|
| PTP wire types: operation codes, response codes, format codes, ObjectInfo/StorageInfo/DeviceInfo structs, transaction-ID counter | `fuji-ptp` |
| PTP-IP framing: 8-byte packet header, all 14 packet types, two-socket handshake, data-phase assembly, ping/pong | `fuji-ptpip` |
| Chunked GetObject / GetThumb / GetPartialObject flows, progress reporting, cancellation | `fuji-transfer` |
| Typed protocol error states, diagnostics telemetry | `fuji-diagnostics` |
| Network socket creation + `openBoundSocket` (binds to camera `Network` internally) before fd handoff | Android `camera/wifi` module |

The Android layer creates and binds the TCP socket to the camera `Network` object and hands the connected fd to Rust. Rust must never attempt to open its own socket or route through the Android default network.

---

## Wire format reference

### PTP USB Bulk Container (fuji-ptp)

12-byte header, all fields little-endian:

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                            Length (u32)                        |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|     ContainerType (u16)       |         Code (u16)             |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                         TransactionID (u32)                    |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|               Payload: up to 5×u32 params or data bytes        |
```

- `ContainerType`: 1 = Command, 2 = Data, 3 = Response, 4 = Event.
- `Code`: operation code for command containers, response code for response containers.
- `Length` includes the 12-byte header.
- All wire values are little-endian (PTP mandates little-endian byte order throughout).

### PTP-IP Packet Header (fuji-ptpip)

8-byte mandatory prefix on every PTP-IP frame (CIPA DC-X005):

```
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                            Length (u32 LE)                     |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                         PacketType (u32 LE)                    |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

`Length` includes the 8-byte header. Contrast with the USB Bulk Container's 12-byte header — these are different structures used on different transports.

---

## PTP-IP packet types

All 14 packet types defined in CIPA DC-X005 / Wireshark `packet-ptpip.c`:

| Value | Name | Key payload fields (after 8-byte header) |
|---|---|---|
| 1 | INIT_COMMAND_REQUEST | GUID[16], FriendlyName (UTF-16LE, variable), ProtocolVersion:u32 LE |
| 2 | INIT_COMMAND_ACK | ConnectionNumber:u32, GUID[16], FriendlyName (UTF-16LE), ProtocolVersion:u32 |
| 3 | INIT_EVENT_REQUEST | ConnectionNumber:u32 (from ACK) |
| 4 | INIT_EVENT_ACK | no payload |
| 5 | INIT_FAIL | Reason code |
| 6 | CMD_REQUEST | DataPhaseInfo:u32, OperationCode:u16, TransactionID:u32, up to 5×u32 params |
| 7 | CMD_RESPONSE | ResponseCode:u16, TransactionID:u32, up to 5×u32 params |
| 8 | EVENT | EventCode:u16, TransactionID:u32, up to 3×u32 params |
| 9 | START_DATA_PACKET | TransactionID:u32, TotalDataLength:u64 |
| 10 | DATA_PACKET | TransactionID:u32, DataPayload bytes |
| 11 | CANCEL_TRANSACTION | TransactionID:u32 |
| 12 | END_DATA_PACKET | TransactionID:u32, DataPayload (final chunk) |
| 13 | PING | no payload |
| 14 | PONG | no payload |

`DataPhaseInfo` in CMD_REQUEST:
- `0x00000001` — no data phase
- `0x00000002` — data flows from initiator to responder (host → camera)
- `0x00000003` — data flows from responder to initiator (camera → host)

---

## PTP-IP two-socket handshake

Two independent TCP connections are required. The command channel is established first, then the event channel.

```
Command socket (port varies; see note below):
  Initiator → Camera : INIT_COMMAND_REQUEST  (type=1, GUID, FriendlyName, ProtocolVersion)
  Camera → Initiator : INIT_COMMAND_ACK      (type=2, ConnectionNumber, camera GUID, ...)
                    OR INIT_FAIL             (type=5, Reason)

Event socket (separate port; see note below):
  Initiator → Camera : INIT_EVENT_REQUEST    (type=3, ConnectionNumber from ACK above)
  Camera → Initiator : INIT_EVENT_ACK        (type=4, no payload)
```

The `ConnectionNumber` from INIT_COMMAND_ACK (type 2) must be used verbatim in INIT_EVENT_REQUEST (type 3). It is distinct from the PTP `SessionID`.

Port note: the PTP-IP standard (CIPA DC-X005) defines TCP port 15740. Fujifilm cameras have been observed in open-source traces using port 55740 (command) and 55742 (event). Port constants in `fuji-ptpip` must be configurable — do not hardcode 15740. Verify actual ports against a real device before finalising defaults. (Source: libgphoto2 issue #63.)

---

## PTP operation codes and session rules

Standard PTP operations implemented in `fuji-ptp` (all values little-endian on wire):

| Code | Name | Params | Session required? |
|---|---|---|---|
| 0x1001 | GetDeviceInfo | none | No |
| 0x1002 | OpenSession | param1 = SessionID (must be ≥ 1) | No (it opens the session) |
| 0x1003 | CloseSession | none | Yes |
| 0x1004 | GetStorageIDs | none | Yes |
| 0x1005 | GetStorageInfo | param1 = StorageID | Yes |
| 0x1007 | GetObjectHandles | param1 = StorageID, param2 = FormatCode, param3 = AssociationHandle | Yes |
| 0x1008 | GetObjectInfo | param1 = ObjectHandle | Yes |
| 0x1009 | GetObject | param1 = ObjectHandle | Yes |
| 0x100A | GetThumb | param1 = ObjectHandle | Yes |
| 0x100C | SendObjectInfo | — | Yes |
| 0x100D | SendObject | — | Yes |
| 0x101B | GetPartialObject | param1 = ObjectHandle, param2 = Offset:u32, param3 = MaxSize:u32 | Yes |

`GetDeviceInfo` (0x1001) and `OpenSession` (0x1002) are the only operations permitted before a session is open. All others require an open session; the camera will return `PTP_RC_SessionNotOpen (0x2003)` otherwise.

`GetObjectHandles` wildcard values:
- StorageID `0xFFFFFFFF` = all storages
- FormatCode `0x0000` = all formats
- AssociationHandle `0x00000000` = root / all

---

## PTP response codes

Defined in `fuji-ptp`; map to `fuji-diagnostics` typed error states per the table below:

| Code | Name | fuji-diagnostics mapping |
|---|---|---|
| 0x2001 | PTP_RC_OK | success |
| 0x2002 | PTP_RC_GeneralError | `Protocol::UnexpectedResponse` |
| 0x2003 | PTP_RC_SessionNotOpen | `Protocol::OpenSessionFailed` |
| 0x2004 | PTP_RC_InvalidTransactionID | `Protocol::UnexpectedResponse` |
| 0x2005 | PTP_RC_OperationNotSupported | `Protocol::UnsupportedOperation` |
| 0x2008 | PTP_RC_InvalidStorageId | `Transfer::StorageNotFound` |
| 0x2009 | PTP_RC_InvalidObjectHandle | `Transfer::ObjectNotFound` |
| 0x200C | PTP_RC_StoreFull | `Transfer::StoreFull` |
| 0x200F | PTP_RC_AccessDenied | `Protocol::AccessDenied` |
| 0x2017 | PTP_RC_UnknownVendorCode | `Protocol::UnsupportedOperation` |
| 0x2019 | PTP_RC_DeviceBusy | `Protocol::CameraBusy` |
| INIT_FAIL packet | — | `Protocol::HandshakeRejected` |

---

## PTP event codes

Events arrive on the event socket; `fuji-ptpip` reads them asynchronously:

| Code | Name |
|---|---|
| 0x4002 | PTP_EC_ObjectAdded |
| 0x4003 | PTP_EC_ObjectRemoved |
| 0x4004 | PTP_EC_StoreAdded |
| 0x4006 | PTP_EC_DevicePropChanged |
| 0x400D | PTP_EC_CaptureComplete |

---

## Data-phase assembly (fuji-ptpip / fuji-transfer)

GetObject, GetThumb, and GetPartialObject responses involve a multi-packet sequence:

```
Camera → Initiator : START_DATA_PACKET (type=9)
                     - TransactionID: u32
                     - TotalDataLength: u64 (or 0xFFFFFFFFFFFFFFFF if unknown)
Camera → Initiator : DATA_PACKET (type=10) × N   [0 or more intermediate chunks]
Camera → Initiator : END_DATA_PACKET (type=12)    [final chunk, may be empty]
```

`fuji-ptpip` assembles these into a contiguous byte buffer or streaming source. `fuji-transfer` owns the flow: it drives the assembly, reports progress, and checks the total size against `ObjectCompressedSize` from ObjectInfo.

When `TotalDataLength` is `0xFFFFFFFFFFFFFFFF` (unknown), the assembler must accumulate chunks until END_DATA_PACKET without pre-allocating to the announced size. Do not assume one packet equals one complete object.

---

## ObjectInfo dataset fields (fuji-ptp)

ObjectInfo is the dataset returned by GetObjectInfo (0x1008). Fields in spec order (ISO 15740):

1. StorageID: u32
2. ObjectFormat: u16 (see format codes below)
3. ProtectionStatus: u16
4. ObjectCompressedSize: u32
5. ThumbFormat: u16
6. ThumbCompressedSize: u32
7. ThumbPixWidth: u32
8. ThumbPixHeight: u32
9. ImagePixWidth: u32
10. ImagePixHeight: u32
11. ImageBitDepth: u32
12. ParentObject: u32
13. AssociationType: u16
14. AssociationDesc: u32
15. SequenceNumber: u32
16. Filename: PTP string (length-prefixed UTF-16LE)
17. CaptureDate: PTP string
18. ModificationDate: PTP string
19. Keywords: PTP string

---

## Object format codes

| Code | Name | Notes |
|---|---|---|
| 0x3801 | PTP_OFC_EXIF_JPEG | Standard JPEG with EXIF |
| 0x3811 | PTP_OFC_DNG | DNG |
| 0xB103 | PTP_OFC_FUJI_RAF | Fuji RAW — from libgphoto2 `ptp.h`; verify against real device before relying on this value in production code |

Some cameras use 0x3800 (undefined image) for JPEG files. Implement format-code tolerant matching in `fuji-transfer` rather than an exact equality check.

---

## Transaction-ID counter (fuji-ptp)

The transaction-ID counter must:
- Start at 1 at the beginning of each session (`OpenSession`).
- Increment by 1 for each operation issued within that session.
- Wrap around per the PTP spec (u32 counter; 0xFFFFFFFF wraps to 1, skipping 0).
- Be unique per session, not per connection — reset on `OpenSession`/`CloseSession`.

---

## Fujifilm vendor extension

`PTP_VENDOR_FUJI = 0x0000000E` is the `VendorExtensionID` value returned in `GetDeviceInfo` for Fujifilm cameras (source: libgphoto2 `ptp.h`). Presence of this value in the DeviceInfo dataset confirms the device is a Fujifilm camera.

Vendor operation codes occupy the range `0x9001–0x9FFF`. Any Fujifilm-specific opcodes found in libgphoto2 or `fuji-cam-wifi-tool` were derived by the open-source community through clean-room reverse engineering and must be marked **"to be confirmed against real device / clean-room interop notes"** before use in production code. This is a hard Frameport legal requirement (see `docs/security/reverse-engineering-boundary.md`). Do not assert undocumented Fuji opcodes as fact in code comments, error messages, or documentation.

---

## Typical session flow

```
[Android] create TCP socket, bind to camera Network, connect to camera command port
[Android] pass connected fd to Rust via JNI

[fuji-ptpip] send INIT_COMMAND_REQUEST (type=1)
[fuji-ptpip] receive INIT_COMMAND_ACK (type=2) → extract ConnectionNumber

[Android] create second TCP socket, bind to camera Network, connect to camera event port
[Android] pass connected event fd to Rust via JNI

[fuji-ptpip] send INIT_EVENT_REQUEST (type=3, ConnectionNumber)
[fuji-ptpip] receive INIT_EVENT_ACK (type=4)

[fuji-ptp / fuji-ptpip] GetDeviceInfo (0x1001) — no session needed, verify VendorExtensionID
[fuji-ptp / fuji-ptpip] OpenSession (0x1002, SessionID=1) — SessionID must be ≥ 1
[fuji-ptp]              transaction-ID counter starts at 1

[fuji-ptp / fuji-ptpip] GetStorageIDs (0x1004) → list of StorageID u32 values
[fuji-ptp / fuji-ptpip] GetObjectHandles (0x1007, 0xFFFFFFFF, 0x0000, 0x00000000)
[fuji-ptp / fuji-ptpip] GetObjectInfo (0x1008, handle) → ObjectInfo
[fuji-transfer]          GetObject (0x1009, handle) → START_DATA + DATA* + END_DATA assembly

[fuji-ptp / fuji-ptpip] CloseSession (0x1003)
```

---

## Key pitfalls

1. **Header size confusion.** The PTP USB Bulk Container header is 12 bytes (Length:u32 + ContainerType:u16 + Code:u16 + TransactionID:u32). The PTP-IP packet header is 8 bytes (Length:u32 + PacketType:u32). Do not mix them.

2. **Non-standard Fujifilm ports.** Standard PTP-IP port is 15740. Fujifilm cameras have been observed on port 55740 (command) and 55742 (event) in open-source traces. Verify against a real device; do not hardcode 15740. (Source: libgphoto2 issue #63.)

3. **SessionID = 0 is invalid.** OpenSession requires `param1 ≥ 1`. Sending SessionID=0 is a protocol error.

4. **ConnectionNumber ≠ SessionID.** INIT_EVENT_REQUEST carries the `ConnectionNumber` returned in INIT_COMMAND_ACK (a PTP-IP transport concept), not the PTP `SessionID` (a session-layer concept). These are independent values.

5. **Pre-session operations.** Only `GetDeviceInfo` (0x1001) and `OpenSession` (0x1002) may be sent before a session is open. All other operations will return `PTP_RC_SessionNotOpen (0x2003)`.

6. **Multi-packet data phase.** GetObject delivers data as START_DATA_PACKET + zero or more DATA_PACKETs + END_DATA_PACKET. Rust must assemble all chunks; never assume one packet = one complete object.

7. **Unknown TotalDataLength.** When START_DATA_PACKET carries `TotalDataLength = 0xFFFFFFFFFFFFFFFF`, the total is unknown. Buffer/stream without pre-allocating to that sentinel value.

8. **GetObjectHandles wildcard.** The all-storages wildcard is `0xFFFFFFFF`. `0x00000000` is not a wildcard for StorageID and may return an error.

9. **Object handles are session-scoped.** PTP does not guarantee handle stability across disconnect/reconnect. Re-enumerate after each new session; do not cache handles across reconnects.

10. **Format-code tolerance for JPEG.** `PTP_OFC_EXIF_JPEG = 0x3801` is the standard code but some cameras emit `0x3800` (undefined image) for JPEG. Match permissively in `fuji-transfer`.

11. **Thumbnail format independence.** The thumbnail format (`ThumbFormat` field in ObjectInfo) is distinct from the parent object format. It may be JPEG even when the object is RAW, or absent entirely. Do not assume.

12. **Vendor opcodes require legal sign-off.** Any Fuji-specific opcode (0x9XXX range) from libgphoto2 or community sources must be marked "to be confirmed against real device" and reviewed against `docs/security/reverse-engineering-boundary.md` before shipping. No exceptions.

13. **Android network binding.** Rust never creates raw sockets. The Android `camera/wifi` module creates the socket, calls `Network.bindSocket` (or equivalent) to bind it to the camera Wi-Fi `Network`, connects it, then passes the fd to Rust. Rust code that tries to create its own socket will silently route via the Android default network and fail to reach the camera.

14. **`PTP_OFC_FUJI_RAF = 0xB103`.** This format code is sourced from libgphoto2's `ptp.h`. Treat as unverified until confirmed on Frameport's target hardware.

---

## References

- libgphoto2 `ptp.h` — operation codes, response codes, format codes, vendor IDs: <https://github.com/gphoto/libgphoto2/blob/master/camlibs/ptp2/ptp.h>
- Wireshark `packet-ptpip.c` — PTP-IP packet type enum and field layouts: <https://github.com/boundary/wireshark/blob/master/epan/dissectors/packet-ptpip.c>
- CIPA DC-X005-2005 PTP-IP Standard: <https://www.cipa.jp/std/documents/e/DC-X005.pdf>
- Julian Schroden — Pairing and Initializing a PTP/IP Connection (2023): <https://julianschroden.com/post/2023-05-10-pairing-and-initializing-a-ptp-ip-connection-with-a-canon-eos-camera/>
- libgphoto2 issue #517 — Fuji X-T series WiFi PTP-IP support: <https://github.com/gphoto/libgphoto2/issues/517>
- libgphoto2 issue #63 — PTP-IP different ports for control and events (Fujifilm 55740/55742): <https://github.com/gphoto/libgphoto2/issues/63>
- sequoia-ptpy `ptp.py` — GetObjectHandles params, GetObjectInfo field order, OpenSession constraint: <https://github.com/Parrot-Developers/sequoia-ptpy/blob/master/ptpy/ptp.py>
- Microsoft Docs: PTP Required Commands (WIA driver conformance): <https://learn.microsoft.com/en-us/windows-hardware/drivers/image/ptp-required-commands>
- ISO 15740:2013 — Picture Transfer Protocol standard: <https://www.iso.org/standard/63602.html>
- mmptp — PTP container structure and transaction phases: <https://www.michaelminn.com/linux/mmptp/>
