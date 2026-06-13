//! [`FakeCameraBuilder`] — fluent builder for configuring a [`crate::server::FakeCameraServer`].

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
        }
    }
}

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

    /// Sets the number of media objects the server will report.
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

    /// Finalises the configuration.
    pub fn build(self) -> ServerConfig {
        self.config
    }
}
