//! [`FakeCameraBuilder`] — fluent builder for configuring a [`crate::server::FakeCameraServer`].

// ── Storage constant ──────────────────────────────────────────────────────────

/// Arbitrary StorageID used in GetObjectInfo responses.
///
/// PTP-IP Wi-Fi mode uses a single logical storage; the exact value is not
/// significant to the client for import purposes.
pub const SIM_STORAGE_ID: u32 = 0x0001_0001;

// ── Fault injection configuration ────────────────────────────────────────────

/// Fault injection configuration for object-transfer operations.
///
/// All faults are disabled by default (`None` / `false`).
///
/// # Supported fault scenarios
///
/// | Fault | Trigger | Client-observable effect |
/// |-------|---------|--------------------------|
/// | `size_mismatch_handle` | GetObject for the named handle | `ObjectInfo.object_compressed_size` reports the real `object_bytes.len()`, but the server streams `advertised_size` bytes in `StartData.total_data_length` (or vice-versa: the fault is that `ObjectInfo.compressed_size != actual_bytes_len`) |
/// | `malformed_object_info_handle` | GetObjectInfo for the named handle | Server sends a 4-byte EndData payload (too short to decode) |
/// | `connection_reset_after_bytes` | GetObject for the named handle | Server drops the TCP connection after streaming `k` bytes of the object body |
#[derive(Debug, Clone, Default)]
pub struct FaultConfig {
    /// When `Some(handle)`, the `GetObjectInfo` response for that handle sends a
    /// 4-byte payload (too short to be a valid ObjectInfo) so the decoder returns
    /// `PtpCodecError::PacketTooShort`.
    pub malformed_object_info_handle: Option<u32>,

    /// When `Some((handle, advertised_size))`, the `GetObject` response for `handle`
    /// reports `advertised_size` in `StartData.total_data_length` and in
    /// `ObjectInfo.object_compressed_size`, but the actual `object_bytes` stored in
    /// the object store have a different length — creating a size mismatch the client
    /// must detect after streaming.
    ///
    /// This fault is injected at the builder level by setting
    /// `object_compressed_size` in the `SimObject` to a value that does NOT equal
    /// `object_bytes.len()`.  The sim streams the real `object_bytes` bytes but
    /// advertises the stored `object_compressed_size` in ObjectInfo.  No extra field
    /// needed here — just store the mismatch in the `SimObject` directly.
    pub size_mismatch_handle: Option<u32>,

    /// When `Some((handle, k))`, the `GetObject` response for `handle` closes the
    /// TCP connection after writing exactly `k` bytes of object data (simulating a
    /// mid-transfer TCP RST / camera disconnect).
    pub connection_reset_after_bytes: Option<(u32, usize)>,
}

// ── Object store entry ────────────────────────────────────────────────────────

/// A single object in the fake camera's object store.
///
/// Handles are assigned sequentially starting at 1 by the builder when you call
/// [`FakeCameraBuilder::add_object`].  The handle is determined by insertion order
/// (first added → handle 1, second → handle 2, …).
///
/// `object_compressed_size` is stored separately from `object_bytes.len()` to
/// support the size-mismatch fault scenario: set `object_compressed_size` to a
/// value different from `object_bytes.len()` and the sim will advertise the wrong
/// size in `ObjectInfo` while streaming the real bytes.
#[derive(Debug, Clone)]
pub struct SimObject {
    /// PTP object format code (use `fuji_ptp::constants::object_format` constants).
    pub format_code: u16,
    /// Size advertised in `ObjectInfo.object_compressed_size`.  May differ from
    /// `object_bytes.len()` to simulate a size-mismatch fault.
    pub compressed_size: u32,
    /// Original filename on the camera (used only in `ObjectInfo.filename`; the
    /// raw string is acceptable here because the sim is a test fixture, not
    /// production code — privacy rules apply to production paths only).
    pub filename: String,
    /// ISO 8601 capture-date string (e.g. `"20241225T120000"`).
    pub capture_date: String,
    /// Raw object bytes served by `GetObject`.
    pub object_bytes: Vec<u8>,
    /// Raw thumbnail bytes served by `GetThumb`.
    pub thumb_bytes: Vec<u8>,
}

// ── ServerConfig ──────────────────────────────────────────────────────────────

