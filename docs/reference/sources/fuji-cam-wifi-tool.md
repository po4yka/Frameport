# fuji-cam-wifi-tool — Reverse-Engineered Fujifilm X-Series Wi-Fi Remote Protocol Reference

**Source project:** hkr/fuji-cam-wifi-tool (C++14, MIT license)
**Languages:** C++14
**Analyzed revision:** HEAD as surveyed; primary test hardware X-T10 / X-T100

## Role for Frameport

This project is the most complete publicly available reverse-engineering of the Fujifilm X-series Wi-Fi remote-control protocol. It documents, at binary level, the full three-socket TCP topology (control / async-events / live-view), the Fuji transport framing, the multi-step PTP-IP session handshake, every known opcode, every camera property code with its encoding rules, the two-part property-write pattern, the live-view frame header, and the image-browse/download command sequence. Frameport uses it as a clean-room interoperability reference: the numeric constants (opcodes, property codes, port numbers, magic header bytes) are protocol facts extracted here and re-implemented from scratch in the `fuji-ptpip`, `fuji-core`, `fuji-liveview`, and `fuji-transfer` crates. No source code from this project is reproduced in Frameport.

---

## Protocol Layers

### 1. TCP Transport / Socket Framing

**Coverage:** full
**Key files:** `lib/src/comm.cpp`, `lib/include/comm.hpp`

All communication is plain TCP over three well-known ports on the fixed camera IP address `192.168.0.1`. Every payload is framed with a 4-byte little-endian length prefix that includes the prefix itself. `fuji_send()` prepends this prefix; `fuji_receive()` reads the prefix first then reads the payload. A received payload of 4 bytes all-`0xFF` signals an error or busy state from the camera.

### 2. PTP-IP Session Handshake / Registration

**Coverage:** full
**Key files:** `lib/src/commands.cpp`, `lib/include/message.hpp`

Connection starts with a 78-byte registration message sent raw (no Fuji length prefix) to port 55740. The message has a fixed 24-byte header containing a GUID-like magic value, followed by 54 bytes of UTF-16LE device name (max 26 visible characters, null-padded). The camera replies; a specific 8-byte error pattern means rejected. On success, a fixed multi-step init sequence using `message_type` codes follows before camera control is active.

### 3. Fuji Message Framing / Packet Container

**Coverage:** full
**Key files:** `lib/include/message.hpp`, `lib/src/message.cpp`

Every control message body (after the transport length prefix) is: 2-byte index field (normally 1; 0 for terminate; 2 for two-part follow-up), 2-byte `message_type` (little-endian opcode), 4-byte transaction ID, then an opcode-specific payload. Responses carry a success pattern of `{03 00 01 20}` followed by the echoed 4-byte transaction ID. Message IDs are generated from a monotonically-increasing atomic counter.

### 4. PTP-IP Handshake Sequence / Session Lifecycle

**Coverage:** full
**Key files:** `lib/src/commands.cpp`

`init_control_connection()` has at least 9 ordered steps: (1) send registration message; (2) send `start` (0x1002) with payload `{01 00 00 00}`; (3) send two-part (0x1016) part-1 payload `{01 df 00 00}`, part-2 payload `{05 00}`; (4) send `single_part` (0x1015) with mode selector `{24 df 00 00}`; (5) receive two responses; (6) send two-part with same mode selector then `{ff 00 02 00}`; (7) send `camera_capabilities` (0x902b) and parse 392+ byte response; (8) receive trailing ack; (9) send `camera_remote` (0x101c) with 8-byte zero payload. `terminate_control_connection()` sends `stop` (0x1003) then raw 4 bytes `0xFFFFFFFF`.

### 5. Camera Property / Capabilities Query

**Coverage:** full
**Key files:** `lib/src/capabilities.cpp`, `lib/include/capabilities.hpp`

The `camera_capabilities` response has 12 bytes of unknown prefix, then a sequence of TLV sub-messages each preceded by a 4-byte little-endian sub-message length (inclusive). Each sub-message is a standard PTP `DevicePropDesc`: 2-byte property code, 2-byte data type, 1-byte get/set flag, N-byte factory default, N-byte current value, 1-byte form flag (1=range, 2=enumeration), then range `{min, max, step}` or enumeration `{count, values[]}`. `status_request` using `single_part` (0x1015) with payload `{12 d2 00 00}` is polled continuously; its response is an 8-byte header, then 2-byte count, then count × 6-byte records `{2-byte property code, 4-byte value}`.

### 6. Remote Capture / Shutter Control

**Coverage:** full
**Key files:** `lib/src/commands.cpp`

