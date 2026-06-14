//! ERROR-PATHS: oversized, truncated, and malformed frame tests for
//! `LiveViewParser::parse_frame` and `run_liveview_loop`.
//!
//! Tests (all synthetic bytes; no real camera data):
//!
//!   OVERSIZED tests:
//!     - Frame with declared length > RING_BUFFER_SIZE → FrameTooLarge + drop_count++.
//!     - Parser does NOT panic, does NOT grow the buffer.
//!     - A valid frame parsed immediately after a FrameTooLarge succeeds.
//!     - Repeated oversized frames accumulate drop_count.
//!
//!   TRUNCATED / EOF tests:
//!     - Empty input slice → UnexpectedEof.
//!     - Input shorter than MIN_FRAME_SIZE → MalformedHeader.
//!     - Transport prefix length mismatches actual byte count → MalformedHeader.
//!     - `run_liveview_loop` on a stream that is cut mid-header → UnexpectedEof.
//!
//!   RECOVERY tests:
//!     - parse_frame can parse a valid frame after receiving FrameTooLarge.
//!     - parser state (sequence, drop_count) is consistent across error/success interleaving.
//!
//! Given-When-Then naming convention used throughout.

#![allow(clippy::unwrap_used)]
// Given-When-Then test names use double-underscore separators between the
// three BDD sections.  Clippy's `non_snake_case` lint rejects double
// underscores; suppress it for this file only.
#![allow(non_snake_case)]

use std::sync::atomic::AtomicBool;

use fuji_core::SessionId;
use fuji_liveview::{
    FCW_FRAME_COUNTER_OFFSET, FRAME_HEADER_TOTAL_LEN, JPEG_SOI_BYTE_0, JPEG_SOI_BYTE_1,
    LiveViewError, LiveViewParser, MIN_FRAME_SIZE, RING_BUFFER_SIZE, TRANSPORT_PREFIX_LEN,
    run_liveview_loop,
};

// ── Fixture helpers ────────────────────────────────────────────────────────────

fn session_id() -> SessionId {
    SessionId::new(1).unwrap()
}

/// Build a well-formed framed packet per transfer-liveview.md §6g.
fn make_frame(jpeg: &[u8], counter: u32) -> Vec<u8> {
    let total = FRAME_HEADER_TOTAL_LEN + jpeg.len();
    let mut buf = vec![0u8; total];
    buf[0..TRANSPORT_PREFIX_LEN].copy_from_slice(&(total as u32).to_le_bytes());
    buf[FCW_FRAME_COUNTER_OFFSET..FCW_FRAME_COUNTER_OFFSET + 4]
        .copy_from_slice(&counter.to_le_bytes());
    buf[FRAME_HEADER_TOTAL_LEN..].copy_from_slice(jpeg);
    buf
}

/// Minimal well-formed JPEG payload: SOI + one body byte + EOI.
fn minimal_jpeg() -> Vec<u8> {
    vec![
        JPEG_SOI_BYTE_0,
        JPEG_SOI_BYTE_1,
        0x00, // body (not validated by parser)
        0xFF,
        0xD9, // EOI
    ]
}

// ── OVERSIZED tests ────────────────────────────────────────────────────────────

/// GIVEN an input whose length is RING_BUFFER_SIZE + 1
/// WHEN parse_frame is called
/// THEN FrameTooLarge is returned and drop_count is incremented to 1.
///
/// The ring buffer must NOT be grown (parser has only one buffer of RING_BUFFER_SIZE).
#[test]
fn given_input_one_byte_over_capacity__when_parse_frame__then_frame_too_large_and_drop_incremented()
{
    let mut parser = LiveViewParser::new(session_id());

    // GIVEN: input is RING_BUFFER_SIZE + 1 bytes (transport prefix declares this length too)
    // We do NOT need a valid frame structure — the size check fires first.
    let oversized = vec![0u8; RING_BUFFER_SIZE + 1];

    // WHEN
    let result = parser.parse_frame(&oversized);

    // THEN
    assert_eq!(
        result,
        Err(LiveViewError::FrameTooLarge),
        "input exceeding RING_BUFFER_SIZE must return FrameTooLarge"
    );
    assert_eq!(
        parser.stats().drop_count,
        1,
        "drop_count must be incremented to 1 after one FrameTooLarge"
    );
    assert_eq!(
        parser.stats().sequence,
        0,
        "sequence must not advance after FrameTooLarge"
    );
    assert!(
        parser.stats().last_parse_monotonic.is_none(),
        "last_parse_monotonic must remain None after FrameTooLarge"
    );
}

