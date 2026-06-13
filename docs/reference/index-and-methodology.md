# Frameport External-Reference Index and Clean-Room Methodology

This document synthesises five open-source / reverse-engineered references used as interoperability guidance for Frameport's Fujifilm camera protocol implementation. It covers three areas: (1) camera property codes and film-simulation encodings, (2) a conservative compatibility matrix, and (3) clean-room methodology and IP risk.

---

## 1. Camera Properties and Film Simulation

### 1.1 Property Code Namespace

Fujifilm vendor device properties occupy the range **0xD001–0xDF44**. Properties below 0xD000 are ISO 15740 standard PTP properties shared with all PTP cameras. Both ranges are observable from `GetDevicePropDesc` / `GetDevicePropDescAll` on a live camera and are therefore interoperability facts, not protectable expression.

Standard properties relevant to Frameport (confirmed across multiple sources):

| Code | Name | Encoding |
|------|------|----------|
| 0x5005 | WhiteBalance | uint16 enum |
| 0x5007 | FNumber (aperture) | uint16; f×100; 0x0000=Auto, 0xFFFF=N/A |
| 0x500A | FocusMode | uint16 enum; 1=Manual, 0x8001=Single, 0x8002=Continuous |
| 0x500C | FlashMode | uint16 enum |
| 0x500D | ExposureTime | uint16 (standard PTP shutter) |
| 0x500E | ExposureProgramMode | uint16; 1=Manual, 2=Program, 3=Av, 4=Tv, 6=Auto |
| 0x5010 | ExposureBiasCompensation | int16 milli-EV |
| 0x5012 | SelfTimer | uint16; 0=Off, 1=1s, 2=2s, 3=5s, 4=10s |

Fujifilm-specific properties confirmed across multiple independent sources (fuji-cam-wifi-tool, libfuji, filmkit):

| Code | Name | Encoding |
|------|------|----------|
| 0xD001 | FilmSimulation | uint16 enum — see §1.2 |
| 0xD018 | ImageFormat | uint16; 2=JPEG Fine, 3=JPEG Normal, 4=RAW+JPEG Fine, 5=RAW+JPEG Normal |
| 0xD02A | ISO | uint32 bit-field — see §1.3 |
| 0xD02B | MovieISO | uint32 same encoding as ISO; 0xFFFFFFFF=Auto |
| 0xD17C | FocusPoint | uint32; x=bits 8–15, y=bits 0–7 |
| 0xD183 | StartRawConversion | uint16; write 0x0000 to trigger |
| 0xD184 | IOPCodes | binary block (part of RawConvProfile machinery) |
| 0xD185 | RawConvProfile | 625-byte native binary — see §1.4 |
| 0xD186 | FirmwareVersion | string |
| 0xD187 | FirmwareVersion2 | string |
| 0xD209 | FocusLock | uint16; 0=unlocked, 1=locked |
| 0xD212 | EventsList / CurrentState | variable array — see §1.5 |
| 0xD21B | DeviceError | uint16; 0=no error |
| 0xD222 | ObjectCount | uint16; available in events after session open |
| 0xD226 | CompressSmall | uint16; 1=compressed 400–800 KB preview |
| 0xD227 | EnableCorrectFileSize | uint16; must be 1 before GetObjectInfo/GetObject |
| 0xD229 | ImageSpaceSD | uint32 remaining SD capacity |
| 0xD22A | MovieRemainingTime | uint32 remaining record time |
| 0xD240 | ShutterSpeed | uint32 bit-field — see §1.3 |
| 0xD241 | ImageAspect | uint16; 2=S 3:2, 3=S 16:9, 4=S 1:1, 6=M 3:2, 7=M 16:9, 8=M 1:1, 10=L 3:2, 11=L 16:9, 12=L 1:1 |
| 0xD242 | BatteryLevel | uint16; NP-W126: 1=Critical–4=Full; NP-W126S: 6=Critical–11=Full |
| 0xD36A | BatteryInfo1 | uint32 percentage |
| 0xD36B | BatteryInfo2 | uint32 |
| 0xD500 | Geolocation (PTP/IP path) | ASCII NMEA-like string |
| 0xDF00 | CameraState | uint16 enum; 0=Wait, 1=MultipleTransfer, 2=FullAccess, 3=PCAutoSave, 6=RemoteAccess |
| 0xDF01 | ClientState | uint16 enum — controls mode transition |
| 0xDF21 | ImageGetVersion | version negotiation; read then re-write |
| 0xDF22 | GetObjectVersion | version negotiation; read then re-write |
| 0xDF24 | RemoteVersion | uint32; Camera Connect 2.11 = 0x2000C |
| 0xDF25 | RemoteGetObjectVersion | uint32; set to 5 during remote image view |

