//! GOLDEN: three-frame hex-stream test for `LiveViewParser::parse_frame`.
//!
//! Parses a synthetic 3-frame byte stream where each frame:
//!   - Has a valid 18-byte header (4-byte transport prefix + 14-byte FCW).
//!   - Carries a unique FCW frame counter value.
//!   - Contains a JPEG payload starting with SOI 0xFF 0xD8 and ending with EOI 0xFF 0xD9.
//!
//! Assertions (per frame):
//!   - `parse_frame` returns `Ok(&[u8])`.
//!   - Payload byte 0 == 0xFF (JPEG_SOI_BYTE_0).
//!   - Payload byte 1 == 0xD8 (JPEG_SOI_BYTE_1).
//!   - Last two payload bytes == 0xFF 0xD9 (EOI).
//!   - `stats.sequence` increments monotonically (1, 2, 3).
//!   - `last_frame_counter()` reflects the FCW counter field for each frame.
//!   - `stats.drop_count` remains 0 throughout.
//!
//! All bytes are synthetic (constructed in this file); no real camera data.
//!
//! # Given-When-Then naming convention
//!
//! `given_three_frame_stream__when_each_frame_parsed__then_jpeg_markers_present_and_sequence_monotonic`

#![allow(clippy::unwrap_used)]
// Given-When-Then test names use double-underscore separators between the
// three BDD sections.  Clippy's `non_snake_case` lint rejects double
// underscores; suppress it for this file only.
#![allow(non_snake_case)]

use fuji_core::SessionId;
use fuji_liveview::{
    FCW_FRAME_COUNTER_OFFSET, FRAME_HEADER_TOTAL_LEN, JPEG_EOI_BYTE_0, JPEG_EOI_BYTE_1,
    JPEG_SOI_BYTE_0, JPEG_SOI_BYTE_1, LiveViewParser, TRANSPORT_PREFIX_LEN,
};

// ── Fixture helpers ────────────────────────────────────────────────────────────

fn session_id() -> SessionId {
    SessionId::new(42).unwrap()
}

/// Build a well-formed framed packet per transfer-liveview.md §6g (fuji-cam-wifi-tool model).
///
/// Wire layout:
///   [0..4]    transport prefix (u32 LE, inclusive = FRAME_HEADER_TOTAL_LEN + jpeg.len())
///   [4..8]    FCW frame counter (u32 LE) at absolute offset FCW_FRAME_COUNTER_OFFSET (4)
///   [8..18]   remaining FCW header (zeros per §6g "bytes 8-13: zeros")
///   [18..]    JPEG payload
///
/// Source: transfer-liveview.md §6g -- Wireshark filter `data.data[18:2] == ff:d8`.
fn make_frame(jpeg: &[u8], counter: u32) -> Vec<u8> {
    let total = FRAME_HEADER_TOTAL_LEN + jpeg.len();
    let mut buf = vec![0u8; total];
    // Transport prefix (u32 LE, inclusive total length).
    buf[0..TRANSPORT_PREFIX_LEN].copy_from_slice(&(total as u32).to_le_bytes());
    // FCW frame counter at absolute offset 4 (immediately after transport prefix).
    // Source: transfer-liveview.md §6g -- "bytes 4-7: u32 LE frame counter (increments per frame)".
    buf[FCW_FRAME_COUNTER_OFFSET..FCW_FRAME_COUNTER_OFFSET + 4]
        .copy_from_slice(&counter.to_le_bytes());
    // JPEG payload at FRAME_HEADER_TOTAL_LEN (18).
    buf[FRAME_HEADER_TOTAL_LEN..].copy_from_slice(jpeg);
    buf
}