`shutter()` sends `message_type::shutter` (0x100e) with 8-byte zero payload and awaits a success response on the control socket. After that it optionally reads up to two async notifications from port 55741, then sends `camera_last_image` (0x9022) to get the thumbnail, reads the thumbnail data (first 8 bytes skipped), reads a success response, then reads a final async notification. Video recording: `start_record` (0x9020) 8-byte zero payload returns a transaction ID; `stop_record` (0x9021) echoes that ID as its 4-byte payload.

### 7. Focus Control

**Coverage:** full
**Key files:** `lib/src/commands.cpp`, `tool/src/main.cpp`

Focus point is set with `focus_point` (0x9026) using a 4-byte payload `{y_byte, x_byte, 0x02, 0x03}`. Focus is unlocked with `focus_unlock` (0x9027) with no payload. The AF grid on the X-T100 is 0xD (13) columns by 0x7 (7) rows.

### 8. Live-View / JPEG Stream

**Coverage:** partial
**Key files:** `tool/src/main.cpp`, `lib/include/comm.hpp`

A separate TCP connection is opened to port 55742. Each frame arrives as a Fuji-framed payload. The first 14 bytes are a header (bytes 0–3: `uint32` = 0; bytes 4–7: `uint32` frame counter; bytes 8–13: zeros), followed immediately by JPEG data starting with `FF D8`. No explicit live-view start command is documented here beyond completing the `init_control_connection` handshake in remote mode 0x24.

### 9. Setting Mutation (Two-Part Message Pattern)

**Coverage:** full
**Key files:** `lib/src/commands.cpp`, `lib/include/message.hpp`

Mutable property writes use the two-part pattern: part 1 has `message_type::two_part` (0x1016), index=1, and a 4-byte payload of the property code (little-endian uint32); part 2 re-uses the same transaction ID, index=2, `message_type::two_part`, with a payload of the new value. Relative adjustments (aperture, shutter speed, exposure correction) use dedicated opcodes with a 4-byte payload where byte 0 = 1 (increment) or 0 (decrement).

### 10. Async Event Channel

**Coverage:** partial
**Key files:** `tool/src/main.cpp`, `lib/include/comm.hpp`

Port 55741 receives unsolicited camera events. The shutter flow reads up to three async messages from this port around capture events. No event parsing or decoding is implemented; payloads are logged raw.

### 11. BLE

**Coverage:** absent
**Key files:** (none)

No BLE code present in this project. Wi-Fi only.

### 12. USB

**Coverage:** absent
**Key files:** (none)

No USB code present in this project. Wi-Fi only.

### 13. Media Transfer / Image Download

**Coverage:** partial
**Key files:** `lib/src/commands.cpp`, `lib/include/message.hpp`

`image_info_by_index` (0x1008) takes a 4-byte image index and returns a ~154-byte struct including image ID, dimensions, filename as UTF-16LE, and a date string. `thumbnail_by_index` (0x100a) takes a 4-byte image index and returns thumbnail JPEG data. `full_image` (0x101b) takes an 8-byte payload (4-byte image index + 4-byte image ID) and streams the full image data. The image ID from `image_info_by_index` is required as the second 4-byte parameter to `full_image`.

---

## Protocol Facts

All 59 facts from the survey are rendered below, grouped by category. All numeric values are as found in the source.

### Category: discovery

| Name | Value | Detail | Source ref | Confidence |
|------|-------|--------|------------|------------|
| Camera IP address | `192.168.0.1` | Camera always acts as Wi-Fi access point; its IPv4 address is the fixed constant `192.168.0.1`. The client connects to this address; no mDNS or SSDP discovery is used in this implementation. | `lib/src/comm.cpp:29` | high |

### Category: ptp-ip-handshake