**Preset slot system** (confirmed on X100VI via filmkit, March 2026):

| Code | Name |
|------|------|
| 0xD18C | PresetSlot (write 1–7 to select) |
| 0xD18D | PresetName (PTP string) |
| 0xD18E–0xD1A5 | Per-slot settings (24 properties) — see §1.6 |

### 1.2 Film Simulation Codes

The following numeric codes are confirmed across three independent sources (fuji-cam-wifi-tool, libfuji, filmkit). They are used both as the value of property 0xD001 and as the FilmSimulation field in the 0xD185 RawConvProfile.

| Code | Name |
|------|------|
| 0x01 | Provia / Standard |
| 0x02 | Velvia / Vivid |
| 0x03 | Astia / Soft |
| 0x04 | PRO Neg Hi |
| 0x05 | PRO Neg Std |
| 0x06 | Monochrome |
| 0x07 | Monochrome + Ye filter |
| 0x08 | Monochrome + R filter |
| 0x09 | Monochrome + G filter |
| 0x0A | Sepia |
| 0x0B | Classic Chrome |
| 0x0C | Acros |
| 0x0D | Acros + Ye |
| 0x0E | Acros + R |
| 0x0F | Acros + G |
| 0x10 | Eterna / Cinema |
| 0x11 | Classic Neg |
| 0x12 | Eterna Bleach Bypass |
| 0x13 | Nostalgic Neg |
| 0x14 | Reala Ace |

**Codes 0x11–0x14** are sourced exclusively from filmkit and noted as "may need adjustment for X-Processor 5." Treat with medium confidence until validated on X-T5.

**Monochrome simulation set** (requires special handling): 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0C, 0x0D, 0x0E, 0x0F. For these sims the Color property (0xD19F in presets) must not be written; MonoWC (0xD193) and MonoMG (0xD194) are applicable instead.

**Important note on fuji-cam-wifi-tool codes:** That project mapped Provia=1 through Eterna=16 but assigned PRO Neg Hi=6, PRO Neg Std=7, and used different ordinals for Monochrome variants. The libfuji / filmkit ordering above (PRO Neg Hi=0x04, PRO Neg Std=0x05, Monochrome=0x06) is more widely confirmed. **Do not mix the two ordinal schemes.** Validate against a live X-T5 before shipping.

### 1.3 ISO and Shutter Speed Bit-Field Encodings

**ISO (property 0xD02A / 0xD02B):** 32-bit value.
- Bit 31 set → Auto ISO
- Bit 30 set → Emulated ISO
- Bits 0–23 → Numeric ISO value
- 0xFFFFFFFF → Auto (movie ISO)

**ShutterSpeed (property 0xD240):** 32-bit value.
- Bit 31 set → sub-second; denominator = bits 0–27 / 1000 (i.e. 1/N seconds)
- Bit 31 clear → whole seconds; value = bits 0–27 / 1000 seconds
- 0xFFFFFFFF → N/A or Bulb

**Aperture (property 0x5007):** uint16; actual f-number = value / 100. Zero = Auto, 0xFFFF = N/A.

**ExposureBiasCompensation (property 0x5010):** int16 in milli-EV; divide by 1000 for EV stops.

### 1.4 RawConvProfile (0xD185) Binary Structure

