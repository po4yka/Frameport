# Frameport Workspace Patterns Reference

## Workspace dependency management

All dependency versions are centralized in the root `rust/fuji-rs/Cargo.toml`:

```toml
[workspace.dependencies]
# Internal crates use path deps
fuji-ptp = { path = "crates/fuji-ptp" }

# External crates pinned to compatible ranges
jni = "0.22"
tokio = { version = "1", default-features = false }
serde = { version = "1", features = ["derive"] }
```

Members inherit with `workspace = true` and can add features:

```toml
[dependencies]
serde.workspace = true
tokio = { workspace = true, features = ["rt", "net"] }
```

## Workspace-level lints (Cargo.toml)

Clippy lints are configured at workspace level and inherited by all members:

```toml
[workspace.lints.clippy]
correctness = { level = "deny", priority = -1 }
suspicious = { level = "deny", priority = -1 }
style = { level = "warn", priority = -1 }
# JNI/FFI allowances
missing_safety_doc = "allow"
not_unsafe_ptr_arg_deref = "allow"
```

Members opt in with:
```toml
[lints]
workspace = true
```

## Gradle property reference (native build)

| Property | Purpose | Example |
|----------|---------|---------|
| `frameport.nativeAbis` | ABIs for CI/release | `arm64-v8a,armeabi-v7a,x86_64,x86` |
| `frameport.localNativeAbisDefault` | ABIs for local dev | `arm64-v8a` |
| `frameport.localNativeAbis` | Per-invocation ABI override | `arm64-v8a` |
| `frameport.nativeCargoProfile` | Default cargo profile | `android-jni` |
| `frameport.localNativeCargoProfileDefault` | Local dev profile | `android-jni-dev` |
| `frameport.nativeNdkVersion` | NDK version string | `27.2.12479018` |
| `frameport.minSdk` | Android minSdk (passed to clang target) | `26` |

## Artifact mapping

The Gradle task maps Cargo output names to Android library names:

| Cargo package | Cargo output | Android .so |
|---------------|-------------|-------------|
| fuji-ffi | libframeport_android.so | libframeport.so |

## Selective build commands

```bash
cd rust/fuji-rs

# Build specific workspace member
cargo build -p fuji-ptp
cargo check -p fuji-transfer

# Test specific member
cargo nextest run -p fuji-core

# Test all members
cargo nextest run --workspace

# Exclude member from workspace build
cargo build --workspace --exclude fuji-diagnostics

# Cross-compile check (host only -- no NDK linker)
cargo check --target aarch64-linux-android -p fuji-ptp
```

## Cargo.lock management

The lock file is checked into git (application, not library):

```bash
# Update single dep precisely
cargo update -p tokio --precise 1.42.0

# Preview changes
cargo update --dry-run

# Regenerate lock file
cargo generate-lockfile
```