/// GIVEN repeated oversized inputs (5 times)
/// WHEN parse_frame is called each time
/// THEN drop_count accumulates to 5 and sequence stays 0.
#[test]
fn given_five_oversized_inputs__when_parse_frame_each_time__then_drop_count_equals_five() {
    let mut parser = LiveViewParser::new(session_id());
    let oversized = vec![0u8; RING_BUFFER_SIZE + 100];

    for i in 1u64..=5 {
        let result = parser.parse_frame(&oversized);
        assert_eq!(
            result,
            Err(LiveViewError::FrameTooLarge),
            "iteration {i}: must return FrameTooLarge"
        );
        assert_eq!(
            parser.stats().drop_count,
            i,
            "iteration {i}: drop_count must equal {i}"
        );
    }

    assert_eq!(
        parser.stats().sequence,
        0,
        "sequence must remain 0 after 5 FrameTooLarge errors"
    );
}

/// GIVEN an oversized frame followed by a valid frame
/// WHEN parse_frame is called for each
/// THEN the oversized frame returns FrameTooLarge (drop_count=1),
///      the valid frame succeeds (sequence=1), and drop_count stays at 1.
///
/// This is the KEY recovery assertion: a FrameTooLarge must not corrupt parser state
/// so that subsequent valid frames are rejected.
#[test]
fn given_oversized_then_valid_frame__when_parsed__then_recovery_succeeds_with_correct_counts() {
    let mut parser = LiveViewParser::new(session_id());

    // GIVEN: oversized (> RING_BUFFER_SIZE)
    let oversized = vec![0u8; RING_BUFFER_SIZE + 1];

    // WHEN: parse oversized
    let r1 = parser.parse_frame(&oversized);

    // THEN: FrameTooLarge, drop_count=1
    assert_eq!(r1, Err(LiveViewError::FrameTooLarge));
    assert_eq!(parser.stats().drop_count, 1);
    assert_eq!(parser.stats().sequence, 0);

    // GIVEN: a valid well-formed frame
    let jpeg = minimal_jpeg();
    let valid_frame = make_frame(&jpeg, 1);

    // WHEN: parse valid frame
    let r2 = parser.parse_frame(&valid_frame);

    // THEN: success, SOI present, sequence=1, drop_count unchanged at 1
    let slice = r2.expect("valid frame after FrameTooLarge must succeed");
    assert_eq!(
        slice[0], JPEG_SOI_BYTE_0,
        "recovered frame: byte 0 must be JPEG SOI"
    );
    assert_eq!(
        slice[1], JPEG_SOI_BYTE_1,
        "recovered frame: byte 1 must be JPEG SOI"
    );
    assert_eq!(
        parser.stats().sequence,
        1,
        "sequence must be 1 after one valid frame"
    );
    assert_eq!(
        parser.stats().drop_count,
        1,
        "drop_count must still be 1 — valid frame must not increment it"
    );
}

// ── TRUNCATED / EOF tests (parse_frame) ───────────────────────────────────────

/// GIVEN an empty byte slice
/// WHEN parse_frame is called
/// THEN UnexpectedEof is returned and parser state is unchanged.
#[test]
fn given_empty_input__when_parse_frame__then_unexpected_eof() {
    let mut parser = LiveViewParser::new(session_id());

    let result = parser.parse_frame(&[]);

    assert_eq!(
        result,
        Err(LiveViewError::UnexpectedEof),
        "empty input must return UnexpectedEof"
    );
    assert_eq!(parser.stats().sequence, 0);
    assert_eq!(parser.stats().drop_count, 0);
    assert!(parser.last_frame_counter().is_none());
}