The camera returns a **625-byte native profile** from `GetDevicePropValue(0xD185)` when a RAF is loaded (confirmed X100VI, filmkit March 2026). This differs from the 629-byte format documented by libfuji (from X-H1 / X Raw Studio). A lenient parser that does not assume fixed total size is required.

Layout:
- Bytes 0–1: uint16 `n_props` (number of int32 parameters that follow at the end)
- Bytes 2–512: 511-byte `iop_codes` block (MTP-style string; camera incorrectly writes string length as 8 instead of 9 — parser must be tolerant)
- Bytes 513–end: `n_props` × int32 LE parameter values

**Field index table** (confirmed X100VI, NativeIdx from filmkit d185.ts):

| Index | Field | Encoding |
|-------|-------|----------|
| 4 | ExposureBias | millistops as int32 |
| 6 | DynamicRange% | 100/200/400 as int32 |
| 7 | DRangePriority | int32 |
| 8 | FilmSimulation | enum — see §1.2 |
| 9 | GrainEffect | flat enum (see §1.7) |
| 10 | ColorChrome | 1-indexed: 1=Off, 2=Weak, 3=Strong |
| 11 | SmoothSkin | 1-indexed: 1=Off, 2=Weak, 3=Strong |
| 12 | WBMode | 0=use EXIF/sentinel |
| 13 | WBShiftR | int32 |
| 14 | WBShiftB | int32 |
| 15 | WBColorTemp | Kelvin as int32 |
| 16 | HighlightTone | ×10 encoded int32 |
| 17 | ShadowTone | ×10 encoded int32 |
| 18 | Color | ×10 encoded int32; 0=sentinel |
| 19 | Sharpness | ×10 encoded int32 |
| 20 | NoiseReduction | proprietary non-linear — see §1.8 |
| 25 | ColorChromeFxBlue | 1-indexed: 1=Off, 2=Weak, 3=Strong |
| 27 | Clarity | ×10 encoded int32 |

**Patch strategy:** Read the camera-supplied 625-byte base profile, clone it, then overwrite only the indices corresponding to user-changed parameters. This preserves EXIF sentinel values in untouched fields and avoids a visual shift that building from scratch would cause.

### 1.5 EventsList / Status Polling (0xD212)

Two separate read mechanisms exist and must both be implemented:

**`camera_capabilities` (opcode 0x902B):** Returns the full PTP DevicePropDesc structure for every supported property — enumeration lists, min/max, defaults. Called once at session start. Response: 12-byte unknown prefix, then TLV sub-messages each with a 4-byte inclusive length prefix, each sub-message is a standard PTP DevicePropDesc blob.

**EventsList polling (`GetDevicePropValue(0xD212)`):** Returns only current values as compact `{code u16, value u32}` pairs. Format: uint16 count, then count × 6-byte records. Intended for continuous polling. Must also be called regularly during transfers to keep the connection alive. Also delivers the `ObjectCount` (0xD222) value needed to determine how many images are available for download.

### 1.6 Preset Slot Properties (0xD18C–0xD1A5)

Writing a preset requires: (1) write 0xD18C = slot number (1–7); (2) wait 100 ms; (3) write 0xD18D = preset name string; (4) write 0xD18E–0xD1A5 in the confirmed order below; (5) verify name and setting readback.

Confirmed write order from Wireshark capture of X RAW Studio (filmkit):

`D18E, D18F, D190, D191, D192, [D193 if mono], [D194 if mono], D195, D196, D197, D198, D199, [D19C if WB=0x8007], D19A, D19B, D19D, D19E, [D19F if not mono], D1A0, D1A1, D1A2, D1A3, D1A4, D1A5`

