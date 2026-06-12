---
name: cargo-workflows
description: Use when managing the Rust workspace, adding/removing crates, editing workspace dependencies, running cargo nextest/audit/deny, configuring Cargo profiles for Android cross-compilation, debugging Cargo.lock churn, migrating crate edition, or wiring Gradle to cargo via the frameport.android.rust-native plugin.
---

# Cargo Workflows -- Frameport

## Project layout

```text
rust/fuji-rs/
  Cargo.toml              # Virtual workspace manifest (97 crates as of 2026-05)
  Cargo.lock              # Checked in -- reproducible builds
  .cargo/config.toml      # Per-target rustflags for Android NDK
  .config/nextest.toml    # nextest profiles (default + ci)
  deny.toml               # cargo-deny policy
  crates/
    fuji-ffi/       # cdylib -- JNI entry point (libframeport.so)
    fuji-ffi/           # cdylib -- JNI bridge (libframeport.so)
    
    
    fuji-cli/           # Host-only CLI binary
    fuji-diagnostics/         # Criterion benchmarks
    ... (34 more library crates)
```

## Android NDK cross-compilation

### How it works (no cargo-ndk)

This project does NOT use `cargo-ndk`. Instead, a custom Gradle convention plugin
(`frameport.android.rust-native`) invokes `cargo build` directly with per-ABI
environment variables pointing to NDK clang linkers and `llvm-ar`.

Key file: `build-logic/convention/src/main/kotlin/frameport.android.rust-native.gradle.kts`

### Target ABIs and Rust triples

| Android ABI     | Rust target                  | Clang target prefix          |
|-----------------|------------------------------|------------------------------|
| arm64-v8a       | aarch64-linux-android        | aarch64-linux-android        |
| armeabi-v7a     | armv7-linux-androideabi      | armv7a-linux-androideabi     |
| x86_64          | x86_64-linux-android         | x86_64-linux-android         |
| x86             | i686-linux-android           | i686-linux-android           |

### Required Rust targets

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi \
    x86_64-linux-android i686-linux-android
```

### Cargo profiles for Android

```toml
# Cargo.toml -- custom profiles
[profile.android-jni]       # Release: opt-level "z", panic = "unwind"
inherits = "release"

[profile.android-jni-dev]   # Dev: opt-level 1, panic = "unwind"
inherits = "dev"
```

The active profile is selected by Gradle property `frameport.nativeCargoProfile`.
Local dev overrides via `frameport.localNativeCargoProfileDefault` and
`frameport.localNativeAbisDefault` in `gradle.properties` or `local.properties`.

### .cargo/config.toml (cross-compilation flags)

All four Android targets share the same rustflags:
- `-C link-arg=-Wl,-z,max-page-size=16384` (Android 15+ 16 KiB page size)
- `-C force-frame-pointers=yes` (profiling / crash symbolication)

Linkers are NOT configured here -- the Gradle task sets `CARGO_TARGET_<TRIPLE>_LINKER`
environment variables pointing to NDK clang at build time.

## Gradle <-> Cargo integration

### Build flow

1. Gradle task `buildRustNativeLibs` (registered by `frameport.android.rust-native` plugin)
2. Runs `cargo build --locked --target <triple> --profile <profile> -p fuji-ffi`
3. Builds all ABIs in parallel (one thread per ABI, capped at CPU count)
4. Each ABI gets its own `CARGO_TARGET_DIR` to avoid lock contention
5. Copies `libframeport_android.so` -> `libframeport.so` (Frameport JNI bridge .so)
6. Output lands in `build/generated/jniLibs/<abi>/` and is wired into Android `jniLibs` source set
7. Task is wired into `merge*JniLibFolders`, `copy*JniLibsProjectOnly`, `merge*NativeLibs`, and `preBuild`

### Building locally

```bash
# Full Android build (builds Rust + Kotlin + APK)
./gradlew :core:engine:assembleDebug

# Rust-only (triggers Gradle's Rust task)
./gradlew :core:engine:buildRustNativeLibs

# Host-only (no Android NDK, for tests/benchmarks)
cd rust/fuji-rs && cargo build -p fuji-cli
cd rust/fuji-rs && cargo bench -p fuji-diagnostics
```

## cdylib crates and JNI considerations

One crate produces the shared library loaded via `System.loadLibrary()`:
- `fuji-ffi` -> `libfuji_ffi.so` (Fujifilm camera JNI bridge)

Key rules for cdylib JNI crates:
- `crate-type = ["cdylib"]` in `[lib]` -- produces `.so` for Android
- `panic = "unwind"` required (not `"abort"`) so JNI can catch panics
- Exported `#[no_mangle] pub extern "system" fn Java_...` entry points
- The `jni` crate (v0.22) provides `JNIEnv`, `JClass`, `JString` wrappers
- Clippy allows `missing_safety_doc` and `not_unsafe_ptr_arg_deref` workspace-wide for JNI/FFI