/// GIVEN an input that is shorter than MIN_FRAME_SIZE (18 bytes header + 2 SOI bytes = 20)
/// WHEN parse_frame is called
/// THEN MalformedHeader is returned.
#[test]
fn given_input_shorter_than_min_frame_size__when_parse_frame__then_malformed_header() {
    let mut parser = LiveViewParser::new(session_id());

    // MIN_FRAME_SIZE = FRAME_HEADER_TOTAL_LEN(18) + 2(SOI) = 20.
    // Use MIN_FRAME_SIZE - 1 to be exactly one byte short.
    let short = vec![0u8; MIN_FRAME_SIZE - 1];

    let result = parser.parse_frame(&short);

    assert_eq!(
        result,
        Err(LiveViewError::MalformedHeader),
        "input shorter than MIN_FRAME_SIZE must return MalformedHeader"
    );
    assert_eq!(parser.stats().sequence, 0);
    assert_eq!(parser.stats().drop_count, 0);
}

/// GIVEN a byte slice of length 1 (single byte)
/// WHEN parse_frame is called
/// THEN MalformedHeader or UnexpectedEof is returned (it must not panic or return Ok).
///
/// The 1-byte case hits the `input.is_empty()` guard after a single byte,
/// but MIN_FRAME_SIZE check fires for most inputs > 0 and < MIN_FRAME_SIZE.
#[test]
fn given_single_byte_input__when_parse_frame__then_error_not_panic() {
    let mut parser = LiveViewParser::new(session_id());
    let single = [0xABu8];

    let result = parser.parse_frame(&single);

    assert!(
        result.is_err(),
        "single-byte input must return an error, not Ok"
    );
    // Must be one of the two expected errors; no panic.
    match result {
        Err(LiveViewError::MalformedHeader) | Err(LiveViewError::UnexpectedEof) => {}
        other => panic!("single-byte input: unexpected result {other:?}"),
    }
}

/// GIVEN a frame where the transport prefix declares a length 10 bytes longer than actual
/// WHEN parse_frame is called
/// THEN MalformedHeader is returned (length mismatch).
#[test]
fn given_transport_prefix_length_mismatch__when_parse_frame__then_malformed_header() {
    let mut parser = LiveViewParser::new(session_id());

    // Build a valid frame first, then corrupt the transport prefix.
    let jpeg = minimal_jpeg();
    let mut frame = make_frame(&jpeg, 7);
    // Overwrite transport prefix with (actual_len + 10).
    let wrong_len = (frame.len() as u32).wrapping_add(10);
    frame[0..4].copy_from_slice(&wrong_len.to_le_bytes());

    let result = parser.parse_frame(&frame);

    assert_eq!(
        result,
        Err(LiveViewError::MalformedHeader),
        "transport prefix length mismatch must return MalformedHeader"
    );
    assert_eq!(parser.stats().sequence, 0);
    assert_eq!(parser.stats().drop_count, 0);
}

/// GIVEN a frame where the transport prefix declares a length 10 bytes shorter than actual
/// WHEN parse_frame is called
/// THEN MalformedHeader is returned.
#[test]
fn given_transport_prefix_too_short__when_parse_frame__then_malformed_header() {
    let mut parser = LiveViewParser::new(session_id());

    let jpeg = minimal_jpeg();
    let mut frame = make_frame(&jpeg, 8);
    // Declare a shorter length than actual (but still >= MIN_FRAME_SIZE).
    let actual = frame.len() as u32;
    let shorter = actual.saturating_sub(5);
    frame[0..4].copy_from_slice(&shorter.to_le_bytes());

    let result = parser.parse_frame(&frame);

    assert_eq!(
        result,
        Err(LiveViewError::MalformedHeader),
        "transport prefix shorter than actual frame must return MalformedHeader"
    );
}