| Code | Field | Notes |
|------|-------|-------|
| 0xD18E | ImageSize | default=7 |
| 0xD18F | ImageQuality | default=4 |
| 0xD190 | DynamicRange% | raw: 100/200/400 |
| 0xD191 | unknown | always 0 |
| 0xD192 | FilmSimulation | enum §1.2 |
| 0xD193 | MonoWC×10 | mono sims only; camera rejects write of 0 |
| 0xD194 | MonoMG×10 | mono sims only; camera rejects write of 0 |
| 0xD195 | GrainEffect | flat 1-indexed enum — see §1.7 |
| 0xD196 | ColorChrome | 1-indexed |
| 0xD197 | ColorChromeFxBlue | 1-indexed |
| 0xD198 | SmoothSkin | 1-indexed |
| 0xD199 | WhiteBalance | uint16 stored as int16 — see §1.9 |
| 0xD19A | WBShiftR | int16 |
| 0xD19B | WBShiftB | int16 |
| 0xD19C | ColorTemp (K) | only when WB=0x8007; write immediately after D199 |
| 0xD19D | HighlightTone×10 | int16; 0x8000=sentinel |
| 0xD19E | ShadowTone×10 | int16; 0x8000=sentinel |
| 0xD19F | Color×10 | int16; omit for mono sims |
| 0xD1A0 | Sharpness×10 | int16 |
| 0xD1A1 | HighIsoNR | proprietary non-linear — see §1.8 |
| 0xD1A2 | Clarity×10 | int16 |
| 0xD1A3 | LongExpNR | 1=On |
| 0xD1A4 | ColorSpace | 1=sRGB |
| 0xD1A5 | unknown | always 7 |

### 1.7 Grain Effect Encoding Duality

**Critical: two incompatible wire encodings exist for grain.** Do not mix them.

In the **0xD185 profile** (byte-packed uint16):
- High byte = size: 0=Small, 1=Large
- Low byte = strength: 0=Off, 2=Weak, 3=Strong
- Combined: Off=0x0000, WeakSmall=0x0002, StrongSmall=0x0003, WeakLarge=0x0102, StrongLarge=0x0103

In **preset property 0xD195** (flat 1-indexed enum):
- 1=Off, 2=WeakSmall, 3=StrongSmall, 4=WeakLarge, 5=StrongLarge

### 1.8 HighIsoNR Non-Linear Encoding

Property 0xD1A1 / profile index 20 uses a proprietary non-linear lookup, confirmed via Wireshark capture of X RAW Studio (filmkit). This is **not** a ×10 encoding. The complete mapping:

| UI Value | Wire uint16 |
|----------|-------------|
| -4 | 0x8000 |
| -3 | 0x7000 |
| -2 | 0x4000 |
| -1 | 0x3000 |
| 0 | 0x2000 |
| +1 | 0x1000 |
| +2 | 0x0000 |
| +3 | 0x6000 |
| +4 | 0x5000 |

**Warning:** 0x8000 here means NR=-4, whereas in all tone fields (HighlightTone, ShadowTone, Color, etc.) 0x8000 is the "use EXIF default" sentinel. The same bit pattern has context-dependent meaning.

### 1.9 White Balance Mode Codes

WB codes in preset property 0xD199 are uint16 on the wire but the generic property reader returns them as signed int16. Values with high-bit set (e.g. 0x8001) come back as negative signed integers. Callers must mask with 0xFFFF.

| Code | Name |
|------|------|
| 0x0000 | AsShot |
| 0x0002 | Auto |
| 0x0004 | Daylight |
| 0x0006 | Incandescent |
| 0x0008 | Underwater |
| 0x8001 | Fluorescent 1 |
| 0x8002 | Fluorescent 2 |
| 0x8003 | Fluorescent 3 |
| 0x8006 | Shade |
| 0x8007 | ColorTemp (K) |
| 0x8021 | Ambience Priority |

### 1.10 Tone Parameters ×10 Convention

HighlightTone, ShadowTone, Color, Sharpness, Clarity, MonoWC, MonoMG are stored as `raw = ui_integer × 10` (e.g. +1.5 → 15, -2 → -20). In preset properties they are signed int16; in the 0xD185 profile they are signed int32. The sentinel value **0x8000** (int16: -32768) means "use default/EXIF value" and should decode to 0 in the UI.