| Name | Value | Detail | Source ref | Confidence |
|------|-------|--------|------------|------------|
| Control server TCP port | `55740` | Main command/response channel. All `message_type` opcodes are exchanged on this port. | `lib/include/comm.hpp:11` | high |
| Async event server TCP port | `55741` | Second socket opened after the control connection is established. Receives unsolicited camera event notifications (e.g. capture complete, state changes). Three async messages are consumed around a shutter event. | `lib/include/comm.hpp:12` | high |
| Transport framing: Fuji length-prefix | (encoding rule) | Every payload sent via `fuji_send()` is wrapped with a 4-byte little-endian length prefix. The prefix value equals `payload_size + 4`, i.e. the prefix counts itself. `fuji_receive()` reads the 4-byte prefix first, subtracts 4, then reads that many payload bytes. A 4-byte response of `0xFFFFFFFF` means camera error or busy. | `lib/src/comm.cpp:181-243` | high |
| Registration message header magic bytes | `01 00 00 00 f2 e4 53 8f ad a5 48 5d 87 b2 7f 0b d3 d5 de d0 02 78 a8 c0` | The 24-byte header of `registration_message` is this fixed byte sequence. It looks like a fixed GUID or protocol version identifier. The message is sent raw (not Fuji-length-prefixed) as a 78-byte blob (24 header + 54 device-name bytes). | `lib/src/commands.cpp:11-13` | high |
| Registration message device name encoding | (encoding rule) | The 54-byte `device_name` field is UTF-16LE, maximum 26 visible characters (54/2 − 1 for null terminator). Each ASCII character is encoded as two bytes: the character byte followed by `0x00`. The example client name used is `HackedClient`. | `lib/src/commands.cpp:18-28; tool/src/main.cpp:353` | high |
| Registration error response | `05 00 00 00 19 20 00 00` | If the camera rejects the registration (e.g. user presses Cancel), it returns exactly 8 bytes: `{05 00 00 00 19 20 00 00}`. The first time a new client name is used the camera displays an approval dialog; subsequent connections from the same name are auto-approved. | `lib/src/commands.cpp:324-329` | high |
| Operation mode selector bytes | `0x24=remote, 0x21=receive, 0x22=browse, 0x31=geo` | During init, the `single_part` and `two_part` handshake messages use a 4-byte mode selector following the pattern `{?? df 00 00}`. Remote/camera-control = `{24 df 00 00}`. Receive mode (browse images on camera) = `{21 df 00 00}`. Browse mode = `{22 df 00 00}`. Geo mode = `{31 df 00 00}`. | `lib/src/commands.cpp:340-353` | high |
| Terminate sentinel | `0xFFFFFFFF` | After sending the `stop` message, the client sends a raw 4-byte value `0xFFFFFFFF` (not Fuji-framed) to signal end of session. | `lib/src/commands.cpp:375-376` | high |
| Connection timeout | `1 second` | The TCP `connect()` call is non-blocking with a 1-second `select()` timeout. If the socket is not writable within 1 second the connection is considered failed. | `lib/src/comm.cpp:155-175` | high |

### Category: ptp-container

| Name | Value | Detail | Source ref | Confidence |
|------|-------|--------|------------|------------|
| Message container layout | (field layout) | After the 4-byte Fuji transport prefix, every control message body is: `[u16 index][u16 message_type][u32 transaction_id][N bytes payload]`. `index` is normally 1; for the second part of a two-part message it is 2; for the terminate sentinel it is 0. All fields are little-endian. | `lib/include/message.hpp:44-48` | high |
| Success response pattern | `03 00 01 20 [4-byte txn-id]` | A success acknowledgement from the camera is exactly 8 payload bytes: `{03 00 01 20}` followed by the 4-byte little-endian transaction ID of the command being acknowledged. Any other content indicates failure. | `lib/src/message.cpp:53-66` | high |

### Category: opcode

