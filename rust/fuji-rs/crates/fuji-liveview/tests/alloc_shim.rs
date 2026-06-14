//! ALLOC-SHIM: zero-allocation hot-path test for `LiveViewParser::parse_frame`.
//!
//! Installs a counting `#[global_allocator]` wrapper in this test binary only.
//! Uses a thread-local counter so that concurrent tests in the same binary do
//! not pollute each other's measurements.
//!
//! Constructs the parser ONCE (which allocates the 200 KiB ring buffer), warms
//! up the macOS monotonic clock subsystem (which lazily allocates on the first
//! N calls to `Instant::now()`), then calls `parse_frame` 1000 times on a
//! well-formed MJPEG fixture.  Asserts that the number of allocations on the
//! hot path is exactly 0.
//!
//! Frame format (transfer-liveview.md §6g, master-constants.md §1):
//!   [0..4]   4-byte transport prefix (u32 LE, inclusive total length)
//!   [4..18]  14-byte FCW frame control word (counter at bytes 4-7, zeros elsewhere)
//!   [18..]   JPEG payload starting with SOI 0xFF 0xD8
//!
//! # Scope note
//!
//! This test measures ONLY the Rust parser hot path.  The per-frame JVM byte[]
//! creation at the FFI boundary is intentionally excluded per the task spec.
//!
//! # Concurrency isolation
//!
//! Rust's test harness runs tests in the same binary on multiple threads.  A
//! global atomic counter would be polluted by allocations from other tests
//! running concurrently.  We use a `thread_local!` counter instead: each test
//! thread accumulates its own count, so the delta measured within one test is
//! unaffected by other threads.

#![allow(clippy::unwrap_used)]
// Given-When-Then test names use double-underscore separators between the
// three BDD sections.  Clippy's `non_snake_case` lint rejects double
// underscores; suppress it for this file only.
#![allow(non_snake_case)]

use std::alloc::{GlobalAlloc, Layout, System};
use std::cell::Cell;

use fuji_core::SessionId;
use fuji_liveview::{
    FCW_FRAME_COUNTER_OFFSET, FRAME_HEADER_TOTAL_LEN, JPEG_SOI_BYTE_0, JPEG_SOI_BYTE_1,
    LiveViewParser, RING_BUFFER_SIZE, TRANSPORT_PREFIX_LEN,
};

// ── Thread-local counting allocator ───────────────────────────────────────────

/// Wraps `System` and counts every `alloc` call in a thread-local counter.
///
/// Using `Cell<u64>` (thread-local) rather than `AtomicU64` (global) means
/// allocations from concurrently running tests in other threads are invisible
/// to the measurement window in this thread.
struct CountingAllocator;

thread_local! {
    static THREAD_ALLOC_COUNT: Cell<u64> = const { Cell::new(0) };
}

/// Read the current thread-local allocation counter.
fn thread_alloc_count() -> u64 {
    THREAD_ALLOC_COUNT.with(|c| c.get())
}

unsafe impl GlobalAlloc for CountingAllocator {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        THREAD_ALLOC_COUNT.with(|c| c.set(c.get().wrapping_add(1)));
        // SAFETY: delegating to System allocator with the same layout.
        unsafe { System.alloc(layout) }
    }

    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        // SAFETY: delegating to System allocator with the same pointer and layout.
        unsafe { System.dealloc(ptr, layout) }
    }
}

#[global_allocator]
static GLOBAL: CountingAllocator = CountingAllocator;

// ── Fixture helpers ────────────────────────────────────────────────────────────

fn session_id() -> SessionId {
    SessionId::new(1).unwrap()
}

/// Build a well-formed framed packet per transfer-liveview.md §6g.
///
/// Wire layout:
///   [0..4]    transport prefix (u32 LE, inclusive = FRAME_HEADER_TOTAL_LEN + jpeg.len())
///   [4..8]    FCW frame counter (u32 LE) at FCW_FRAME_COUNTER_OFFSET (4)
///   [8..18]   remaining FCW header zeros
///   [18..]    JPEG payload
fn make_frame(jpeg: &[u8], counter: u32) -> Vec<u8> {
    let total = FRAME_HEADER_TOTAL_LEN + jpeg.len();
    let mut buf = vec![0u8; total];
    buf[0..TRANSPORT_PREFIX_LEN].copy_from_slice(&(total as u32).to_le_bytes());
    buf[FCW_FRAME_COUNTER_OFFSET..FCW_FRAME_COUNTER_OFFSET + 4]
        .copy_from_slice(&counter.to_le_bytes());
    buf[FRAME_HEADER_TOTAL_LEN..].copy_from_slice(jpeg);
    buf
}

/// Realistic minimal JPEG fixture: SOI marker + 60 bytes of body + EOI.
fn minimal_jpeg_fixture() -> Vec<u8> {
    let mut jpeg = vec![0xABu8; 64];
    jpeg[0] = JPEG_SOI_BYTE_0; // 0xFF
    jpeg[1] = JPEG_SOI_BYTE_1; // 0xD8
    jpeg[62] = 0xFF;
    jpeg[63] = 0xD9; // EOI
    jpeg
}