/// GIVEN a frame with a payload that does not start with the JPEG SOI marker
/// WHEN parse_frame is called
/// THEN InvalidJpegMarker is returned and drop_count is NOT incremented.
///
/// InvalidJpegMarker is a recoverable skip; drop_count tracks FrameTooLarge only.
#[test]
fn given_non_soi_payload__when_parse_frame__then_invalid_jpeg_marker_and_drop_count_unchanged() {
    let mut parser = LiveViewParser::new(session_id());

    // Payload starts with 0x00 0x01 — not 0xFF 0xD8.
    let bad_payload = vec![0x00u8, 0x01, 0x02, 0x03, 0x04];
    let frame = make_frame(&bad_payload, 1);

    let result = parser.parse_frame(&frame);

    assert_eq!(
        result,
        Err(LiveViewError::InvalidJpegMarker),
        "payload not starting with 0xFF 0xD8 must return InvalidJpegMarker"
    );
    assert_eq!(
        parser.stats().drop_count,
        0,
        "drop_count must NOT increment for InvalidJpegMarker (only FrameTooLarge increments it)"
    );
    assert_eq!(parser.stats().sequence, 0);
}

// ── TRUNCATED / EOF tests (run_liveview_loop) ─────────────────────────────────

/// GIVEN an empty TCP stream (zero bytes available)
/// WHEN run_liveview_loop is called
/// THEN it returns Err(UnexpectedEof) immediately and delivers zero frames.
#[tokio::test]
async fn given_empty_stream__when_run_liveview_loop__then_unexpected_eof_zero_frames() {
    let mut parser = LiveViewParser::new(session_id());
    let mut stream = std::io::Cursor::new(Vec::<u8>::new());
    let stop = AtomicBool::new(false);
    let mut frame_count = 0usize;

    // WHEN
    let result = run_liveview_loop(&mut parser, &mut stream, |_| frame_count += 1, &stop).await;

    // THEN
    assert_eq!(
        result,
        Err(LiveViewError::UnexpectedEof),
        "empty stream must return UnexpectedEof"
    );
    assert_eq!(
        frame_count, 0,
        "no frames must be delivered from empty stream"
    );
    assert_eq!(parser.stats().sequence, 0);
}

/// GIVEN a stream that is truncated mid-header (only 2 bytes available)
/// WHEN run_liveview_loop is called
/// THEN it returns Err(UnexpectedEof) and delivers zero frames.
///
/// The loop reads a 4-byte transport prefix first; with only 2 bytes
/// available, `read_exact` fails with EOF, which maps to UnexpectedEof.
#[tokio::test]
async fn given_stream_truncated_mid_header__when_run_liveview_loop__then_unexpected_eof() {
    let mut parser = LiveViewParser::new(session_id());
    // Only 2 bytes — not enough to complete the 4-byte prefix read.
    let mut stream = std::io::Cursor::new(vec![0xAAu8, 0xBB]);
    let stop = AtomicBool::new(false);
    let mut frame_count = 0usize;

    let result = run_liveview_loop(&mut parser, &mut stream, |_| frame_count += 1, &stop).await;

    assert_eq!(
        result,
        Err(LiveViewError::UnexpectedEof),
        "stream truncated mid-header must return UnexpectedEof"
    );
    assert_eq!(frame_count, 0);
}

