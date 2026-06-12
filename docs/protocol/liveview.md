# Live View Protocol

## Purpose

This document defines Frameport's future live-view protocol and rendering pipeline.

Live view is a camera-control workflow where Frameport receives a continuous preview stream from the connected camera and displays it on Android while maintaining control over connection state, cancellation, diagnostics, and privacy.

Live view is different from:

```text
still image import
thumbnail fetch
full object transfer
remote shutter only
USB import
firmware update
cloud sync
```

This document is an engineering/protocol document, not a compatibility guarantee.

## Scope

Covered:

```text
live-view channel setup
live-view frame receiving
frame metadata
compressed frame handling
buffer ownership
backpressure strategy
frame dropping policy
Rust-to-Android frame delivery
Compose/Surface rendering boundary
live-view start/stop lifecycle
diagnostics
performance goals
compatibility validation
```

Not covered:

```text
Wi-Fi socket fd handoff details
Bluetooth LE handoff details
USB PTP details
MediaStore import
full object transfer
firmware update
cloud/account behavior
analytics
iOS behavior
desktop behavior
```

Related documents:

```text
docs/protocol/wifi-ptp-ip.md
docs/protocol/media-transfer.md
docs/protocol/bluetooth-le.md
docs/protocol/usb-ptp.md
docs/protocol/error-model.md
docs/adr/0002-wifi-socket-fd-handoff.md
docs/adr/0006-no-firmware-update-v1.md
```

## Product Status

```text
Status:
  Future / later scope

Live view is not the first production workflow. Frameport v1 focuses on Wi-Fi PTP-IP connection, media enumeration, import, diagnostics, and v1-approved foreground GPS-to-camera EXIF geotagging.
```

Allowed later:

```text
live-view channel validation
continuous preview stream
low-allocation frame pipeline
user-visible start/stop controls
latency and frame-drop diagnostics
remote shutter integration
```

Not allowed without explicit scope update:

```text
hidden background live view
recording live-view frames without user action
raw frame dumps in release diagnostics
cloud upload of frames
claiming live-view support without hardware validation
```

## Architecture Overview

Frameport uses this split:

```text
Android/Kotlin:
  owns user-visible live-view screen
  owns lifecycle state
  owns rendering surface
  owns foreground/user-visible operation state
  owns permission and connection UI
  owns user actions such as start, stop, capture

Rust:
  owns live-view channel protocol
  owns frame receiving
  owns frame metadata parsing
  owns buffer reuse policy
  owns protocol-level live-view errors
  owns live-view diagnostics
```

```text
User opens live-view screen
  ↓
Android requests live-view start through repository/use case
  ↓
Rust validates active camera session
  ↓
Rust opens/uses live-view channel
  ↓
Rust receives compressed frames
  ↓
Rust writes frames into reusable buffers
  ↓
Android receives frame callback or frame handle
  ↓
Android decodes/renders preview
  ↓
User stops live view
  ↓
Rust stops receiver and releases buffers
```

Rust must not own Compose UI or Android rendering lifecycle. Android must not parse camera live-view protocol frames in feature UI code.

## Android/Rust Boundary

Android owns:

```text
live-view screen state
screen lifecycle
rendering surface lifecycle
user start/stop actions
foreground service state if required
frame display and UI composition
preview controls
error presentation
capture button UI
diagnostic presentation
```

Rust owns:

```text
live-view channel read loop
frame packet parsing
frame metadata parsing
compressed frame boundaries
frame buffer allocation/reuse
frame drop policy
protocol timeout handling
camera disconnect detection
typed live-view errors
protocol diagnostics
```

Explicitly forbidden:

```text
Composable directly reading live-view socket
ViewModel directly parsing frame packets
Android UI allocating protocol buffers per frame
Rust calling Compose or Android UI APIs
Rust logging raw frames in release builds
Rust storing live-view frames persistently by default
cloud upload of live-view frames
```

## Live-View Lifecycle

```text
SessionReady
  ↓ user starts live view
PreparingLiveView
  ↓
OpeningLiveViewChannel
  ├── success → LiveViewStarting
  └── failure → Error

LiveViewStarting
  ↓ first frame received
LiveViewRunning
  ├── frame received → RenderLatestFrame
  ├── frame timeout → Recovering or Error
  ├── camera disconnect → Error(CameraDisconnected)
  ├── user capture → RemoteCaptureInteraction
  └── user stops → StoppingLiveView

StoppingLiveView
  ↓
LiveViewStopped
```

Cleanup requirements:

```text
stop receiver loop
release Rust-owned live-view channel resources
release reusable frame buffers
stop callbacks
clear Android rendering state
emit final diagnostic state
```

Cancellation points:

```text
before channel open
during channel open
before first frame
during frame stream
during remote capture interaction
during stop/cleanup
```

User stop is not an error.

## Channel Model

Expected channels:

```text
command channel:
  already active camera session

event channel:
  may be used for camera events later

live-view / through-picture channel:
  later channel used for frame receive
```