| Name | Value | Detail | Source ref | Confidence |
|------|-------|--------|------------|------------|
| hello / registration | `0x0000` | First message type sent to the camera. Used only as the `message_type` field in the raw `registration_message` struct; not used with the standard Fuji container framing. | `lib/include/message.hpp:13` | high |
| start | `0x1002` | Second message in the init sequence after registration. Payload: `{01 00 00 00}`. | `lib/include/message.hpp:15; lib/src/commands.cpp:332-333` | high |
| stop | `0x1003` | Sent as part of `terminate_control_connection()` before the raw `0xFFFFFFFF` sentinel. | `lib/include/message.hpp:16; lib/src/commands.cpp:374` | high |
| image_info_by_index | `0x1008` | Request image metadata by 1-based index. 4-byte payload = image index (u32 LE). Response is ~154 bytes including: u16 status (0x1000=ok), u16 unknown, u32 image_id, various metadata, UTF-16LE filename, UTF-16LE date string, UTF-16LE orientation string. | `lib/include/message.hpp:19; lib/src/commands.cpp:110-127 (comments)` | high |
| thumbnail_by_index | `0x100a` | Request JPEG thumbnail by 1-based index. 4-byte payload = image index (u32 LE). Response is JPEG thumbnail data. | `lib/include/message.hpp:20; lib/src/commands.cpp:115-116 (comments)` | high |
| shutter | `0x100e` | Trigger shutter release. 8-byte zero payload. Expects success response. After success, camera sends async events on port 55741. | `lib/include/message.hpp:21; lib/src/commands.cpp:407-408` | high |
| single_part | `0x1015` | Used for status polling and mode selection. During init, sent with `{24 df 00 00}` to select remote mode. The `status_request_message` uses this type with payload `{12 d2 00 00}` to poll current camera property values. The Wireshark filter shows this as `0x1015` in bytes `[6:2]` of the raw data. | `lib/include/message.hpp:22; lib/include/message.hpp:111-118; README.md:71` | high |
| two_part | `0x1016` | First part of a two-part message sequence used for property writes and init handshake steps. The follow-up part re-uses the same transaction ID with index=2. | `lib/include/message.hpp:23` | high |
| full_image | `0x101b` | Download full-resolution image. 8-byte payload: 4-byte image index + 4-byte image ID (from `image_info_by_index` response). The success response includes the 4-byte image ID. Full image data is streamed before the final success response. | `lib/include/message.hpp:24; lib/src/commands.cpp:104-131 (comments)` | high |
| camera_remote | `0x101c` | Last message in the init handshake before remote control is active. 8-byte zero payload. Must succeed before shutter, focus, and setting commands work. | `lib/include/message.hpp:25; lib/src/commands.cpp:363-366` | high |
| start_record | `0x9020` | Begin video recording. 8-byte zero payload. Returns success with echoed transaction ID; that ID must be saved and passed to `stop_record`. | `lib/include/message.hpp:28; lib/src/commands.cpp:379-390` | high |
| stop_record | `0x9021` | Stop video recording. 4-byte payload = transaction ID returned by `start_record` (u32 LE). | `lib/include/message.hpp:29; lib/src/commands.cpp:392-401` | high |
| camera_last_image | `0x9022` | Retrieve thumbnail of the most recently captured image. No payload (zero-length). Returns thumbnail JPEG data; first 8 bytes are a header, JPEG starts at offset 8. | `lib/include/message.hpp:30; lib/src/commands.cpp:425-437` | high |
| focus_point | `0x9026` | Set AF point. 4-byte payload: `{y_u8, x_u8, 0x02, 0x03}`. On X-T100, valid x range is 1..13 (0xD columns), valid y range is 1..7 (0x7 rows). Requires AF-S mode, not manual. | `lib/include/message.hpp:32; lib/src/commands.cpp:280-281` | high |
| focus_unlock | `0x9027` | Release the current AF lock. No payload. | `lib/include/message.hpp:33; lib/src/commands.cpp:285-287` | high |
| camera_capabilities | `0x902b` | Request the full device property descriptor list. No payload sent. Response is 392+ bytes: 12-byte unknown prefix, then a sequence of sub-messages each with a 4-byte inclusive length prefix followed by a PTP `DevicePropDesc` binary blob. | `lib/include/message.hpp:35; lib/src/commands.cpp:356-359; lib/src/capabilities.cpp:223-262` | high |
| shutter_speed (relative) | `0x902c` | Adjust shutter speed by one step. 4-byte payload: byte 0 = 1 (increment/faster) or 0 (decrement/slower), bytes 1–3 = 0x00. | `lib/include/message.hpp:37; lib/src/commands.cpp:296-300` | high |
| aperture (relative) | `0x902d` | Adjust aperture by one third-stop step. 4-byte payload: byte 0 = 1 (open/increment) or 0 (close/decrement), bytes 1–3 = 0x00. | `lib/include/message.hpp:38; lib/src/commands.cpp:289-294` | high |
| exposure_correction (relative) | `0x902e` | Adjust exposure compensation by one step. 4-byte payload: byte 0 = 1 (positive/increment) or 0 (negative/decrement), bytes 1–3 = 0x00. | `lib/include/message.hpp:39; lib/src/commands.cpp:303-308` | high |

### Category: property-code