// ── Tests ──────────────────────────────────────────────────────────────────────

/// GIVEN a LiveViewParser constructed once
/// WHEN parse_frame is called 1000 times with a well-formed MJPEG fixture
/// THEN the number of heap allocations on the hot path is exactly zero.
///
/// The construction alloc (single Box<[u8; 204800]>) is excluded by snapshotting
/// the thread-local counter after construction and computing the delta.
///
/// Platform warm-up: on macOS, `std::time::Instant::now()` lazily initialises
/// the monotonic clock subsystem (mach_timebase_info, call-site registration)
/// through our allocator shim on the first N calls. We warm up by parsing 256
/// frames on the measurement parser before the snapshot so all lazy platform
/// allocations are exhausted before the measurement window opens.
#[test]
fn given_parser_constructed_once__when_parse_frame_called_1000_times__then_zero_hot_path_allocations()
 {
    const HOT_ITERATIONS: u32 = 1_000;
    const WARMUP_ITERATIONS: u32 = 256;

    let jpeg = minimal_jpeg_fixture();
    let frame = make_frame(&jpeg, 1);

    // Construct the measurement parser — allocates the 200 KiB ring buffer.
    let mut parser = LiveViewParser::new(session_id());

    // WARM-UP: parse WARMUP_ITERATIONS frames and call Instant::now() to
    // exhaust all per-thread lazy platform allocations on this thread before
    // snapshotting the counter. These allocations are NOT parse_frame bugs.
    for _ in 0..WARMUP_ITERATIONS {
        let _ = parser.parse_frame(&frame);
    }
    for _ in 0..64 {
        let _ = std::time::Instant::now();
    }

    // Reset parser stats so the sanity assertion counts only HOT_ITERATIONS.
    // FrameStats fields are pub; resetting is allocation-free.
    parser.stats.sequence = 0;
    parser.stats.drop_count = 0;
    parser.stats.last_parse_monotonic = None;

    // MEASUREMENT SNAPSHOT — thread-local, unaffected by other test threads.
    let alloc_before = thread_alloc_count();

    // HOT PATH: 1000 calls. `frame` is pre-built above the window.
    // Return value is &[u8] into the parser's ring buffer — no allocation.
    for _ in 0..HOT_ITERATIONS {
        let _ = parser.parse_frame(&frame);
    }

    let alloc_after = thread_alloc_count();
    let hot_allocs = alloc_after - alloc_before;

    assert_eq!(
        hot_allocs, 0,
        "parse_frame must not allocate on the hot path; got {hot_allocs} allocations over \
         {HOT_ITERATIONS} iterations (after {WARMUP_ITERATIONS} warm-up frames)"
    );

    // Sanity: parser processed exactly HOT_ITERATIONS frames after reset.
    assert_eq!(
        parser.stats().sequence,
        HOT_ITERATIONS as u64,
        "parser must have processed all {HOT_ITERATIONS} frames"
    );
}

/// GIVEN a ring-buffer-sized frame (exactly RING_BUFFER_SIZE bytes)
/// WHEN parse_frame is called on that frame
/// THEN it succeeds AND zero additional allocations occur.
///
/// This exercises the maximum-capacity boundary without triggering FrameTooLarge.
#[test]
fn given_max_size_frame__when_parse_frame_called__then_accepted_with_zero_allocations() {
    let payload_len = RING_BUFFER_SIZE - FRAME_HEADER_TOTAL_LEN;
    let mut jpeg_payload = vec![0xCDu8; payload_len];
    jpeg_payload[0] = JPEG_SOI_BYTE_0;
    jpeg_payload[1] = JPEG_SOI_BYTE_1;
    let frame = make_frame(&jpeg_payload, 42);

    assert_eq!(
        frame.len(),
        RING_BUFFER_SIZE,
        "fixture must be exactly RING_BUFFER_SIZE bytes"
    );

    let mut parser = LiveViewParser::new(session_id());

    // Warm-up on this thread before measurement.
    for _ in 0..256 {
        // Use a small valid frame for warm-up so we don't stack-allocate
        // a 200 KiB payload repeatedly.
        let small_jpeg = minimal_jpeg_fixture();
        let small_frame = make_frame(&small_jpeg, 0);
        let _ = parser.parse_frame(&small_frame);
    }
    for _ in 0..64 {
        let _ = std::time::Instant::now();
    }
    // Reset stats before measurement.
    parser.stats.sequence = 0;
    parser.stats.drop_count = 0;
    parser.stats.last_parse_monotonic = None;

    let alloc_before = thread_alloc_count();

    let result = parser.parse_frame(&frame);

    let alloc_after = thread_alloc_count();
    let hot_allocs = alloc_after - alloc_before;

    assert!(
        result.is_ok(),
        "max-size frame must be accepted (not FrameTooLarge)"
    );
    assert_eq!(
        hot_allocs, 0,
        "parse_frame on max-size frame must not allocate; got {hot_allocs}"
    );
}
