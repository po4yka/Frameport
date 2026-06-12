## Compose Preview rendering — agent contract

[`yschimke/compose-ai-tools`](https://github.com/yschimke/compose-ai-tools) is wired into Frameport so AI agents (and humans) can see every `@Preview` composable as a PNG without booting Android Studio or an emulator. It is a **build-time-only** Gradle plugin (`ee.schimke.composeai.preview`, Apache-2.0, Maven Central, pinned in `gradle/libs.versions.toml`). Zero runtime impact, zero permissions, zero outbound network calls from the app or the plugin itself.

### What this is NOT

- **Not a Roborazzi replacement.** Roborazzi (`frameport.android.roborazzi`) continues to own regression-locked golden screenshots under `app/src/test/screenshots/`. Those goldens are governed by `golden-bless-discipline.md` and are intentionally hard to change.
- **Not a daemon.** Upstream ships a CLI + a Gradle plugin + a VS Code extension — **no HTTP daemon, no localhost endpoint**. Agents drive it through Gradle tasks.
- **Not goldens.** Output PNGs are throwaway artifacts. They live under a gitignored path and must never be committed, copied into `app/src/test/screenshots/`, or used as bless inputs.

### Architecture — what the plugin does vs what the CLI does

The integration has two pieces:

1. **Gradle plugin** (`ee.schimke.composeai.preview`, applied via `frameport.android.compose`) — registers exactly one task per module: `:<module>:composePreviewApplied`, which writes a marker JSON advertising that the module supports preview rendering. It does NOT register `renderAllPreviews` or `discoverPreviews` by itself.
2. **`compose-preview` CLI** — ships a Gradle init-script that registers `discoverPreviews` and `renderAllPreviews` and drives them via the Tooling API.

A first-time agent or contributor must install the CLI once per machine:

```sh
curl -fsSL https://raw.githubusercontent.com/yschimke/skills/main/scripts/install.sh | bash
```

### How to render

```sh
scripts/render-compose-previews.sh         # render every @Preview in :app
scripts/render-compose-previews.sh list    # list previews, no render
```

### Where the PNGs land

| Path | Contents |
|------|----------|
| `app/build/compose-previews/renders/` | one PNG per discovered `@Preview` function |
| `app/build/compose-previews/diffs/` | visual diffs vs baseline (when a baseline is supplied) |
| `app/build/compose-previews/previews.json` | preview index (FQN → file, dimensions, device) |
| `app/build/compose-previews/**/*.json` | per-render metadata |

All matched by the `**/build/compose-previews/` entry in `.gitignore`.

### Defaults the plugin ships with

```kotlin
composePreview {
    variant.set("debug")     // Android build variant
    sdkVersion.set(37)       // Robolectric SDK version — matches frameport.targetSdk=37
    enabled.set(true)
}
```

Defaults match Frameport's `debug` variant and `targetSdk=37`. The convention plugin (`frameport.android.compose.gradle.kts`) leaves the extension at its defaults.

### Forbidden actions (hard rules for agents)

1. **Never** copy or `mv` files from `app/build/compose-previews/` into `app/src/test/screenshots/` or any path under `tests/golden/` / `src/test/resources/golden/`. That short-circuits `golden-bless-discipline.md`.
2. **Never** invoke `FRAMEPORT_BLESS_GOLDENS=1` to "fix" a difference between a render and a Roborazzi golden. The two systems are intentionally decoupled.
3. **Never** commit anything under `app/build/compose-previews/`. CI surfaces them via `actions/upload-artifact` if needed — they are not source.
4. **Never** add a `composePreview { enabled.set(false) }` override "to silence the task" — fix the underlying preview compilation problem instead.

### Where it is applied

`build-logic/convention/src/main/kotlin/frameport.android.compose.gradle.kts` applies `id("ee.schimke.composeai.preview")` alongside `org.jetbrains.kotlin.plugin.compose`, so every module that opts into `frameport.android.compose` participates.

### Upstream requirements (already satisfied)

| Requirement | Upstream needs | Frameport has |
|---|---|---|
| Gradle | 9.4.1+ | 9.x (workspace) |
| Java | 17+ | JVM 17 target |
| AGP | 9.1+ | 9.2.1 |
| Kotlin | 2.2.21+ | 2.3.10 |
| Robolectric | 4.16.x | 4.16.x (pinned) |

### Cross-references

- `golden-bless-discipline.md` — why preview output paths must never touch the golden paths.
- `llm-rust-prompts.md` — diff-acceptance gate; treat any agent attempt to commit `build/compose-previews/` content as a slop signal.