| Name | Value | Detail | Source ref | Confidence |
|------|-------|--------|------------|------------|
| property_white_balance | `0x5005` | Standard PTP white balance property. | `lib/include/capabilities.hpp:13` | high |
| property_aperture | `0x5007` | Standard PTP f-number property. Value is f-number × 100 as uint16. `0x0000` = Auto, `0xFFFF` = not applicable. | `lib/include/capabilities.hpp:14; lib/src/settings.cpp:185-191` | high |
| property_focus_mode | `0x500a` | Focus mode. Values: 1=Manual, 32769 (0x8001)=Single AF, 32770 (0x8002)=Continuous AF. | `lib/include/capabilities.hpp:15` | high |
| property_flash | `0x500c` | Flash mode. Standard PTP code. Many values documented. | `lib/include/capabilities.hpp:16` | high |
| property_shooting_mode | `0x500e` | Exposure mode. Values: 1=Manual, 2=Program, 3=Aperture Priority, 4=Shutter Priority, 6=Auto. | `lib/include/capabilities.hpp:17` | high |
| property_exposure_compensation | `0x5010` | Exposure correction. Value is int16 in milli-EV (divide by 1000.0 for EV stops). | `lib/include/capabilities.hpp:18` | high |
| property_self_timer | `0x5012` | Self-timer delay. Values: 0=Off, 1=1s, 2=2s, 3=5s, 4=10s. | `lib/include/capabilities.hpp:19` | high |
| property_film_simulation | `0xd001` | Fujifilm-specific film simulation mode. Numeric codes 1–16: Provia=1, Velvia=2, Astia=3, Monochrome=4, Sepia=5, Pro-Neg Hi=6, Pro-Neg Std=7, Mono Y filter=8, Mono R filter=9, Mono G filter=10, Classic Chrome=11, Acros=12, Acros Y=13, Acros R=14, Acros G=15, Eterna=16. | `lib/include/capabilities.hpp:20; lib/src/settings.cpp:63-80` | high |
| property_image_format | `0xd018` | Image quality/format. Values: 2=JPEG Fine, 3=JPEG Normal, 4=RAW+JPEG Fine, 5=RAW+JPEG Normal. Note: camera always reports RAW+FINE when in pure RAW mode (no pure-RAW enum value). | `lib/include/capabilities.hpp:21` | high |
| property_recmode_enable | `0xd019` | Whether the video record button is available. Values: 0=Unavailable, 1=Available. | `lib/include/capabilities.hpp:22` | high |
| property_f_ss_control | `0xd028` | Aperture/shutter speed control state. Values: 0=both adjustable, 1=SS hit limit, 2=aperture hit limit, 3=both hit limit. | `lib/include/capabilities.hpp:23` | high |
| property_iso | `0xd02a` | ISO sensitivity. The 32-bit value is decomposed: bit 31 = auto flag, bit 30 = emulated flag, bits 0–23 = numeric ISO value. `0xFFFFFFFF` means auto (for movie ISO). | `lib/include/capabilities.hpp:24; lib/include/settings.hpp:12-14; lib/src/settings.cpp:153-167` | high |
| property_movie_iso | `0xd02b` | ISO for video recording. Same bit-field encoding as `property_iso`. Value `0xFFFFFFFF` = auto. | `lib/include/capabilities.hpp:25` | high |
| property_focus_point | `0xd17c` | Current AF point coordinates. The 32-bit value encodes `{x = bits 8-15, y = bits 0-7}` when read: `x = val >> 8`, `y = val & 0xFF`. | `lib/include/capabilities.hpp:26; lib/include/settings.hpp:21-25` | high |
| property_focus_lock | `0xd209` | AF lock state. Values: 0=unlocked, 1=locked. | `lib/include/capabilities.hpp:27` | high |
| property_device_error | `0xd21b` | Camera error status. Value 0 = no error. | `lib/include/capabilities.hpp:28` | high |
| property_image_space_sd | `0xd229` | Remaining image space on SD card (raw integer; units unclear). | `lib/include/capabilities.hpp:29` | high |
| property_movie_remaining_time | `0xd22a` | Remaining video recording time on SD card (raw integer; units unclear). | `lib/include/capabilities.hpp:30` | high |
| property_shutter_speed | `0xd240` | Shutter speed. 32-bit value: bit 31 = sub-second flag. If bit 31 is set: `1 / (bits 0-27 / 1000.0)` seconds. If bit 31 is clear: `(bits 0-27 / 1000.0)` seconds. Value `0xFFFFFFFF` = not applicable / bulb. | `lib/include/capabilities.hpp:31; lib/include/settings.hpp:15-16; lib/src/settings.cpp:169-179` | high |
| property_image_aspect | `0xd241` | Image size and aspect ratio combination. Values: 2=S 3:2, 3=S 16:9, 4=S 1:1, 6=M 3:2, 7=M 16:9, 8=M 1:1, 10=L 3:2, 11=L 16:9, 12=L 1:1. | `lib/include/capabilities.hpp:32` | high |
| property_battery_level | `0xd242` | Battery level. Standard NP-W126: 1=Critical, 2=One bar, 3=Two bars, 4=Full. NP-W126S: 6=Critical, 7–10=bars, 11=Full. | `lib/include/capabilities.hpp:33` | high |

### Category: liveview-format

| Name | Value | Detail | Source ref | Confidence |
|------|-------|--------|------------|------------|
| JPEG stream server TCP port | `55742` | Third socket opened independently for the live-view JPEG stream. Each Fuji-framed payload starts with a 14-byte header then JPEG data. The Wireshark filter `data.data[18:2] == ff:d8` confirms JPEG start at offset 18 from the raw TCP data (4-byte Fuji prefix + 14-byte frame header). | `lib/include/comm.hpp:13; README.md:67` | high |
| Live-view frame header structure | (field layout) | Each Fuji-framed live-view payload begins with a 14-byte header structured as: bytes 0–3 = uint32 (observed as 0); bytes 4–7 = uint32 frame counter (increments per frame); bytes 8–13 = zeros. JPEG data follows immediately after byte 14 (starts with `FF D8`). Skip 14 bytes to reach raw JPEG. | `tool/src/main.cpp:224-229; README.md:67` | high |