## Feature flags

```toml
# Example: fuji-ffi
[features]
loom = ["dep:loom"]   # Enable loom for concurrency testing
```

Feature rules:
- Features are additive -- once enabled anywhere in the dep graph, they stay on
- `resolver = "2"` prevents dev-dep features from leaking into regular deps
- Use `dep:optional_dep` syntax to avoid implicit feature creation

## Testing

```bash
# Run all workspace tests with nextest (preferred)
cd rust/fuji-rs && cargo nextest run

# CI profile (retries=2, no fail-fast)
cd rust/fuji-rs && cargo nextest run --profile ci

# Run single crate tests
cd rust/fuji-rs && cargo nextest run -p fuji-ptp

# Standard cargo test (for doc-tests, which nextest skips)
cd rust/fuji-rs && cargo test --doc
```

nextest config at `rust/fuji-rs/.config/nextest.toml`:
- `default` profile: fail-fast=true, slow-timeout=30s
- `ci` profile: retries=2, fail-fast=false

## Dependency auditing

```bash
cd rust/fuji-rs

# Security advisory check
cargo audit

# Full policy check (licenses, bans, advisories, sources)
cargo deny check
```

### deny.toml policy (rust/fuji-rs/deny.toml)

- **Licenses**: MIT, Apache-2.0, BSD-2/3-Clause, ISC, 0BSD, Zlib, Unicode-3.0, OpenSSL allowed
- **Bans**: multiple-versions=warn, wildcards=warn
- **Sources**: unknown registries denied, unknown git warned
- **Advisories**: one explicit ignore — `RUSTSEC-2024-0436` (`paste` proc-macro, unmaintained, no runtime risk). See `rust-security` skill for RUSTSEC triage SLA.

## CI caching

```yaml
# Preferred: Swatinem/rust-cache
- uses: Swatinem/rust-cache@v2
  with:
    cache-on-failure: true
    workspaces: "rust/fuji-rs -> target"

# Manual cache (use v4, not v3)
- uses: actions/cache@v4
  with:
    path: |
      ~/.cargo/registry/index/
      ~/.cargo/registry/cache/
      ~/.cargo/git/db/
      rust/fuji-rs/target/
    key: ${{ runner.os }}-cargo-${{ hashFiles('rust/fuji-rs/Cargo.lock') }}
```

## Workspace commands cheat sheet

```bash
cd rust/fuji-rs

cargo check --workspace                # Type-check all crates
cargo clippy --workspace -- -D warnings # Lint (workspace lints in Cargo.toml)
cargo fmt --check                       # Format check
cargo build -p fuji-cli               # Build single crate
cargo tree --duplicates                 # Find duplicate deps
cargo tree -i serde                     # Who depends on serde?
cargo update -p tokio --precise 1.42.0  # Pin single dep version
cargo deny check                        # Run full deny policy
cargo audit                             # Security advisories only
```

## Rust Edition 2024 migration

Edition 2024 stabilized in Rust 1.85.0 (Feb 2025) — see <https://blog.rust-lang.org/2025/02/20/Rust-1.85.0/>. The workspace is currently on edition 2021 (check `rustfmt.toml:edition` and per-crate `Cargo.toml`). Migrate **one leaf crate at a time** — do not bump the workspace-wide edition in a single commit.

### Per-crate migration workflow

```bash
cd rust/fuji-rs
# Pick a leaf crate (no other workspace crate depends on it, e.g. fuji-cli or fuji-diagnostics).
cd crates/<leaf-crate>
cargo fix --edition
# Inspect diff: cargo fix may edit .rs files in place.
git diff
# Bump the crate's Cargo.toml:
#   edition = "2024"
# Run per-crate lint + test to verify:
cargo clippy -p <leaf-crate> --all-targets -- -D warnings
cargo nextest run -p <leaf-crate>
```

### Breaking changes that bite

- **Stricter `unsafe` in `extern` blocks.** Every item declared in `extern "C" { ... }` or `extern "system" { ... }` now requires explicit `unsafe {}` at the declaration site. `fuji-usb-ptp`, `fuji-ptp`, and the JNI adapter crates must be reviewed carefully. The `#[unsafe(no_mangle)]` syntax (used in JNI entry points) is already 2024-style.
- **`gen` keyword reserved.** Any identifier named `gen` needs renaming before migration. Check for `let gen = …`, `fn gen(…)`, `mod gen`.
- **Precise-capturing `impl Trait`.** Functions returning `impl Trait` that should capture only a subset of input lifetimes now need `use<'a, T>` syntax. Most affected: tokio task spawners in runtime crates that return `impl Future + Send + 'static`. `cargo fix --edition` usually handles this but check the diff.
- **`if let` / `while let` chains stabilize.** No breaking change, but existing nested `if let Some(…) = … { if let Some(…) = … { … } }` patterns can be collapsed to `if let Some(…) = … && let Some(…) = … { … }`. Do not do this in the migration commit — keep the migration diff surgical.
- **`async || {}` closures** are now stable. You don't have to rewrite `|| async { … }` patterns, but new code should prefer the closure form. Do not mass-rewrite — violates `rust-unsafe` surgical-changes discipline.