/// Configuration produced by [`FakeCameraBuilder`] and consumed by the server.
///
/// All fields have safe defaults: zero busy retries, one object, a placeholder version,
/// and no fatal-on-set-mode override.
#[derive(Debug, Clone)]
pub struct ServerConfig {
    /// Number of BUSY (0x2019) responses to emit on SetDevicePropValue-mode before OK.
    ///
    /// Models the Fujifilm retry loop documented in ptp-ptpip.md section 4.3:
    /// client retries up to `MAX_BUSY_RETRIES` times with `RETRY_DELAY_MS` delay.
    pub busy_count: u32,

    /// Number of Wi-Fi-accessible objects to report via ObjectCount (0xD222).
    ///
    /// When the object store is populated via [`FakeCameraBuilder::add_object`] this
    /// is automatically set to `objects.len()`.  If you use [`FakeCameraBuilder::object_count`]
    /// without adding objects, the server will respond to `GetObjectInfo` /
    /// `GetObject` / `GetThumb` with `INVALID_OBJECT_HANDLE`.
    pub object_count: u32,

    /// Fake firmware version word returned by GetDevicePropValue(IMAGE_GET_VERSION).
    ///
    /// The X-T20 reports `0x20004` per master-constants.md §3b.
    pub firmware_version_word: u32,

    /// When `Some(code)`, the server returns that status code instead of BUSY/OK on
    /// SetDevicePropValue-mode.  Intended for fatal-scenario tests:
    ///   - `response_code::FATAL_AUTH_FAILURE`  (0x201D)
    ///   - `response_code::SESSION_ALREADY_OPEN` (0x201E)
    ///   - `response_code::FATAL_GENERIC_FAILURE` (0x2000)
    ///
    /// When `None` the normal busy_count → OK sequence runs.
    pub fatal_on_set_mode: Option<u16>,

    /// Fake camera GUID returned in InitCommandAck (128 bits).
    pub camera_guid: [u8; 16],

    /// Camera model name returned in InitCommandAck (max 21 visible UTF-16 chars).
    pub camera_name: String,

    /// Object store: objects indexed 0-based; handle = index + 1.
    ///
    /// Populated via [`FakeCameraBuilder::add_object`].
    pub objects: Vec<SimObject>,

    /// Fault injection configuration.
    pub faults: FaultConfig,
}

impl Default for ServerConfig {
    fn default() -> Self {
        Self {
            busy_count: 0,
            object_count: 3,
            // X-T20 observed value: 0x20004 (master-constants.md §3b, prop 0xDF21)
            firmware_version_word: 0x0002_0004,
            fatal_on_set_mode: None,
            camera_guid: [
                0xFA, 0x4E, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x00, 0x01,
            ],
            camera_name: "Fujifilm X-T5 (sim)".to_owned(),
            objects: Vec::new(),
            faults: FaultConfig::default(),
        }
    }
}

impl ServerConfig {
    /// Look up an object by 1-based PTP handle.
    ///
    /// Returns `None` if `handle` is 0 or out of range.
    // cancel-safe: synchronous — no .await points.
    pub fn object_by_handle(&self, handle: u32) -> Option<&SimObject> {
        if handle == 0 {
            return None;
        }
        self.objects.get((handle - 1) as usize)
    }
}

// ── FakeCameraBuilder ─────────────────────────────────────────────────────────

/// Fluent builder for the fake camera server.
///
/// # Example
///
/// ```rust
/// use fuji_sim::FakeCameraBuilder;
///
/// let config = FakeCameraBuilder::new()
///     .busy_count(2)
///     .object_count(5)
///     .firmware_version(0x0002_0004)
///     .build();
/// ```
#[derive(Debug, Default)]
pub struct FakeCameraBuilder {
    config: ServerConfig,
}

impl FakeCameraBuilder {
    /// Creates a builder with default configuration.
    pub fn new() -> Self {
        Self {
            config: ServerConfig::default(),
        }
    }

    /// Sets the number of BUSY responses emitted before OK on the mode write.
    ///
    /// The client-side retry policy is `MAX_BUSY_RETRIES = 5` retries with
    /// `RETRY_DELAY_MS = 500` ms delay (ptp-ptpip.md section 4.3).
    pub fn busy_count(mut self, count: u32) -> Self {
        self.config.busy_count = count;
        self
    }

    /// Sets the number of media objects the server will report via `ObjectCount`.
    ///
    /// If you populate the object store with [`add_object`](Self::add_object), this
    /// value is updated automatically; calling `object_count` after `add_object` will
    /// override that automatic value.
    pub fn object_count(mut self, count: u32) -> Self {
        self.config.object_count = count;
        self
    }

    /// Sets the firmware version word returned for GetDevicePropValue(IMAGE_GET_VERSION).
    pub fn firmware_version(mut self, version_word: u32) -> Self {
        self.config.firmware_version_word = version_word;
        self
    }