### Category: transfer

| Name | Value | Detail | Source ref | Confidence |
|------|-------|--------|------------|------------|
| Image info response layout (browse mode) | (response layout) | Response to `image_info_by_index`: 2-byte response type (`0x1000 0x0010` = ok), 2-byte unknown, 4-byte `image_id`, then fields including dimensions. Later in the blob: UTF-16LE filename prefixed by 1-byte character count, UTF-16LE datetime string (format `20160102T150136`), UTF-16LE EXIF orientation string (`Orientation:1`). The 4-byte `image_id` is required as the second argument to `full_image` (0x101b). | `lib/src/commands.cpp:100-131 (inline hex comments)` | medium |

### Category: film-sim

| Name | Value | Detail | Source ref | Confidence |
|------|-------|--------|------------|------------|
| Film simulation numeric codes | `1-16` | Provia/Standard=1, Velvia/Vivid=2, Astia/Soft=3, Monochrome=4, Sepia=5, Pro Neg Hi=6, Pro Neg Std=7, Monochrome+Y filter=8, Monochrome+R filter=9, Monochrome+G filter=10, Classic Chrome=11, Acros=12, Acros+Y=13, Acros+R=14, Acros+G=15, Eterna/Cinema=16. | `lib/include/capabilities.hpp:121-136; lib/src/settings.cpp:63-80` | high |

### Category: other

| Name | Value | Detail | Source ref | Confidence |
|------|-------|--------|------------|------------|
| Status response message payload | `12 d2 00 00` | The `status_request` uses `single_part` (0x1015) with payload `{12 d2 00 00}` and transaction ID = 0. Camera response: 8-byte message header, then 2-byte count of property entries, then count × 6-byte records where each record is `{2-byte property_code, 4-byte value}`, all little-endian. | `lib/include/message.hpp:111-118; lib/src/commands.cpp:452-492` | high |
| Two-part property write protocol | (protocol sequence) | Writing a property: (1) send `two_part` (0x1016) index=1 with 4-byte payload = property_code as uint32 LE; (2) immediately send `two_part` (0x1016) index=2 with the same transaction ID and 4-byte payload = new value as uint32 LE; (3) wait for success response only after the second part. | `lib/src/commands.cpp:266-276` | high |
| Geo position sharing format | (string format) | Geographic position is sent as a two-part message (0x1016) where part-2 payload is a UTF-16LE string in the format: `H35 32 29.131848,N 0132 22.842972,E 00000.00,M 000.0,K 2016:01:03 15:41:40.130`. This encodes NMEA-style GPRMC/GGA coordinates as a human-readable Unicode string. | `lib/src/commands.cpp:86-92 (hex dump comments)` | medium |

---

## Frameport Mapping