/// A realistic JPEG payload for golden tests: SOI + body + EOI.
///
/// Body bytes are deterministic and non-trivial so that an offset bug is
/// likely to cause a visible assertion failure rather than a silent pass.
fn jpeg_payload(body_len: usize, frame_index: u8) -> Vec<u8> {
    let mut jpeg = vec![frame_index.wrapping_add(1); 2 + body_len + 2];
    // SOI at [0..2] (master-constants.md §6g: JPEG_SOI_BYTE_0, JPEG_SOI_BYTE_1)
    jpeg[0] = JPEG_SOI_BYTE_0;
    jpeg[1] = JPEG_SOI_BYTE_1;
    // EOI at [len-2..len]
    let last = jpeg.len() - 1;
    jpeg[last - 1] = JPEG_EOI_BYTE_0;
    jpeg[last] = JPEG_EOI_BYTE_1;
    jpeg
}

// ── FCW counter values for the 3-frame fixture ────────────────────────────────
//
// These are arbitrary but distinct values that exercise the counter field
// without relying on any specific camera-produced byte sequence.
// Source: transfer-liveview.md §6g -- counter gaps indicate camera-side drops.

const COUNTER_FRAME_1: u32 = 0x0000_0001;
const COUNTER_FRAME_2: u32 = 0x0000_0002;
const COUNTER_FRAME_3: u32 = 0x0000_0003;

// ── Tests ──────────────────────────────────────────────────────────────────────

