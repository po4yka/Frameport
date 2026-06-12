# Agent Tooling

Optional tooling that accelerates AI-assisted development on Frameport. None of it is required to build the app; it improves the agent (Claude Code / Codex) workflow. The skills and rules under `.claude/` and `.codex/` are committed and load automatically — this doc covers the parts that live outside the repo (MCP servers, external skill packs) and how to reproduce them.

## MCP servers (`.mcp.json`)

`.mcp.json` at the repo root declares three project-scoped MCP servers. Claude Code prompts to enable them on first use.

| Server | Purpose | Launch | Prerequisites |
|---|---|---|---|
| `android-docs` | Live developer.android.com lookup (class/API/permission search) — keeps the agent from guessing platform APIs | `npx -y android-docs-mcp` | Node/npx |
| `gradle` | Gradle introspection: tasks, filtered test runs with stack traces, dependency browser | `jbang run --quiet --fresh gradle-mcp@rnett` | **JDK 25+** and **jbang** (`brew install jbangdev/tap/jbang`) |
| `android-device` | ADB device/emulator control: install, screenshot, logcat, UI automation | `npx -y claude-in-mobile@latest` | `adb` on PATH |

Status note: the `gradle` server needs JDK 25+ and jbang; the repo currently builds on JDK 17/21, so install those before enabling it (or remove the `gradle` entry). `android-docs` and `android-device` work with the standard toolchain.

The `android-docs` server is the highest-value one: it directly prevents the kind of fabricated Android API names that adversarial review otherwise has to catch.

## External skill packs (global, `~/.claude/skills`)

Two third-party Apache-2.0 skill collections by skydoves are installed globally (so they apply to any Android project), with their sources kept under `~/.claude/skills-sources/` and symlinked by slug into `~/.claude/skills/`. Re-run the install script after `git pull` to pick up new skills.

```bash
mkdir -p ~/.claude/skills-sources && cd ~/.claude/skills-sources
git clone https://github.com/skydoves/android-testing-skills.git
git clone https://github.com/skydoves/compose-performance-skills.git
bash android-testing-skills/scripts/install-skills.sh        # 54 skills
bash compose-performance-skills/scripts/install-skills.sh     # 26 skills
# uninstall: pass --uninstall to either script
```

- **android-testing-skills** (54): Compose UI testing, JVM unit tests (MockK, Turbine, Robolectric), instrumentation, ADB CI scripting. Complements the project `android-testing` skill.
- **compose-performance-skills** (26): stability inference, recomposition avoidance, baseline profiles, lazy-layout tuning — relevant to the live-view and gallery screens.

These are installed globally rather than vendored into the repo to avoid committing ~80 third-party files; the project's own focused skills live in `.claude/skills/`.

## Open-source PTP / PTP-IP references (study only)

Clean-room interop study under the project's legal boundary (see `legal-check` skill and `CLAUDE.md`). Read to understand the standard and what to observe on a real device — do **not** copy code or assert reverse-engineered Fujifilm opcodes as fact.

- **ISO 15740 (PTP)** — the normative standard. Free summary: https://en.wikipedia.org/wiki/ISO_15740
- **libgphoto2** (`camlibs/ptp2/ptp.h`, `camlibs/ptpip/`) — canonical FOSS PTP/PTP-IP implementation; study the header and issue tracker for the standard-vs-vendor boundary. LGPL-2.1: study, do not copy into the Rust code. https://github.com/gphoto/libgphoto2
- **hkr/fuji-cam-wifi-tool** — community reverse-engineering notes for older Fujifilm X-series Wi-Fi control. Interop reference only; opcodes there are observations, not authoritative. https://github.com/hkr/fuji-cam-wifi-tool
- **vdavid/mtp-rs** — pure-Rust async MTP/PTP-over-USB; structural reference for USB framing/dispatch in Rust. https://github.com/vdavid/mtp-rs

## Discovery registries

- https://github.com/rohitg00/awesome-claude-code-toolkit
- https://github.com/hesreallyhim/awesome-claude-code
