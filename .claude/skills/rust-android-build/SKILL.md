---
name: rust-android-build
description: Android-specific Rust build, verification, and packaging for Frameport — per-target 16 KiB page alignment, size-optimized release profile, ELF symbol allowlist, .so size budgets, NDK 29 specifics. Use when modifying .cargo/config.toml for Android targets, the workspace [profile.release] / [profile.android-jni] block in rust/fuji-rs/, or when verifying a built .so before release. Also applies to the :native:fuji-rust-android Gradle tasks (cargoBuildRustArm64, cargoBuildRustX86_64, cargoTestRust).
---

# Rust Android Build -- Frameport

## Purpose

Frameport ships 2 primary Android ABIs (arm64-v8a, x86_64 for emulator) cross-compiled from the `rust/fuji-rs/` workspace via a Gradle convention plugin. This skill codifies the build-and-verify discipline: which `rustflags` go where, how to verify 16 KiB alignment per ABI, the ELF symbol allowlist, size budgets per ABI, and the NDK 29 specifics.

## When to consult

- Editing `rust/fuji-rs/.cargo/config.toml` for any `*-linux-android*` target.
- Modifying the `[profile.release]` or `[profile.android-jni]` block in `rust/fuji-rs/Cargo.toml`.
- Auditing a built `libfuji_ffi.so` before release.
- Reviewing a Gradle convention-plugin change in `build-logic/` affecting `:native:fuji-rust-android`.
- Investigating a Play Console rejection citing 16 KiB alignment, native crashes, or symbol issues.
- Modifying the `cargoBuildRustArm64`, `cargoBuildRustX86_64`, or `cargoTestRust` Gradle tasks.

## 16 KiB page-size alignment

### Status quo

Play Store has required 16 KiB-aligned `.so` files for new and updated apps targeting Android 15+ since 1 November 2025. NDK r28+ (Frameport pins NDK r29 = `29.0.14206865`) compiles 16 KiB-aligned by default. `.cargo/config.toml` per-target rustflags reinforce this for `cargo build` invocations that do not go through the Gradle plugin.

### Per-ABI rustflags

`rust/fuji-rs/.cargo/config.toml` should contain:

```toml
[target.aarch64-linux-android]
rustflags = [
    "-C", "link-arg=-Wl,-z,max-page-size=16384",
    "-C", "link-arg=-Wl,-z,common-page-size=16384",
    "-C", "force-frame-pointers=yes",
]

[target.x86_64-linux-android]
rustflags = [
    "-C", "link-arg=-Wl,-z,max-page-size=16384",
    "-C", "link-arg=-Wl,-z,common-page-size=16384",
    "-C", "force-frame-pointers=yes",
]

# 32-bit ABIs do NOT need 16 KiB alignment — kernel uses 4 KiB pages.
[target.armv7-linux-androideabi]
rustflags = ["-C", "force-frame-pointers=yes"]

[target.i686-linux-android]
rustflags = ["-C", "force-frame-pointers=yes"]
```

Putting 16 KiB flags on armv7/i686 is harmless but wastes some space on padding. Pure-Rust crates do not need the explicit `-Wl` flags (NDK lld defaults are correct on r28+), but transitive C dependencies compiled by `cc-rs` honor the rustflags and produce correctly aligned object files.

### Verification per ABI

```bash
NDK_BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$(uname | tr '[:upper:]' '[:lower:]')-x86_64/bin"

# arm64-v8a — Align column must be 0x4000 for all LOAD segments
"$NDK_BIN/llvm-readelf" -lW app/build/intermediates/.../arm64-v8a/libfuji_ffi.so \
  | awk '/LOAD/ {print $NF}' \
  | sort -u
# Expected: 0x4000

# x86_64 — also expects 0x4000
"$NDK_BIN/llvm-readelf" -lW app/build/intermediates/.../x86_64/libfuji_ffi.so \
  | awk '/LOAD/ {print $NF}' | sort -u
# Expected: 0x4000
```

AAB-level verification:

```bash
zipalign -c -P 16 -v 4 app/build/outputs/bundle/release/app-release.aab
# Exit 0 = all .so files inside the bundle are properly aligned at 16 KiB.
```

Pre-release gate: a CI step should fail if `0x4000` is missing from `arm64-v8a` or `x86_64` LOAD segments.

### Common traps

- A transitive C dep that compiles without the `-z` flags. Verify via `llvm-readelf -d libfuji_ffi.so | grep DT_NEEDED` to enumerate, then re-build the dep with explicit `CFLAGS=-Wl,-z,max-page-size=16384`.
- `mmap(addr, size, ...)` calls in vendor C code with `size` not 16 KiB aligned. The kernel rounds up; the C code then assumes its smaller original size. Audit any `mmap` in dependencies.
- A `#define PAGE_SIZE 4096` somewhere in a vendor C dep. NDK r29 explicitly REMOVED `PAGE_SIZE` from `unistd.h` for arm64-v8a/x86_64 to force the audit. If your build fails on `PAGE_SIZE` undefined, that is correct — the C code must call `sysconf(_SC_PAGESIZE)`.

## Size-optimized release profile

Workspace `rust/fuji-rs/Cargo.toml`:

```toml
[profile.android-jni]
inherits = "release"
opt-level = "z"           # size > speed for Android distribution
lto = "fat"
codegen-units = 1
panic = "unwind"          # JNI needs unwind for catch_unwind
strip = "symbols"
debug = false

[profile.android-jni-dev]
inherits = "dev"
opt-level = 1
panic = "unwind"
debug = "line-tables-only"  # symbols for on-device profiling
```