    /// Configures the server to return a fatal status code instead of BUSY/OK on
    /// SetDevicePropValue-mode (the "SetFunctionMode" step).
    ///
    /// Pass `None` to disable (default). Pass `Some(code)` to enable, e.g.:
    /// ```rust
    /// use fuji_ptp::constants::response_code;
    /// use fuji_sim::FakeCameraBuilder;
    ///
    /// let config = FakeCameraBuilder::new()
    ///     .fatal_on_set_mode(Some(response_code::FATAL_AUTH_FAILURE))
    ///     .build();
    /// ```
    pub fn fatal_on_set_mode(mut self, code: Option<u16>) -> Self {
        self.config.fatal_on_set_mode = code;
        self
    }

    /// Sets the 128-bit camera GUID returned in `InitCommandAck`.
    pub fn camera_guid(mut self, guid: [u8; 16]) -> Self {
        self.config.camera_guid = guid;
        self
    }

    /// Sets the camera model name returned in `InitCommandAck` (max 21 chars).
    pub fn camera_name(mut self, name: impl Into<String>) -> Self {
        self.config.camera_name = name.into();
        self
    }

    // ── Object store ──────────────────────────────────────────────────────────

    /// Adds one object to the fake camera's object store.
    ///
    /// Objects are assigned sequential 1-based handles in insertion order.
    /// The first call assigns handle 1, the second handle 2, and so on.
    ///
    /// `object_count` is updated automatically to `objects.len()`.
    ///
    /// # Parameters
    ///
    /// - `format_code` — PTP format code (use `fuji_ptp::constants::object_format`).
    /// - `compressed_size` — size advertised in `ObjectInfo.object_compressed_size`;
    ///   may differ from `object_bytes.len()` to test size-mismatch detection.
    /// - `filename` — original filename string (for `ObjectInfo.filename`).
    /// - `capture_date` — ISO 8601 string (for `ObjectInfo.capture_date`).
    /// - `object_bytes` — full object payload served by `GetObject`.
    /// - `thumb_bytes` — thumbnail payload served by `GetThumb`.
    pub fn add_object(
        mut self,
        format_code: u16,
        compressed_size: u32,
        filename: impl Into<String>,
        capture_date: impl Into<String>,
        object_bytes: Vec<u8>,
        thumb_bytes: Vec<u8>,
    ) -> Self {
        self.config.objects.push(SimObject {
            format_code,
            compressed_size,
            filename: filename.into(),
            capture_date: capture_date.into(),
            object_bytes,
            thumb_bytes,
        });
        // Keep object_count in sync with the object store length.
        self.config.object_count = self.config.objects.len() as u32;
        self
    }

    // ── Fault injection ───────────────────────────────────────────────────────

    /// Fault: advertise `compressed_size` in `ObjectInfo` that does NOT match
    /// `object_bytes.len()` for the given handle.
    ///
    /// Use [`add_object`](Self::add_object) with a `compressed_size` that differs
    /// from `object_bytes.len()` to set up the data, then call this method to
    /// register the handle so the server knows to send that mismatch.
    ///
    /// The sim streams the real `object_bytes` but `ObjectInfo.object_compressed_size`
    /// is whatever `SimObject.compressed_size` was set to.  No separate state is
    /// needed — just ensure `compressed_size != object_bytes.len()` in the stored
    /// `SimObject`.  This builder method records `handle` in `FaultConfig` so
    /// dispatch can apply different streaming behaviour if desired.
    pub fn with_size_mismatch_fault(mut self, handle: u32) -> Self {
        self.config.faults.size_mismatch_handle = Some(handle);
        self
    }

    /// Fault: respond to `GetObjectInfo(handle)` with a 4-byte payload that is too
    /// short to be a valid ObjectInfo, causing the decoder to return
    /// `PtpCodecError::PacketTooShort`.
    pub fn with_malformed_object_info_fault(mut self, handle: u32) -> Self {
        self.config.faults.malformed_object_info_handle = Some(handle);
        self
    }

    /// Fault: drop the TCP connection after writing `k` bytes of the object body
    /// during `GetObject(handle)`, simulating a mid-transfer camera disconnect
    /// (TCP RST).
    pub fn with_connection_reset_fault(mut self, handle: u32, after_bytes: usize) -> Self {
        self.config.faults.connection_reset_after_bytes = Some((handle, after_bytes));
        self
    }

    /// Finalises the configuration.
    pub fn build(self) -> ServerConfig {
        self.config
    }
}