/// GIVEN a 3-frame byte stream with valid FCW headers and JPEG payloads
/// WHEN each frame is parsed with parse_frame
/// THEN each JPEG slice starts with SOI (0xFF 0xD8), ends with EOI (0xFF 0xD9),
///      sequence increments monotonically (1 → 2 → 3),
///      last_frame_counter() reflects the FCW field of the most-recently parsed frame,
///      and drop_count remains 0.
#[test]
fn given_three_frame_stream__when_each_frame_parsed__then_jpeg_markers_present_and_sequence_monotonic()
 {
    let mut parser = LiveViewParser::new(session_id());

    // ── Frame 1 ───────────────────────────────────────────────────────────────
    // GIVEN a 64-byte JPEG payload for frame 1
    let jpeg1 = jpeg_payload(60, 1);
    let frame1 = make_frame(&jpeg1, COUNTER_FRAME_1);

    // WHEN frame 1 is parsed
    let slice1 = parser
        .parse_frame(&frame1)
        .expect("frame 1 must parse successfully");

    // THEN the returned slice starts with JPEG SOI
    assert_eq!(
        slice1[0], JPEG_SOI_BYTE_0,
        "frame 1: byte 0 must be JPEG_SOI_BYTE_0 (0xFF)"
    );
    assert_eq!(
        slice1[1], JPEG_SOI_BYTE_1,
        "frame 1: byte 1 must be JPEG_SOI_BYTE_1 (0xD8)"
    );
    // THEN the returned slice ends with JPEG EOI
    let last1 = slice1.len() - 1;
    assert_eq!(
        slice1[last1 - 1],
        JPEG_EOI_BYTE_0,
        "frame 1: second-to-last byte must be JPEG_EOI_BYTE_0 (0xFF)"
    );
    assert_eq!(
        slice1[last1], JPEG_EOI_BYTE_1,
        "frame 1: last byte must be JPEG_EOI_BYTE_1 (0xD9)"
    );
    // THEN the slice length equals the JPEG payload length
    assert_eq!(
        slice1.len(),
        jpeg1.len(),
        "frame 1: returned slice length must equal jpeg payload length"
    );
    // THEN sequence is 1 (monotonically incremented from 0)
    assert_eq!(
        parser.stats().sequence,
        1,
        "after frame 1: sequence must be 1"
    );
    // THEN drop_count is still 0
    assert_eq!(
        parser.stats().drop_count,
        0,
        "after frame 1: drop_count must be 0"
    );
    // THEN last_frame_counter reflects the FCW counter of frame 1
    assert_eq!(
        parser.last_frame_counter(),
        Some(COUNTER_FRAME_1),
        "after frame 1: last_frame_counter must equal COUNTER_FRAME_1"
    );
    // THEN last_parse_monotonic is set
    assert!(
        parser.stats().last_parse_monotonic.is_some(),
        "after frame 1: last_parse_monotonic must be Some"
    );

    // ── Frame 2 ───────────────────────────────────────────────────────────────
    // GIVEN a 128-byte JPEG payload for frame 2 (different size to detect offset bugs)
    let jpeg2 = jpeg_payload(124, 2);
    let frame2 = make_frame(&jpeg2, COUNTER_FRAME_2);

    // WHEN frame 2 is parsed
    let slice2 = parser
        .parse_frame(&frame2)
        .expect("frame 2 must parse successfully");

    // THEN the returned slice starts with JPEG SOI
    assert_eq!(
        slice2[0], JPEG_SOI_BYTE_0,
        "frame 2: byte 0 must be JPEG_SOI_BYTE_0 (0xFF)"
    );
    assert_eq!(
        slice2[1], JPEG_SOI_BYTE_1,
        "frame 2: byte 1 must be JPEG_SOI_BYTE_1 (0xD8)"
    );
    // THEN EOI at end
    let last2 = slice2.len() - 1;
    assert_eq!(
        slice2[last2 - 1],
        JPEG_EOI_BYTE_0,
        "frame 2: second-to-last byte must be JPEG_EOI_BYTE_0"
    );
    assert_eq!(
        slice2[last2], JPEG_EOI_BYTE_1,
        "frame 2: last byte must be JPEG_EOI_BYTE_1"
    );
    assert_eq!(
        slice2.len(),
        jpeg2.len(),
        "frame 2: returned slice length must equal jpeg payload length"
    );
    // THEN sequence is 2 (monotonically greater than after frame 1)
    assert_eq!(
        parser.stats().sequence,
        2,
        "after frame 2: sequence must be 2"
    );
    assert_eq!(
        parser.stats().drop_count,
        0,
        "after frame 2: drop_count must still be 0"
    );
    assert_eq!(
        parser.last_frame_counter(),
        Some(COUNTER_FRAME_2),
        "after frame 2: last_frame_counter must equal COUNTER_FRAME_2"
    );

    // ── Frame 3 ───────────────────────────────────────────────────────────────
    // GIVEN a 32-byte JPEG payload for frame 3 (smaller than frame 1 to detect state pollution)
    let jpeg3 = jpeg_payload(28, 3);
    let frame3 = make_frame(&jpeg3, COUNTER_FRAME_3);

    // WHEN frame 3 is parsed
    let slice3 = parser
        .parse_frame(&frame3)
        .expect("frame 3 must parse successfully");

    // THEN the returned slice starts with JPEG SOI
    assert_eq!(
        slice3[0], JPEG_SOI_BYTE_0,
        "frame 3: byte 0 must be JPEG_SOI_BYTE_0 (0xFF)"
    );
    assert_eq!(
        slice3[1], JPEG_SOI_BYTE_1,
        "frame 3: byte 1 must be JPEG_SOI_BYTE_1 (0xD8)"
    );
    let last3 = slice3.len() - 1;
    assert_eq!(
        slice3[last3 - 1],
        JPEG_EOI_BYTE_0,
        "frame 3: second-to-last byte must be JPEG_EOI_BYTE_0"
    );
    assert_eq!(
        slice3[last3], JPEG_EOI_BYTE_1,
        "frame 3: last byte must be JPEG_EOI_BYTE_1"
    );
    assert_eq!(
        slice3.len(),
        jpeg3.len(),
        "frame 3: returned slice length must equal jpeg payload length"
    );
    // THEN sequence is 3 (monotonically greater than after frame 2)
    assert_eq!(
        parser.stats().sequence,
        3,
        "after frame 3: sequence must be 3"
    );
    assert_eq!(
        parser.stats().drop_count,
        0,
        "after frame 3: drop_count must still be 0"
    );
    assert_eq!(
        parser.last_frame_counter(),
        Some(COUNTER_FRAME_3),
        "after frame 3: last_frame_counter must equal COUNTER_FRAME_3"
    );
}