| Target crate / module | What to borrow | Clean-room note |
|----------------------|----------------|-----------------|
| `fuji-ptpip` | TCP connection topology: three sockets to `192.168.0.1` on ports 55740 (control), 55741 (async events), 55742 (live-view JPEG). Fuji transport framing (4-byte LE length prefix inclusive of itself). Message container layout (`u16 index`, `u16 opcode`, `u32 txn-id`, payload). Success response pattern (`03 00 01 20` + txn-id). Transaction ID generation (atomic counter). Connection timeout strategy (non-blocking connect + 1-second select). | Define your own structs (e.g. `FujiFrame { len: u32, body: Vec<u8> }`) and a codec that prepends/strips the 4-byte length. Implement the three-port topology as three independent `TcpStream` instances passed to Rust as file descriptors per ADR-0002. The `0xFFFFFFFF` busy/error sentinel should be a typed error variant. |
| `fuji-ptpip` | Registration handshake: 78-byte message with 24-byte magic header + 54-byte UTF-16LE device name. Rejection detection via the 8-byte error pattern. Multi-step init sequence order: hello → start(0x1002) → two-part mode negotiation → single_part mode select → two-part confirm → camera_capabilities(0x902b) → camera_remote(0x101c). Terminate sequence: stop(0x1003) → raw `0xFFFFFFFF`. | Express the init sequence as a state machine enum (`AwaitingRegistration`, `AwaitingStart`, `AwaitingModeNeg`, `AwaitingCapabilities`, `AwaitingRemoteEnable`, `Ready`). Each state transition sends one packet and awaits a response. The 24-byte magic header is a protocol constant, not a proprietary asset; record it as a named constant `FUJI_PTPIP_MAGIC_HEADER: [u8; 24]` with a doc comment citing this RE project. |
| `fuji-ptpip` | Operation mode selector byte in init: `0x24` for remote/camera-control, `0x21` for receive (image browsing), `0x22` for browse, `0x31` for geo. The `{?? df 00 00}` pattern where the first byte selects mode. | Define an enum `SessionMode { Remote = 0x24, Receive = 0x21, Browse = 0x22, Geo = 0x31 }` and encode it into the init handshake payload. Frameport v1 only needs `Remote` mode. |
| `fuji-core` | All opcode values for the command set: shutter (0x100e), camera_last_image (0x9022), start_record (0x9020), stop_record (0x9021), focus_point (0x9026), focus_unlock (0x9027), relative aperture (0x902d), relative shutter speed (0x902c), relative exposure compensation (0x902e), full_image (0x101b), image_info_by_index (0x1008), thumbnail_by_index (0x100a), camera_capabilities (0x902b), status poll (0x1015 with payload `12 d2 00 00`). Also the two-part property-write protocol. | Define an enum `FujiOpcode` with all values. Implement each command as a function that constructs the appropriate request buffer and parses the typed response. The two-part write protocol should be a generic helper taking a property code and value. |
| `fuji-core` | All property codes and their numeric values (0x5005 through 0xd242). The ISO bit-flag encoding (bit 31 = auto, bit 30 = emulated, bits 0–23 = value). The shutter speed bit-flag encoding (bit 31 = sub-second flag, bits 0–27 = value/1000). The aperture encoding (uint16 × 100 = f-number, 0 = Auto, 0xFFFF = N/A). The exposure compensation int16 milli-EV encoding. The focus_point coordinate packing (x=bits 8–15, y=bits 0–7). | Define a `FujiPropertyCode` enum. Write separate newtype wrappers (`IsoValue`, `ShutterSpeed`, `Aperture`, `ExposureComp`, `FocusPoint`) with `From`/`Into` implementations that encode/decode the bit-field layout without copying the C++ struct definitions. |
| `fuji-core` | Shutter post-capture flow: trigger shutter → consume N async events from port 55741 → send camera_last_image (0x9022) → receive thumbnail (skip 8-byte header) → receive final success response → consume final async event. | Model this as a `CaptureSession` state machine that drives both the control socket and the async event socket concurrently via `tokio::select!` or sequential awaits, with cancel-safety annotation per the `llm-rust-prompts` rule. |
| `fuji-core` | `camera_capabilities` response parsing: skip 12-byte prefix, then iterate TLV sub-messages (each with 4-byte inclusive length), parse each as PTP `DevicePropDesc`: property_code (u16), data_type (u16), get_set (u8), default_value (N bytes), current_value (N bytes), form_flag (u8), then range `{min, max, step}` if form_flag=1, or enumeration `{count (u16), values[]}` if form_flag=2. | Implement `parse_device_prop_desc(bytes: &[u8]) -> Result<DevicePropDesc, ProtocolError>` as a `nom` or manual cursor-based parser. Keep it separate from the Fuji-specific `property_code` enum so it can be reused if standard PTP/MTP support is added later. |
| `fuji-liveview` | Live-view stream topology: separate TCP socket to port 55742, continuously Fuji-framed. Each frame: 14-byte header (bytes 4–7 = u32 frame counter), then JPEG data (starts with `FF D8`). No explicit start command needed beyond completing the remote-mode init sequence. | Implement a `LiveViewStream` as an `async Stream<Item = Result<JpegFrame, LiveViewError>>`. `JpegFrame` wraps the JPEG bytes and the frame counter. Parse the 14-byte header to extract the counter; validate `FF D8` as a sanity check. Run this on the dedicated port-55742 socket independently of the control socket. |
| `fuji-transfer` | Image browsing and download sequence: `image_info_by_index` (0x1008) to get metadata and `image_id`, `thumbnail_by_index` (0x100a) for preview, `full_image` (0x101b) with both index and `image_id` to download full data. The image response includes UTF-16LE filename and datetime. | Define a `MediaObjectInfo` struct with fields for index, `image_id` (u32), filename (String decoded from UTF-16LE), datetime, and dimensions. Implement a downloader that opens a MediaStore `IS_PENDING` fd, passes it to Rust via the fd-handoff ADR-0002 mechanism, and streams `full_image` data into it. |

---

## Standout Findings

1. **THREE SOCKETS SIMULTANEOUSLY.** The protocol requires three independent TCP connections to `192.168.0.1` — port 55740 for synchronous control commands, port 55741 for asynchronous camera events, port 55742 for the JPEG live-view stream. Each must be bound to the camera Wi-Fi network (Android `Network` binding per ADR-0002) before use. Failing to bind any one of the three to the correct network will silently route traffic over mobile data or another interface.