In libfuji's 0xD185 adjustment encoding (observed from X-H1 / X Raw Studio), the unit is also 10 but expressed as int32 twos-complement: +4=40, +3=30, -0.5=0xFFFFFFFB, -4=0xFFFFFFD8. The filmkit and libfuji encodings are consistent for integer steps.

---

## 2. Compatibility Matrix

This section consolidates per-model facts from fujihack (`etc/models.h`, `models.json`) and supplementary observations from libfuji and the XApp analysis. **All claims are conservative.** Frameport must not claim compatibility with any model not validated against real hardware. The primary v1 target is **Fujifilm X-T5**.

### 2.1 Bluetooth Generation Split

Fujifilm introduced BLE on X-series cameras starting with the 2018 generation (X-T3, X-E3, X-H1). Pre-2018 models have no Bluetooth hardware.

**Confirmed BLE-capable models** (from fujihack matrix, medium confidence):
X-T3, X-T30, X-T4, X-H1, X-E3, GFX 50R, GFX 100, X-A5, X-A7, X-Pro3, X100V, X-T200, XF10.

**Confirmed no BLE** (pre-2018):
X-T2, X-T10, X-T20, X-T1, X100F, X-A1, X-A2, X-E2, X-E2S, X-M1, X-Pro2, X30, X70, GFX 50S.

**X-T5 (Frameport v1 target):** Not present in the fujihack matrix (released 2022, after the dataset). Based on its X-Processor 5 generation and product positioning alongside X-T4, BLE capability is expected but must be confirmed from hardware testing. Do not publish compatibility until validated.

### 2.2 Remote Capture (PTP Shutter) Support

**capture_support = true** (from fujihack models.h, medium confidence):
X-T3, X-T30, X-T4, X-H1, X-E3, GFX 50R, GFX 100, X-A7, X-Pro3, X100V, X-T200, XF10, FinePix XP140/XP141.

**capture_support = false:**
X-T2, X-T10, X-T20, X-T1, X100F, X-A1, X-A2, X-A3, X-A5, X-A10, X-E2, X-E2S, X-M1, X-Pro2, X30, X70, X100T, GFX 50S, X-T100, FinePix XP130.

Cameras without remote capture support will accept a PTP session but will not respond to `InitiateCapture` (0x100E) or related opcodes.

### 2.3 Function Mode and Version Negotiation

Newer cameras (X-S10, X-H1, X-T5 generation) require explicit version negotiation before certain operations:
- Read `GetObjectVersion` (0xDF22), `RemoteGetObjectVersion` (0xDF25), `ImageGetVersion` (0xDF21), `RemoteVersion` (0xDF24) after OpenSession.
- Write each back to the same value (or a negotiated upgrade) to confirm client support.
- Cameras reject image download if this negotiation is skipped.
- `RemoteVersion` 0x2000C corresponds to Camera Connect app 2.11. X-T20 reports 0x20004; X-S10 reports up to 0x2000B.

The `SetFunctionMode` call (XApp analysis) must precede operation-specific commands. Mode codes: IMAGE_RECEIVE=1, REMOTE=5, NEUTRAL20=6, IMAGE_LIVE_VIEW=22. BUSY error 4102 triggers retry (5 passes, 100 ms delay each, up to 3 outer loops).

### 2.4 GetPartialObject Chunk Size

Chunked download via `GetPartialObject` (0x101B) must use chunks of at most **0x100000 bytes (1 MB)**. The X-A2 and some other older models stall permanently with a single large object request. This chunk size is mandatory regardless of total object size.

### 2.5 Wi-Fi Object Handle Numbering

For Wi-Fi transfers, **do not call `GetObjectHandles`** with a storage-id enumeration. Instead:
- Object count comes from `EventsList` (0xD212) property `ObjectCount` (0xD222).
- Object handles are sequential integers **1..N**, not the result of a GetObjectHandles scan.
- In `MULTIPLE_TRANSFER` mode (CameraState=1), the handle is always **1**; the camera advances it after each successful download.

### 2.6 RED-Compliant Camera Variants