/// GIVEN a stream with a valid 4-byte prefix declaring length > RING_BUFFER_SIZE
/// WHEN run_liveview_loop reads it
/// THEN it returns Err(FrameTooLarge) and increments drop_count.
///
/// The loop validates declared_len before attempting to read the body, so
/// it does NOT try to allocate an oversized buffer.
#[tokio::test]
async fn given_stream_with_oversized_declared_len__when_loop__then_frame_too_large() {
    let mut parser = LiveViewParser::new(session_id());

    // Write a 4-byte prefix claiming a length of RING_BUFFER_SIZE + 1.
    let declared: u32 = (RING_BUFFER_SIZE + 1) as u32;
    let mut buf = declared.to_le_bytes().to_vec();
    // Pad with some extra bytes (the loop won't read them — it returns early).
    buf.extend_from_slice(&[0u8; 32]);
    let mut stream = std::io::Cursor::new(buf);
    let stop = AtomicBool::new(false);
    let mut frame_count = 0usize;

    let result = run_liveview_loop(&mut parser, &mut stream, |_| frame_count += 1, &stop).await;

    assert_eq!(
        result,
        Err(LiveViewError::FrameTooLarge),
        "declared_len > RING_BUFFER_SIZE must return FrameTooLarge from the loop"
    );
    assert_eq!(frame_count, 0);
    assert_eq!(
        parser.stats().drop_count,
        1,
        "drop_count must be incremented to 1 by the loop's FrameTooLarge path"
    );
}

/// GIVEN a stream with a valid 4-byte prefix declaring a length below MIN_FRAME_SIZE
/// WHEN run_liveview_loop reads it
/// THEN it returns Err(MalformedHeader) immediately.
///
/// MIN_FRAME_SIZE = 20; if the declared length is, say, 4 (just the prefix itself),
/// the loop cannot have a valid FCW header and must reject it.
#[tokio::test]
async fn given_stream_with_undersized_declared_len__when_loop__then_malformed_header() {
    let mut parser = LiveViewParser::new(session_id());

    // Declare only 4 bytes (the prefix length itself — no room for FCW header).
    let declared: u32 = TRANSPORT_PREFIX_LEN as u32;
    let mut buf = declared.to_le_bytes().to_vec();
    buf.extend_from_slice(&[0u8; 20]);
    let mut stream = std::io::Cursor::new(buf);
    let stop = AtomicBool::new(false);

    let result = run_liveview_loop(&mut parser, &mut stream, |_| {}, &stop).await;

    assert_eq!(
        result,
        Err(LiveViewError::MalformedHeader),
        "declared_len < MIN_FRAME_SIZE must return MalformedHeader"
    );
    assert_eq!(parser.stats().drop_count, 0);
}

/// GIVEN a valid 2-frame stream followed by a truncated frame (body bytes missing)
/// WHEN run_liveview_loop is called
/// THEN both valid frames are delivered, then UnexpectedEof is returned.
///
/// This tests that the loop correctly delivers all complete frames before
/// failing on the truncated tail.
#[tokio::test]
async fn given_two_valid_frames_then_truncated__when_loop__then_two_delivered_then_eof() {
    let jpeg = minimal_jpeg();
    let frame1 = make_frame(&jpeg, 1);
    let frame2 = make_frame(&jpeg, 2);

    // Build a truncated third "frame": only the 4-byte prefix, body missing.
    let declared: u32 = (FRAME_HEADER_TOTAL_LEN + jpeg.len()) as u32;
    let truncated_prefix = declared.to_le_bytes();

    let mut buf = Vec::new();
    buf.extend_from_slice(&frame1);
    buf.extend_from_slice(&frame2);
    buf.extend_from_slice(&truncated_prefix); // only prefix, no body

    let mut stream = std::io::Cursor::new(buf);
    let stop = AtomicBool::new(false);
    let mut parser = LiveViewParser::new(session_id());
    let mut delivered = 0usize;

    let result = run_liveview_loop(&mut parser, &mut stream, |_| delivered += 1, &stop).await;

    assert_eq!(
        result,
        Err(LiveViewError::UnexpectedEof),
        "truncated tail must return UnexpectedEof"
    );
    assert_eq!(
        delivered, 2,
        "exactly 2 complete frames must have been delivered before truncation"
    );
    assert_eq!(parser.stats().sequence, 2);
}