2. **REGISTRATION MAGIC HEADER IS NOT FUJI-LENGTH-PREFIXED.** The 78-byte registration handshake message starting with `{01 00 00 00 f2 e4 53 8f ad a5 48 5d 87 b2 7f 0b d3 d5 de d0 02 78 a8 c0}` is the only raw send in the entire protocol. All subsequent messages use the standard Fuji length-prefix framing. Missing this distinction will cause the camera to reject the initial connection silently.

3. **MULTI-STEP INIT SEQUENCE IS ORDER-CRITICAL.** `init_control_connection()` has at least 9 ordered steps. The `camera_remote` (0x101c) message is the explicit gate that enables shutter, focus, and property commands. Sending any camera command before this gate completes results in failures. Frameport's session state machine must prevent commands from being issued until `Ready` state is reached.

4. **TWO-PART PROPERTY WRITE PATTERN.** Mutable settings use a split two-message protocol: part 1 carries only the property code, part 2 carries only the new value — both share the same transaction ID with index=1 and index=2 respectively. The opcode for both parts is `0x1016`. This is Fuji-specific and differs from standard PTP `SetDevicePropValue`. Writing both parts atomically with the same txn-id is essential.

5. **ISO AND SHUTTER SPEED BIT FLAGS.** ISO values pack metadata into high bits of a u32: bit 31=auto, bit 30=emulated, bits 0–23=numeric ISO. Shutter speed packs direction into bit 31: set means sub-second (value = `1 / (bits 0-27 / 1000)`), clear means seconds (value = `bits 0-27 / 1000`). These non-trivial encodings must be precisely implemented to correctly display and set these values.

6. **STATUS POLLING vs CAPABILITY QUERY.** The protocol has two distinct mechanisms for reading camera state. `camera_capabilities` (0x902b) returns the full PTP `DevicePropDesc` structure (enumeration lists, min/max, defaults) and is called once at session start. The status poll via `single_part` (0x1015) with payload `{12 d2 00 00}` returns only current values as compact `{code, value}` pairs and is intended for continuous polling. `fuji-core` must implement both.

7. **LIVE-VIEW FRAME COUNTER IN HEADER.** The 14-byte live-view frame header contains a u32 frame counter at bytes 4–7. This can be used to detect dropped frames without parsing JPEG content. JPEG data begins at byte 14 of the payload, confirmed by the Wireshark filter showing `FF D8` at raw TCP offset 18 (after the 4-byte Fuji transport prefix).

8. **VIDEO RECORDING REQUIRES ID ECHO.** `start_record` (0x9020) returns a success response containing the transaction ID. `stop_record` (0x9021) must echo that same transaction ID as its 4-byte payload. If the client loses this ID (e.g. process restart between start and stop), there is no clean way to stop an in-progress recording — the entire session must be terminated and re-established.

---

## Caveats / IP & License Risk

1. **Reverse-engineered protocol, not Fujifilm documentation.** This analysis is based on the hkr/fuji-cam-wifi-tool open-source RE project (MIT-licensed). The protocol was reverse-engineered from Fujifilm X-series cameras and is not documented by Fujifilm. Protocol behavior may differ across camera models and firmware versions.

2. **Primary test hardware was X-T10 / X-T100; Frameport targets X-T5.** The X-T5 may have different firmware-level behavior, additional opcodes, or changed property codes. All constants extracted here should be validated against actual X-T5 wire traffic before shipping.

3. **Registration magic header IP status.** The 24-byte magic header bytes (`{01 00 00 00 f2 e4 53 8f...}`) are reproduced here as interoperability facts. They do not appear to be a proprietary credential, key, or token — they are a fixed protocol-layer identifier analogous to a PTP GUID, publicly documented in multiple open-source RE projects. Nevertheless, Frameport's legal team should confirm that embedding them in original Rust code is acceptable under applicable license terms before shipping.

4. **Medium-confidence facts from hex-dump comments.** Several protocol details (image info response layout, geo position format) are documented only in inline hex-dump comments in `commands.cpp` and are marked `medium` confidence. They have not been verified against a live packet capture in this analysis.

5. **Async event channel structure is undocumented.** Port 55741 has no documented event structure in this codebase — payloads are only logged as raw bytes. The number of events consumed around a shutter operation (up to 3) was observed empirically. Actual event type codes and their semantics would need to be reverse-engineered from additional sources before Frameport can parse or react to individual event types.

6. **Live-view start/stop is implicit.** There is no explicit "start liveview" command documented here beyond completing the remote-mode handshake. Some camera models or firmware versions may require an explicit enable command. This must be verified against an X-T5.

7. **Android network binding is mandatory.** The connection model assumes the camera is a Wi-Fi access point. Frameport must ensure its Android socket is explicitly bound to the Fujifilm camera Wi-Fi `Network` object (per ADR-0002) before any `TCP connect()` call; otherwise the OS will route traffic via mobile data or another Wi-Fi network and all connections will fail silently.