The XApp analysis reveals a firmware/model flag `isREDCompliant` that selects alternate GATT service UUID sets. Affected services: Camera Information and Camera Startup Information use `*_RED` UUID variants on compliant models. The condition for RED compliance was not fully traced. Frameport must detect this and select the correct UUID set.

### 2.7 Fujifilm USB Vendor ID

Fujifilm USB Vendor ID: **0x04CB** (decimal 1227). Confirmed by libfuji and filmkit. Used to identify Fujifilm cameras on USB enumeration. UVC webcam guard: if USB device class is 239 / subclass 2 / protocol 1, bypass PTP entirely (webcam mode).

Known USB product IDs (filmkit, X100VI confirmed tested): X-T30=0x02E3, X100V=0x02E5, X-T4=0x02E7, X100VI=0x0305. X-T5 product ID is not in any surveyed source and must be determined from hardware.

---

## 3. Clean-Room Methodology and IP Risk

### 3.1 Per-Project Summary

| Project | Language | License / Caveat | What Frameport should take |
|---------|----------|-------------------|---------------------------|
| **fuji-cam-wifi-tool** (hkr) | C++14 | MIT — open source | Transport framing (4-byte LE length prefix), three-port TCP topology (55740/55741/55742), opcode table (0x100E–0x902E), property code table with ISO and shutter bit-field encodings, film simulation codes 0x01–0x10, two-part property-write protocol, status poll format, live-view 14-byte frame header. All as interoperability facts; no C++ struct definitions copied verbatim. |
| **libfuji** (libpict submodule) | C | Open source (copyright Daniel C / petabyt) — no explicit license found in survey corpus | PTP-IP standard packet type codes, FujiInitPacket layout (82 bytes, magic 0x8f53e4f2), GATT UUID inventory (9 UUIDs for BLE pairing / subscription), BLE pairing flow (token → CHR_PAIR → CHR_IDEN), geolocation BLE payload format, version negotiation sequence, EventsList polling discipline, chunked download requirements, 0xD185 profile size quirks, film simulation codes 0x01–0x10. No C function bodies or struct definitions copied. Verify license before shipping any derived artifact. |
| **fujihack** | C / Python / ARM asm | **GPL v3.0** — copyleft, incompatible with Apache-2.0 | **No code may be copied.** Protocol constants (PTP opcode values, packet header sizes, USB class IDs) are ISO 15740 facts and not copyrightable. Use the compatibility matrix (BLE-capable model list, capture_support flags) as a cross-check only — reconstruct from primary Fujifilm sources before publishing. The `FUJI_HIJACK` opcode 0x9805 and all firmware-internal details must never appear in Frameport. |
| **filmkit** (eggricesoy/filmkit) | TypeScript | License must be reviewed before any implementation decisions (filmkit does not accept pull requests and is listed as read-only reference) | PTP container wire format (12-byte header), complete Fujifilm property code map 0xD001–0xD1A5, film simulation codes 0x01–0x14, grain effect encoding duality, tone ×10 convention and 0x8000 sentinel, HighIsoNR non-linear lookup table, white balance codes, 0xD185 native 625-byte profile layout and field index table, preset slot system (C1–C7, 0xD18C–0xD1A5), write-order sequence from Wireshark capture, RAF upload opcode pair 0x900C/0x900D with ObjectFormat=0xF802 and filename 'FUP_FILE.dat', stale-session recovery pattern. All as numeric facts; no TypeScript ported to Rust. |
| **FUJIFILM XApp 2.7.5** | Kotlin / C++ (reverse-engineered proprietary) | **High IP risk** — proprietary application, no license, reverse-engineered by Ghidra / JADX. See §3.3. | Wire-observable interoperability facts only: three TCP port assignments, PTP-IP packet sizes (82-byte Init_Command_Request, 68-byte Ack), SessionID hardcoded to 1, BUSY retry timing, function mode codes, liveview buffer size and JPEG extraction offset formula (secondaryLen+18), all 14 Fujifilm GATT service UUIDs and ~40 characteristic UUIDs, BLE shutter S0/S1/S2 values, LocationAndSpeed 23-byte payload layout, WifiNetworkSpecifier construction pattern with NET_CAPABILITY_INTERNET removed. Every item must be re-implemented from scratch in Kotlin/Rust. |