### Migration order

Leaf crates first (no internal dependents). Suggested order:

1. `fuji-diagnostics`, `fuji-cli` — host-only, low blast radius.
2. `fuji-ptp`, `fuji-ptpip`, `fuji-transfer` — pure-logic crates under `#![forbid(unsafe_code)]`.
3. `fuji-core`, `fuji-liveview`, `fuji-ble-protocol` — cross-crate consumers but still leaf-ish.
4. `fuji-usb-ptp`, `fuji-transfer`, and `fuji-ffi` — larger runtime/platform crates; allocate time for the `extern` block and unsafe-wrapper review.
5. JNI adapter crates (`fuji-ffi`) — last, because they depend on everything else and most affected by the stricter `extern` rules.

### Don't bump rustfmt edition

`rustfmt.toml:edition = "2021"` controls how rustfmt formats code. Bump it only AFTER every crate is on edition 2024 and the workspace builds. Bumping it early re-formats edition-2021 code with edition-2024 rules, producing spurious diffs.

## Feature unification silently enables features in `no_std` crates

**Severity: WARNING**

Cargo resolves features per-package, not per-dependency-edge. With resolver v2 (Rust 2021 edition default), dev-dependency features are isolated from normal dependencies — but normal-dependency features are still unified across the workspace. If any crate in the workspace enables feature `std` on a shared dep, every other workspace crate that depends on that dep gets `std` too, even crates that declare themselves `no_std`.

Concrete hazard in Frameport: a test binary or bench crate depending on `serde` with `derive` feature will enable `derive` for all `serde` users in the workspace. More critically, if a cdylib crate depends on a dep without `std`, but a dev-dep in the workspace pulls in the same dep with `std`, the `no_std` crate silently gains `std` — potentially including `println!`, `Vec` heap allocation, or panicking infrastructure that should be absent.

Detection:
```bash
cd rust/fuji-rs
# Find which crates activate which features on serde/tokio/etc.
cargo tree -f '{p}: {f}' -i serde | grep -v '^$'
cargo tree -f '{p}: {f}' -i tokio | grep -v '^$'

# Check if a pure-logic crate accidentally gets std
cargo check -p fuji-ptp --no-default-features 2>&1 | grep 'std\|alloc'
```

Fix: for crates that must remain `no_std`, add an explicit `default-features = false` on every dep declaration and verify via `cargo check --no-default-features`. If a workspace test binary needs the `std` feature of a dep, consider gating it behind a dev-dep rather than a normal dep.

Reference: [cargo feature unification pitfall — nickb.dev](https://nickb.dev/blog/cargo-workspace-and-the-feature-unification-pitfall/), Cargo Resolver docs.

## Workspace dependency inheritance breaks target-specific features

**Severity: WARNING**

When you define a dependency in `[workspace.dependencies]` and reference it as `foo = { workspace = true }` in a subcrate, Cargo resolver v2 sometimes fails to limit features to the current compilation target. The same dependency declared directly (not via workspace inheritance) works correctly.

This manifests as platform-specific features being enabled on all platforms, potentially breaking `no_std` / `#![forbid(unsafe_code)]` crates on some targets or pulling in unwanted platform code (Windows-only, Unix-only) on cross-compilation targets.

Affected in Frameport: Android NDK cross-compilation targets. If a dep's `unix` or `linux` feature is activated via workspace inheritance and the dep does not correctly gate that feature, the Android build may pull in Linux-specific code not supported by the NDK.

Detection:
```bash
# Compare features seen by a subcrate via workspace vs direct inheritance
cargo tree --target aarch64-linux-android -f '{p}: {f}' -i <dep-name>
```

Workaround: for deps where target-specific features are critical, declare the dep directly in the subcrate's `Cargo.toml` with explicit `target.'cfg(...)'.dependencies` rather than relying on workspace inheritance.

Reference: Cargo issue #11779.

## Related skills

- `rust-debugging` -- GDB/LLDB, async debugging, backtraces
- `rust-security` -- cargo-audit, cargo-deny, supply chain safety
- `rust-performance` -- runtime profiling and build-time tuning
- `rust-unsafe` -- unsafe code review, JNI safety patterns
