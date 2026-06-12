# `.claude/scripts/`

Shell scripts wired into Claude Code hooks. `.claude/settings.json` is per-developer
(gitignored), so each developer enables hooks by editing their own settings to
reference the scripts here.

## Available scripts

### `rust-postedit-check.sh`
PostToolUse hook. Runs `cargo check -p <crate> --locked --message-format=short` on
the touched crate after any `Edit` / `Write` / `MultiEdit` of a `*.rs` file.
Exits 2 on compile error so Claude Code injects the error into the model's
next-turn context. Timeout 90s. Skips silently for non-Rust files.

The Rust workspace root is `rust/fuji-rs/`. Crates: `fuji-core`, `fuji-ptp`,
`fuji-ptpip`, `fuji-transfer`, `fuji-liveview`, `fuji-ble-protocol`,
`fuji-usb-ptp`, `fuji-diagnostics`, `fuji-sim`, `fuji-ffi`, `fuji-cli`.

### `rust-stop-verify.sh`
Stop hook. Runs `cargo fmt --all --check` then `cargo clippy --workspace
--all-targets --locked -- -D warnings` if any `*.rs` file is dirty in the working
tree. Exits 2 on failure so the model sees gaps before the turn ends. Timeout
60s for fmt-check, 180s for clippy. Skips silently when no Rust changes pending.

### `subagent-stop-audit-log.sh`
SubagentStop hook (Claude Code v2.1.145+). Logs every sub-agent finish to
`.claude/logs/subagent-stop.log` with `agent_type`, `agent_id`, final-message
length and a 200-char preview. For audit-class agents (`unsafe-code-auditor`,
`jni-bridge-verifier`, `async-cancel-safety`, `arch-layer-auditor`,
`rust-api-auditor`, `kotlin-design-auditor`) it also emits a `WARNING` line when
the final message lacks a recognized conclusion marker (`findings`, `audit`,
`violations`, `risk`, `cancel-safe`, `unsafe`, `reviewed`) — usually a sign the
sub-agent finished without doing the audit. Pure observability: always exits 0
because `SubagentStop` cannot inject `additionalContext` per Anthropic's docs.
Disable with `FRAMEPORT_SUBAGENT_HOOKS=off`.

## Wiring (add to your `.claude/settings.json`)

```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "Edit|Write|MultiEdit",
        "hooks": [
          {
            "type": "command",
            "command": "ROOT=$(git rev-parse --show-toplevel 2>/dev/null || pwd); bash \"$ROOT/.claude/scripts/rust-postedit-check.sh\""
          }
        ]
      }
    ],
    "Stop": [
      {
        "matcher": "",
        "hooks": [
          {
            "type": "command",
            "command": "ROOT=$(git rev-parse --show-toplevel 2>/dev/null || pwd); bash \"$ROOT/.claude/scripts/rust-stop-verify.sh\""
          },
          {
            "type": "command",
            "command": "ROOT=$(git rev-parse --show-toplevel 2>/dev/null || pwd); bash \"$ROOT/.claude/skills/legal-check/hook.sh\""
          }
        ]
      }
    ],
    "SubagentStop": [
      {
        "matcher": "",
        "hooks": [
          {
            "type": "command",
            "command": "ROOT=$(git rev-parse --show-toplevel 2>/dev/null || pwd); bash \"$ROOT/.claude/scripts/subagent-stop-audit-log.sh\""
          }
        ]
      }
    ]
  }
}
```

If you also have hooks for Kotlin / baseline-block, merge the new `command` entries
into the existing `hooks` array for the matching matcher — don't replace.

## Disabling temporarily

All scripts honour `FRAMEPORT_RUST_HOOKS=off`. Export it in your shell before a
session if you want the hooks dormant for a debugging stint:

```bash
FRAMEPORT_RUST_HOOKS=off claude
```

## Codex CLI hook wiring

The same scripts are usable from Codex CLI's `[hooks]` table in `.codex/config.toml`
(project-local) or `~/.codex/config.toml` (per-user). Codex's hook schema mirrors
Claude Code's event names but uses TOML:

```toml
[[hooks.post_tool_use]]
matcher = "Edit|Write|MultiEdit"
command = "bash ${workspace_root}/.claude/scripts/rust-postedit-check.sh"

[[hooks.stop]]
command = "bash ${workspace_root}/.claude/scripts/rust-stop-verify.sh"
```

The scripts are tool-agnostic: they read no Claude-Code-specific environment
variables (the `PostToolUse` script reads `.tool_input.file_path` from stdin via
`jq`, which Codex CLI also pipes per Claude's hook protocol).

## Strict vs advisory mode

`rust-stop-verify.sh` defaults to **advisory** — it prints any fmt-check or
clippy findings to stderr but exits 0, so a parallel agent's in-flight drift in
the working tree does not block your turn. To make it blocking (exit 2 on
findings — model sees the error in next turn's context):

```bash
FRAMEPORT_RUST_HOOKS_STRICT=on claude
```

Use strict mode for solo sessions on a clean tree; default advisory mode for
multi-agent or worktree-shared work where dirty files may not be yours.

## Rationale

See `.claude/rules/llm-rust-prompts.md` and `.claude/rules/rust-toolchain-pin.md`
for why these checks run on the AI-generation path specifically. Empirical
finding: PostToolUse blocking with `exit 2` is the single biggest behaviour
shift for Opus 4.7 on Rust — the model sees its own clippy error inline and
fixes on the next turn, without a separate user prompt.

## Rust-analyzer MCP wiring (recommended)

The `llm-rust-prompts.md` rule mandates "query rust-analyzer before guessing a
Rust type or signature." Wire it into `.claude/settings.json`:

```json
{
  "mcpServers": {
    "rust-analyzer": {
      "command": "rust-analyzer-mcp",
      "args": [],
      "cwd": "${workspaceFolder}/rust/fuji-rs"
    }
  }
}
```

Install the MCP server once per machine:

```bash
cargo install --git https://github.com/zeenix/rust-analyzer-mcp
```

Verify after wiring: restart Claude Code and ask it to hover over a symbol in any
`fuji-*` crate. If `mcp__rust-analyzer__*` tools respond, the MCP is live.