/// GIVEN a stream of frames with non-sequential FCW counters (gap of 5 between each)
/// WHEN each frame is parsed
/// THEN sequence increments by 1 (parser sequence != FCW counter — parser counts
///      successfully delivered frames, not camera-side frame counter gaps).
#[test]
fn given_non_sequential_fcw_counters__when_parsed__then_sequence_increments_by_one_not_by_gap() {
    let mut parser = LiveViewParser::new(session_id());

    // Camera-side counters with a gap of 5 (simulates dropped frames at the camera).
    // Source: transfer-liveview.md §6g -- "Gaps in successive counter values indicate
    //         camera-side frame drops."
    let camera_counters: [u32; 3] = [10, 15, 20];
    let jpeg = jpeg_payload(20, 0);

    for (i, &counter) in camera_counters.iter().enumerate() {
        let frame = make_frame(&jpeg, counter);
        let slice = parser
            .parse_frame(&frame)
            .unwrap_or_else(|e| panic!("frame with counter {counter} must parse: {e}"));

        // Slice must start with SOI
        assert_eq!(slice[0], JPEG_SOI_BYTE_0);
        assert_eq!(slice[1], JPEG_SOI_BYTE_1);

        // Parser sequence reflects frames delivered (1-based), not FCW counter.
        let expected_sequence = (i + 1) as u64;
        assert_eq!(
            parser.stats().sequence,
            expected_sequence,
            "sequence after frame {i}: expected {expected_sequence}, got {}",
            parser.stats().sequence
        );

        // last_frame_counter reflects the camera's FCW counter (not the parser sequence).
        assert_eq!(
            parser.last_frame_counter(),
            Some(counter),
            "last_frame_counter after frame {i} with camera counter {counter}"
        );
    }

    assert_eq!(
        parser.stats().drop_count,
        0,
        "gap in FCW counters must NOT increment drop_count (drop_count tracks FrameTooLarge only)"
    );
}

/// GIVEN a zero-valued FCW frame counter (counter = 0)
/// WHEN parse_frame is called
/// THEN it succeeds, last_frame_counter() == Some(0), sequence == 1.
///
/// Counter value 0 is a valid first frame (camera counter starts at 0 on some firmware).
#[test]
fn given_fcw_counter_zero__when_parsed__then_succeeds_and_counter_is_zero() {
    let mut parser = LiveViewParser::new(session_id());
    let jpeg = jpeg_payload(10, 0);
    let frame = make_frame(&jpeg, 0u32);

    let slice = parser
        .parse_frame(&frame)
        .expect("FCW counter = 0 must be valid");

    assert_eq!(slice[0], JPEG_SOI_BYTE_0);
    assert_eq!(slice[1], JPEG_SOI_BYTE_1);
    assert_eq!(parser.stats().sequence, 1);
    assert_eq!(parser.last_frame_counter(), Some(0u32));
}

/// GIVEN a frame with the maximum u32 FCW counter (0xFFFFFFFF)
/// WHEN parse_frame is called
/// THEN it succeeds, last_frame_counter() == Some(0xFFFFFFFF).
///
/// Counter wrap-around is the camera's concern; the parser must not reject it.
#[test]
fn given_max_u32_fcw_counter__when_parsed__then_succeeds() {
    let mut parser = LiveViewParser::new(session_id());
    let jpeg = jpeg_payload(10, 255);
    let frame = make_frame(&jpeg, u32::MAX);

    let slice = parser
        .parse_frame(&frame)
        .expect("FCW counter = u32::MAX must be valid");

    assert_eq!(slice[0], JPEG_SOI_BYTE_0);
    assert_eq!(slice[1], JPEG_SOI_BYTE_1);
    assert_eq!(parser.last_frame_counter(), Some(u32::MAX));
}