### 3.2 What Is Safe to Reimplement

The following categories of facts extracted from the survey are safe to implement in original Frameport code:

**Numeric constants (opcodes, property codes, port numbers):** Numeric identifiers required to interoperate with a camera are not copyrightable. Port numbers 55740/55741/55742, opcode values such as 0x9026, property codes such as 0xD001, and GATT UUIDs are all required wire-level identifiers observable from any packet capture. They have the same legal character as a file format magic number or a network port number.

**Packet layouts and field widths:** The structure of a PTP container (4-byte length, 2-byte type, 2-byte code, 4-byte transaction ID) is mandated by ISO 15740 and the PTP-IP specification (CIPA DC-005-2005). These are specification facts. Fujifilm-specific extensions (e.g. the 82-byte FujiInitPacket with the proprietary version magic 0x8f53e4f2) are interoperability protocol facts observable on the wire; they are not creative expression.

**Protocol sequences and state transitions:** The order of operations (hello → start → mode-neg → capabilities → camera_remote) is a discovered behavioral fact about the camera's state machine. Implementing a compatible sequence does not require copying any source code.

**Encoding rules:** Bit-field encodings such as the ISO bit-flag layout and the HighIsoNR non-linear table are protocol facts. They are discovered by observation (packet capture or behavioral testing), not authored by a developer, and are therefore not copyrightable.

**GATT UUIDs:** Fujifilm uses proprietary 128-bit UUIDs (not Bluetooth SIG assigned short UUIDs). These are observable from any phone connected to a Fujifilm camera using standard Android BluetoothGatt APIs or tools like nRF Connect. Interoperability provisions (EU Software Directive Article 6 and equivalent) support their use; Frameport legal review should confirm applicability in all target jurisdictions.

### 3.3 What Must Never Be Copied

**XApp reverse-engineered code:** The XApp analysis is the highest IP risk source in this corpus. It is a deobfuscated proprietary application produced by Ghidra and JADX decompilation. Frameport must not reproduce any decompiled function body, class hierarchy, variable name, string resource, or business logic from the XApp. The only permissible use is extracting wire-observable numeric constants and protocol behaviors that can be independently confirmed from packet captures.

**Proprietary credentials found in XApp:** AWS Cognito Pool ID, App Client ID, PostHog API key, Firebase API key, and Google Maps API key are embedded in XApp plaintext. These must never appear in Frameport. The camera IP 192.168.0.1 and ports 55740–55742 are the only server-side facts Frameport legitimately uses.

**GPL-licensed code from fujihack:** No function body, struct definition, or any creative expression from the fujihack repository (GPL v3.0) may appear in Frameport (Apache-2.0). This includes the camlib submodule even though it is Apache-2.0 licensed — the contamination risk from the GPL wrapper is sufficient reason to avoid verbatim use.

**Firmware-internal details from fujihack:** The `FUJI_HIJACK` opcode 0x9805, firmware function addresses in `model/*.h`, and the PTP handler calling convention in `src/ff_ptp.h` describe patched firmware internals and have no role in app-layer PTP communication. Frameport must never target opcode 0x9805.

**Proprietary binary assets:** No icons, string resources, layout files, binary libraries, or SDK binaries from the XApp or any Fujifilm distribution may appear in Frameport.

### 3.4 Specific IP Risk of the XApp Reference

The XApp analysis is the most legally sensitive source in this corpus. The risk is managed as follows:

1. **No decompiled source in Frameport.** All Frameport Rust and Kotlin code implementing the PTP-IP session, BLE pairing, and Wi-Fi handoff must be written from scratch. The XApp analysis is used only to confirm numeric constants and to understand behavioral sequences that can also be observed from packet captures.

