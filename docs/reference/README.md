# External-Reference Interop Notes

Clean-room interoperability knowledge distilled from five open-source / reverse-engineering references, mapped to Frameport's Rust crates and Android modules. These documents capture **protocol facts** — packet layouts, handshake sequences, numeric opcodes / property codes / GATT UUIDs, port numbers, and state machines. They are interoperability facts, not protectable expression. **No source code, decompiled output, binary assets, or proprietary Fujifilm credentials are reproduced here.** Frameport implements all functionality as wholly original code; these docs are the specification, not the source.

Primary test target is the **Fujifilm X-T5**. Every behavior is provisional until confirmed against a real device. Mandatory legal review applies before shipping any feature that relies on Fujifilm-proprietary GATT UUIDs or vendor PTP opcodes.

## Documents

### Subsystem syntheses (cross-source, design-facing)

| Doc | Scope | Frameport targets |
|---|---|---|
| [index-and-methodology.md](index-and-methodology.md) | Camera properties & film simulation, conservative compatibility matrix, clean-room methodology & per-source IP/license risk | `fuji-core` (properties), compatibility matrix |
| [ptp-ptpip.md](ptp-ptpip.md) | PTP container wire format, PTP-IP handshake, opcode / property / event code tables, session lifecycle | `fuji-ptp`, `fuji-ptpip` |
| [transfer-liveview.md](transfer-liveview.md) | Object enumeration, thumbnail/full download, fd streaming, chunking, liveview negotiation & MJPEG frame format | `fuji-transfer`, `fuji-liveview`, media import |
| [ble-wifi-discovery.md](ble-wifi-discovery.md) | BLE pairing/bonding, BLE→Wi-Fi handoff state machine, GATT service/characteristic UUIDs, payload layouts, Wi-Fi routing | `fuji-ble-protocol`, `camera/bluetooth`, `camera/wifi` |

### Master lookup table

| Doc | Scope |
|---|---|
| [master-constants.md](master-constants.md) | Consolidated cross-source constants: TCP ports, PTP/Fuji opcodes, device-property codes, event codes, GATT UUIDs, magic headers & packet layouts, plus a conflicts/uncertainty section. **187 facts merged across all 6 sources** — the table to keep open while implementing. |

### Per-source full-fidelity references (`sources/`)

Every protocol fact extracted from each source, grouped by category, with value / detail / source-ref / confidence. Nothing summarized away.

| Doc | Source | Facts |
|---|---|---|
| [sources/fuji-cam-wifi-tool.md](sources/fuji-cam-wifi-tool.md) | hkr/fuji-cam-wifi-tool (C++) | 59 |
| [sources/libfuji.md](sources/libfuji.md) | libfuji + libpict (C) | 63 |
| [sources/fujihack.md](sources/fujihack.md) | fujihack (C) | 70 |
| [sources/filmkit.md](sources/filmkit.md) | filmkit (TypeScript) | 44 |
| [sources/xapp-native-ptpip.md](sources/xapp-native-ptpip.md) | XApp 2.7.5 — native PTP-IP/transfer/liveview | 57 |
| [sources/xapp-ble-handoff.md](sources/xapp-ble-handoff.md) | XApp 2.7.5 — BLE pairing/handoff/discovery | 90 |

These complement — not replace — the authored specifications under `docs/protocol/`. Where a `docs/protocol/*.md` spec exists for the same subsystem, the reference doc is the empirical evidence behind it. Read order for an implementer: `master-constants.md` for the value to use → the subsystem synthesis for how the sequence fits together → the `sources/` doc for the raw evidence and confidence behind a specific constant.

## Source provenance

| Project | Lang | What Frameport takes from it | Caveat |
|---|---|---|---|
| `fuji-cam-wifi-tool` | C++ | Wi-Fi 3-socket topology (55740/55741/55742 @ 192.168.0.1), registration handshake, order-critical init sequence, two-part property-write pattern, remote-capture/focus opcodes, liveview frame header | Wi-Fi only; X-T100-era observations; no BLE/USB |
| `libfuji` (+ `libpict`) | C | Multi-transport PTP: PTP-IP init, generic container format, USB-PTP, BLE, discovery, liveview parsing — broadest coverage | Verify Fuji-specific vs generic-PTP boundaries per crate |
| `fujihack` | C | Camera-side PTP opcode/property cross-check; per-model capability tables (`model/*.h`) | Firmware-internal facts are NOT app-sendable; marked in caveats |
| `filmkit` | TS | Modern PTP container codec; Fujifilm device properties & film-simulation encoding (e.g. 0xD185) | WebUSB transport framing differs from PTP-IP |
| `XApp 2.7.5` | reversed APK | Real official PTP-IP handshake, object-transfer pipeline, liveview, BLE pairing + Wi-Fi handoff | **Highest IP risk** — protocol behavior only; never copy decompiled code; legal review before use |

## How to use these docs

- Treat numeric constants as authoritative only when attested by 2+ independent sources (confidence `[H]` in the PTP doc). Single-source values (`[M]`/`[L]`) need device confirmation.
- Implement against the field tables and step lists — do not transcribe any reference project's code.
- Keep the Android/Rust split: GATT lifecycle, Wi-Fi `Network` binding, and fd ownership stay Android-side; Rust parses/builds payloads and speaks PTP over a provided descriptor.
- When a fact informs a design decision, record it in the relevant `docs/protocol/*.md` spec or a new ADR, citing the reference doc.