Rules:

```text
exact channel semantics must be verified per camera/firmware
live-view channel support must not be assumed from command-session support
channel failure must not corrupt the command session
starting live view must not silently start media import
stopping live view must cleanly release channel state
```

Do not include exact port numbers unless already verified by project-owned docs and safe to include.

| Channel | Purpose | Live-view dependency | Validation requirement |
|---|---|---|---|
| Command | Session and control operations | Required before live view starts | Verify active session before live view |
| Event | Async camera state later | Optional / unknown | Verify per camera/firmware |
| Live-view / through-picture | Preview frames | Required for live-view display | Verify frame format, stop behavior, and error behavior |

## Frame Model

Conceptual frame fields:

```kotlin
data class LiveViewFrame(
    val frameId: Long,
    val capturedAtMonotonicMs: Long,
    val format: LiveViewFrameFormat,
    val width: Int?,
    val height: Int?,
    val sizeBytes: Int,
    val rotation: Int?,
)
```

Possible format categories:

```text
CompressedJpeg
UnknownCompressed
UnknownRaw
Unsupported
```

Rules:

```text
frame bytes are private visual data
frame bytes must not be logged
frame bytes must not be included in release diagnostics
frame metadata must be validated
frame size must be bounded
malformed frames must fail safely
unknown frame format must be represented explicitly
```

Exact frame format and metadata layout must be verified with project-owned tests before support is claimed.

## Buffering and Backpressure

Performance-critical buffer policy:

```text
do not allocate a new large buffer per frame in the hot path
use reusable buffers
use bounded queues
use latest-frame-wins policy when UI cannot keep up
drop stale frames rather than growing memory unbounded
avoid blocking protocol receiver on slow UI rendering
avoid high-frequency tiny JNI calls
throttle diagnostics and progress events
```

Recommended conceptual strategy:

```text
Rust receiver thread/task:
  reads frame from live-view channel
  validates size
  writes compressed frame into reusable buffer
  publishes frame handle or copied compressed frame according to implementation design

Android renderer:
  consumes latest frame
  decodes/renders
  drops stale frames when newer frame exists
```

Backpressure states:

```text
LiveView.BackpressureNormal
LiveView.FrameDropped
LiveView.UiBehind
LiveView.ReceiverBehind
LiveView.BufferPoolExhausted
```

## Rendering Handoff

Possible handoff models:

```text
compressed frame bytes callback
DirectByteBuffer / native buffer handle
frame id + pull API
shared ring buffer with frame handles
```

Exact approach must be chosen during implementation, but must satisfy:

```text
safe ownership
bounded memory
no use-after-free
no UI-thread blocking
no raw frame logging
clear stop semantics
testable frame delivery
```

Android rendering options may include:

```text
ImageBitmap / Bitmap path for first implementation
Surface-based renderer later
hardware-accelerated decode path later
```

Do not require a specific renderer in this document.

## Remote-Control Interaction

Live view may later coexist with:

```text
remote shutter
focus/half-press
basic camera state
limited property changes
capture result state
```

Rules:

```text
live-view and remote control must share a coordinated session state
do not issue concurrent incompatible camera commands
camera busy must be typed
remote capture during live view must be user-visible
capture failure must not crash live view
live-view stop must not trigger capture
```

Full remote-control behavior belongs to a separate protocol/control document if added later.

## Error Model

Live-view channel errors:

```text
LiveView.ChannelUnavailable
LiveView.ChannelOpenFailed
LiveView.ChannelClosed
LiveView.ChannelTimeout
LiveView.ChannelProtocolMismatch
```

Frame errors:

```text
LiveView.FrameTimeout
LiveView.InvalidFrameHeader
LiveView.InvalidFrameLength
LiveView.UnsupportedFrameFormat
LiveView.FrameDecodeFailed
LiveView.FrameDropped
LiveView.BufferPoolExhausted
```

Session/camera errors:

```text
Protocol.SessionNotOpen
Protocol.UnsupportedFunctionMode
Camera.Busy
Camera.Disconnected
Camera.ModeUnsupported
Camera.FirmwareUntested
```

Android/rendering errors:

```text
LiveView.RenderSurfaceUnavailable
LiveView.RendererStopped
LiveView.UiBackpressure
LiveView.DisplayLifecycleStopped
```

User cancellation:

```text
User.CancelledLiveView
User.StoppedLiveView
```

Diagnostics must distinguish:

```text
channel open failure
frame receive failure
frame parse failure
frame decode/render failure
camera disconnect
user stop
unsupported camera/firmware
```

## Diagnostics

Allowed by default:

```text
app version
Android version
Android device model
camera model
firmware version
transport
live-view state
frame format category
frame size bucket
frame rate bucket
frame drop count
latency bucket
operation name
typed error code
elapsed time
retry count
```

Redacted/forbidden by default:

```text
raw frame bytes
raw frame dumps
raw packet bytes
private filenames
EXIF data
precise GPS
full serial number
full MAC address
Wi-Fi passphrase
pairing secret
```

Diagnostic timeline example:

```text
LiveViewStartRequested
CommandSessionReady
LiveViewChannelOpening
LiveViewChannelOpened
FirstFrameReceived
LiveViewRunning
FrameDropped(count=...)
LiveViewStopRequested
LiveViewStopped
```

Failure timeline example:

```text
LiveViewStartRequested
LiveViewChannelOpened
FrameTimeout
Error(LiveView.FrameTimeout)
CleanupStarted
CleanupCompleted
```

## Performance Requirements

Requirements:

```text
bounded memory usage
no unbounded frame queues
no large allocation per frame in hot path
no UI-thread protocol I/O
no JNI call per tiny packet fragment
frame drop policy under load
clean stop within bounded time
battery-aware operation
diagnostic throttling
```

Initial targets should be measured during implementation rather than invented in this document.

Any concrete FPS, latency, CPU, memory, or battery target must be added only after measurement on real Android devices and target cameras.

## Compatibility and Validation

Live-view support must be claimed per:

```text
camera model
camera firmware version
Android device model
Android OS version
Frameport version/commit
transport
live-view frame behavior
duration/stability test
```

A "supported" claim requires:

```text
real hardware test
successful live-view start
first frame received
stable frame display for a defined test duration
clean stop
redacted diagnostic evidence
compatibility matrix update
repeatable behavior
```

Do not claim:

```text
PTP-IP session support implies live-view support
media import support implies live-view support
remote shutter support implies live-view support
support on one firmware implies support on all firmware
support on one camera implies support on all Fujifilm cameras
```

Initial primary camera target remains:

```text
Fujifilm X-T5
```

Live-view support must remain later-scope and feature-by-feature.

## Known Unknowns

Current unknowns:

```text
exact live-view channel semantics per camera/firmware
exact frame format and metadata layout
whether frame stream is JPEG, MJPEG-like, or model-specific
whether frame dimensions are constant during session
whether rotation/orientation is embedded in frame metadata
whether live-view start requires a specific camera mode
whether remote capture interrupts frame stream
whether camera sleep/off behavior differs during live view
whether Android OEM Wi-Fi routing affects live-view stability
whether frame rate differs by camera setting or firmware
whether event channel is required for robust live-view state
```

Unknowns must be resolved with project-owned hardware tests, sanitized traces, and compatibility matrix updates.

## Security and Privacy Rules

```text
Do not log raw live-view frames.
Do not export raw frame dumps in release diagnostics.
Do not upload frames to cloud.
Do not record live-view frames without explicit user action.
Do not start live view in background.
Do not keep hidden live-view sessions running.
Do not log private camera identifiers.
Do not log Wi-Fi passphrases.
Do not include real live-view frames in tests.
```

Live-view frames are private visual data and must be treated like photos/videos.

Live-view diagnostics and protocol traces must follow:

```text
docs/security/diagnostics-redaction.md
docs/security/reverse-engineering-boundary.md
```

## Testing Requirements

Rust unit tests:

```text
frame header parser tests
frame length validation tests
unsupported frame format tests
buffer pool tests
backpressure/drop policy tests
receiver state machine tests
timeout mapping tests
malformed frame tests
```

Rust fake camera tests:

```text
live-view channel open success
channel open failure
first frame success
frame timeout
malformed frame
large frame rejected
connection reset during stream
stop during frame receive
frame drop under load
```

Android unit tests:

```text
ViewModel live-view state transitions
start/stop behavior
user cancellation
renderer unavailable mapping
diagnostic event mapping
foreground/user-visible state if needed
```

Android instrumentation/manual tests:

```text
screen lifecycle start/stop
rotate/background/foreground behavior
renderer cleanup
no UI-thread blocking
memory behavior during preview
stop action behavior
```

Hardware tests:

```text
Fujifilm X-T5 current firmware
live-view start
first frame received
stable preview duration
remote shutter interaction later
camera disconnect during live view
repeat start/stop cycles
Android device/OEM matrix
```

Privacy tests:

```text
no raw frames in release logs
no raw frames in diagnostic bundle
no cloud upload
no persistent frame storage by default
redaction of camera identifiers
```

## Related Documents

```text
README.md
AGENTS.md
CONTRIBUTING.md
SECURITY.md
NOTICE
docs/product/feature-scope.md
docs/security/diagnostics-redaction.md
docs/security/reverse-engineering-boundary.md
docs/protocol/wifi-ptp-ip.md
docs/protocol/bluetooth-le.md
docs/protocol/media-transfer.md
docs/protocol/error-model.md
docs/protocol/compatibility-matrix.md
docs/adr/0001-android-rust-boundary.md
docs/adr/0002-wifi-socket-fd-handoff.md
docs/adr/0003-ble-client-abstraction.md
docs/adr/0006-no-firmware-update-v1.md
```