/// GIVEN a stop signal already set to true
/// WHEN run_liveview_loop is called with any non-empty stream
/// THEN it returns Ok(()) immediately without reading any frame.
#[tokio::test]
async fn given_stop_signal_preset__when_loop__then_exits_ok_immediately() {
    let jpeg = minimal_jpeg();
    let frames: Vec<_> = (1u32..=10).map(|c| make_frame(&jpeg, c)).collect();
    let mut buf = Vec::new();
    for f in &frames {
        buf.extend_from_slice(f);
    }
    let mut stream = std::io::Cursor::new(buf);
    let stop = AtomicBool::new(true); // pre-set stop
    let mut parser = LiveViewParser::new(session_id());
    let mut delivered = 0usize;

    let result = run_liveview_loop(&mut parser, &mut stream, |_| delivered += 1, &stop).await;

    assert_eq!(result, Ok(()), "pre-set stop must return Ok(())");
    assert_eq!(
        delivered, 0,
        "no frames must be delivered when stop is pre-set"
    );
    assert_eq!(parser.stats().sequence, 0);
}

// ── CONSISTENCY / INVARIANT tests ─────────────────────────────────────────────

/// GIVEN interleaved valid and invalid frames (valid, oversized, valid, bad-SOI, valid)
/// WHEN parse_frame is called for each
/// THEN sequence increments only for valid frames, drop_count increments only for FrameTooLarge.
///
/// Pattern: V(1) O(drop) V(2) B(skip) V(3)
///   where V = valid, O = oversized (FrameTooLarge), B = bad SOI (InvalidJpegMarker)
#[test]
fn given_interleaved_valid_and_invalid__when_parsed__then_counts_are_consistent() {
    let mut parser = LiveViewParser::new(session_id());
    let jpeg = minimal_jpeg();

    // V: valid frame 1
    let frame_v1 = make_frame(&jpeg, 1);
    assert!(parser.parse_frame(&frame_v1).is_ok(), "V1 must succeed");
    assert_eq!(parser.stats().sequence, 1, "after V1: sequence=1");
    assert_eq!(parser.stats().drop_count, 0, "after V1: drop_count=0");

    // O: oversized frame → FrameTooLarge, drop_count++
    let oversized = vec![0u8; RING_BUFFER_SIZE + 1];
    assert_eq!(
        parser.parse_frame(&oversized),
        Err(LiveViewError::FrameTooLarge),
        "O: must be FrameTooLarge"
    );
    assert_eq!(parser.stats().sequence, 1, "after O: sequence still 1");
    assert_eq!(parser.stats().drop_count, 1, "after O: drop_count=1");

    // V: valid frame 2
    let frame_v2 = make_frame(&jpeg, 2);
    assert!(parser.parse_frame(&frame_v2).is_ok(), "V2 must succeed");
    assert_eq!(parser.stats().sequence, 2, "after V2: sequence=2");
    assert_eq!(parser.stats().drop_count, 1, "after V2: drop_count still 1");

    // B: bad SOI → InvalidJpegMarker, drop_count unchanged
    let bad_payload = vec![0x00u8, 0x01, 0x02, 0x03, 0x04];
    let frame_bad = make_frame(&bad_payload, 3);
    assert_eq!(
        parser.parse_frame(&frame_bad),
        Err(LiveViewError::InvalidJpegMarker),
        "B: must be InvalidJpegMarker"
    );
    assert_eq!(parser.stats().sequence, 2, "after B: sequence still 2");
    assert_eq!(parser.stats().drop_count, 1, "after B: drop_count still 1");

    // V: valid frame 3
    let frame_v3 = make_frame(&jpeg, 4);
    let slice_v3 = parser.parse_frame(&frame_v3).expect("V3 must succeed");
    assert_eq!(slice_v3[0], JPEG_SOI_BYTE_0);
    assert_eq!(slice_v3[1], JPEG_SOI_BYTE_1);
    assert_eq!(parser.stats().sequence, 3, "after V3: sequence=3");
    assert_eq!(
        parser.stats().drop_count,
        1,
        "after V3: drop_count remains 1 (only the one FrameTooLarge)"
    );
}