2. **Independently observable facts.** Every numeric constant extracted from the XApp (port numbers, opcode values, GATT UUIDs, packet sizes, timing values) is also observable from any packet capture of a Fujifilm camera session. The analysis confirms rather than exclusively reveals these facts.

3. **Legal review before shipping.** GATT UUIDs in particular should be reviewed with counsel before Frameport v1 release. Interoperability provisions apply in many jurisdictions but not uniformly.

4. **No clean-room contamination.** Developers who have read the XApp Ghidra pseudo-C output must not author Frameport PTP-IP or BLE protocol code without a formal clean-room separation review.

### 3.5 Registration Magic Header

The 24-byte registration message header `{01 00 00 00 f2 e4 53 8f ad a5 48 5d 87 b2 7f 0b d3 d5 de d0 02 78 a8 c0}` appears in both fuji-cam-wifi-tool and libfuji. It is a fixed protocol-layer identifier analogous to a PTP GUID and has the same character as a port number or magic number: it is required to interoperate with the camera and is not creative expression. It is already publicly documented in multiple MIT-licensed open-source projects. Record it in Frameport as a named constant `FUJI_PTPIP_INIT_MAGIC: [u8; 24]` with a doc comment citing both MIT-licensed sources. Legal confirmation is still recommended before shipping.

---

## 4. Mapping to Frameport Crate Targets

The following table maps the interoperability facts above to the Rust workspace crates defined in `CLAUDE.md`.

| Target Crate | Facts to Use |
|---|---|
| `fuji-ptpip` | Three-port TCP topology, 4-byte LE transport framing, FujiInitPacket layout (82 bytes, magic 0x8f53e4f2), session handshake sequence and timing, close sentinel, transaction ID counter wrapping, lazy event/liveview channel open |
| `fuji-ptp` | Full ISO 15740 opcode table (0x1001–0x101C), response codes, data type codes, property descriptor form types, PTP string / array encoding, DeviceInfo field ordering |
| `fuji-core` | All Fujifilm vendor opcodes (0x9020–0x902E, 0x9060), property code enum (0xD001–0xDF25), ISO / shutter speed / aperture bit-field decoders, CameraState / ClientState enumerations, session state machine, version negotiation sequence, EventsList polling |
| `fuji-transfer` | Chunked download via GetPartialObject (max 1 MB chunks), EnableCorrectFileSize flag protocol, object handle numbering (sequential 1..N for Wi-Fi), MULTIPLE_TRANSFER mode, RAF upload opcode pair (0x900C / 0x900D), ObjectFormat=0xF802, filename 'FUP_FILE.dat' |
| `fuji-liveview` | Port-55742 dedicated socket, 14-byte frame header (bytes 4–7 = uint32 frame counter), JPEG at offset 14, XApp-confirmed formula secondaryLen+18 as cross-check |
| `fuji-ble-protocol` | All 14 GATT service UUIDs, all ~40 characteristic UUIDs, pairing flow (4-byte token → CHR_PAIR → CHR_IDEN), ShootingRequest S0/S1/S2 values, LocationAndSpeed 23-byte LE payload, advertisement filter (company ID 0x04D8, offset 23) |
| `fuji-core` (film-sim module) | Film simulation codes 0x01–0x14, grain duality, tone ×10 with 0x8000 sentinel, HighIsoNR non-linear table, WB codes with uint16/int16 mask, 0xD185 native profile field index table, patch-based profile update strategy |
| `camera/wifi` (Android) | WifiNetworkSpecifier construction, NET_CAPABILITY_INTERNET removal, bindProcessToNetwork before socket creation, 3-retry policy, BLE characteristics for SSID and passphrase |
| `camera/bluetooth` (Android) | GATT operation queue (one in flight), notification subscription groupings per operation, isREDCompliant service UUID variant selection, 5-second per-operation timeout |
| `camera/diagnostics` | CameraState / ClientState typed error mapping, XSDK error codes (BUSY=4102, UNSUPPORTED=4101, TIMEOUT=8194), per-model capability facts |