Plus `RUSTFLAGS` at build invocation:

```bash
RUSTFLAGS="-C link-arg=-Wl,--gc-sections -C link-arg=-Wl,--icf=all -C link-arg=-Wl,--exclude-libs,ALL" \
  cargo ndk -t arm64-v8a build --profile android-jni
```

What each flag does:
- `--gc-sections` — dead-code elimination at link time. ~5–10% size reduction.
- `--icf=all` — identical code folding. Multiple identical functions (common with generics post-monomorphization) collapse to one. ~5% reduction.
- `--exclude-libs,ALL` — symbols from static rlibs are NOT exported. Equivalent to `-fvisibility=hidden` for C/C++.

For an additional 20–40% size reduction at the cost of losing panic info:

```bash
RUSTFLAGS="..." cargo +nightly ndk -t arm64-v8a build \
  --profile android-jni \
  -Z build-std=std,panic_abort \
  -Z build-std-features=panic_immediate_abort
```

`panic_immediate_abort` strips the `core::fmt::Arguments` machinery and unwind tables. Keep a separate `release-with-symbols` profile for nightly CI soak (with `panic = "unwind"` and full debug info) so when a crash happens the team has a reproducible binary.

## Gradle integration (:native:fuji-rust-android)

The `:native:fuji-rust-android` Gradle module exposes these tasks:

| Task | ABI | Description |
|------|-----|-------------|
| `cargoBuildRustArm64` | arm64-v8a | Builds `libfuji_ffi.so` for real devices |
| `cargoBuildRustX86_64` | x86_64 | Builds for emulator |
| `cargoTestRust` | host | Runs `cargo test` in the workspace |

The convention plugin sets `CARGO_TARGET_<TRIPLE>_LINKER` to the NDK's clang and `CARGO_TARGET_DIR` per-ABI to avoid lock contention during parallel builds.

Do NOT switch to bare `cargo-ndk` CLI in build scripts without consulting the `cargo-workflows` skill — the Gradle plugin's per-ABI parallelism is faster than cargo-ndk's sequential mode.

## ELF symbol allowlist

The only symbols that should be exported from `libfuji_ffi.so`:

- `JNI_OnLoad` / `JNI_OnUnload`
- `Java_*` (JNI method exports following the JNI naming convention, all under `Java_dev_po4yka_frameport_nativebridge_*`)
- System symbols: `_init`, `_fini`, `__cxa_finalize` (linker-generated)

Verify:

```bash
llvm-objdump -T app/build/intermediates/.../arm64-v8a/libfuji_ffi.so \
  | awk '/ DF / && !/^Java_/ && !/JNI_On/ && !/__cxa/ && !/_init/ && !/_fini/ {print}'
# Expected output: empty
```

Any unexpected symbol is ABI leak — a `pub fn` somewhere in the workspace marked `#[unsafe(no_mangle)]` without the `Java_*` prefix. These leak the Rust ABI to anyone who can `dlopen` your library.

`--exclude-libs,ALL` in rustflags prevents static-rlib symbols from leaking. Apply it.

## .so size budgets

Per-ABI baselines for `libfuji_ffi.so` (includes fuji-core, fuji-ptp, fuji-ptpip, fuji-transfer, fuji-liveview, fuji-ble-protocol, fuji-usb-ptp, fuji-diagnostics):

| ABI | Expected range |
|-----|---------------|
| arm64-v8a | ~3–6 MiB (depending on enabled crates) |
| x86_64 | ~3.5–7 MiB |

Gate: any PR that grows the `.so` by >5% from `ci/baseline-sizes.json` blocks. >1% warns. Update baseline manually in a separate PR with explicit rationale.

Audit a regression:

```bash
cd rust/fuji-rs
cargo bloat --profile android-jni --target aarch64-linux-android --crates -n 30
cargo bloat --profile android-jni --target aarch64-linux-android -n 30   # by function
```

Common culprits:
- A new monomorphized generic explosion. Use the inner-function pattern (see `rust-performance` skill).
- A new transitive dependency. Check `cargo tree -p fuji-ffi` diff.
- Loss of `--icf=all` / `--gc-sections` from RUSTFLAGS.
- LTO regression — verify `lto = "fat"` is still active.

## NDK 29 specifics

NDK r29 (Frameport's pin) changed:

- `PAGE_SIZE` macro removed for arm64-v8a / x86_64 when 16 KiB mode is active. Use `sysconf(_SC_PAGESIZE)`.
- LLVM toolchain bumped. Codegen may shift `.so` size by 1–3% versus r28; rebaseline after any NDK bump.
- Some Binder headers removed (not part of NDK ABI). If a C dep includes `binder.h` from outside NDK, it must vendor the headers or fail.

When bumping NDK in a future PR:
1. Update `rust/fuji-rs/rust-toolchain.toml` if Rust MSRV needs adjusting (NDK r29 supports rustc 1.78+).
2. Rebuild both primary ABIs, run `llvm-readelf -lW` per `.so`, confirm alignment.
3. Re-run size baseline measurement, update `ci/baseline-sizes.json`, separate PR.
4. Run Android tests against the new NDK on API level 31+ (minSdk).

## Related skills

- `cargo-workflows` — workspace structure, Cargo.lock discipline, edition migration.
- `rust-performance` — flamegraphs, cargo-bloat, monomorphization audit.
- `rust-android-jni` — JNI export naming, `EnvUnowned::with_env` pattern.
