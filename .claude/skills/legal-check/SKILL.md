---
name: legal-check
description: Reviews public docs for proprietary-IP, false-affiliation, and compatibility-claim risk in Frameport public documentation.
---

# Legal-framing check (Frameport public docs)

## When to run

After ANY edit to a `*.md` file in the public repository (README.md, docs/**.md, repo metadata). The PostToolUse hook in `.claude/settings.json` automatically warns when high-risk phrases appear; this skill provides the deeper rubric and a structured review.

Internal vault notes (`docs/tasks/`, `_meta/`, operational notes) are out of scope — they live outside the published repo.

## Legal risk profile for Frameport

Frameport is a third-party companion app for Fujifilm cameras. It is NOT affiliated with, endorsed by, or licensed by Fujifilm Corporation. The legal risks are:

1. **False affiliation / endorsement** — claiming official Fujifilm backing that does not exist.
2. **Proprietary-IP reuse** — copying Fujifilm-owned code, assets, protocol implementations sourced from extracted APKs or SDKs, registered trademarks used as own branding.
3. **Overbroad compatibility claims** — claiming the app works with cameras that have not been tested.
4. **Redistribution of proprietary assets** — bundling Fujifilm cloud flows, account flows, firmware, or SDK binaries.

## Hard blocks (MUST NOT appear in public *.md)

- "official Fujifilm", "officially supported by Fujifilm", "official Fujifilm app"
- "Fujifilm partner", "Fujifilm certified", "Fujifilm approved"
- "endorsed by Fujifilm", "licensed by Fujifilm", "authorized by Fujifilm"
- "genuine Fujifilm", "Fujifilm verified"
- "works with all Fujifilm cameras", "works with every Fujifilm camera", "supports all Fujifilm models"
- "compatible with all X-series", "compatible with every GFX" (overbroad without test evidence)
- Any claim of redistributing Fujifilm SDK, Fujifilm cloud API keys, Fujifilm account credentials, or extracted APK assets

## Soft flags (rephrase to neutral)

| Risk wording | Neutral alternative |
|---|---|
| Works with Fujifilm cameras | Tested with [specific model list] |
| Fujifilm app | Third-party companion app for Fujifilm cameras |
| Fujifilm protocol | Fujifilm camera communication protocol (reverse-engineered / community-documented) |
| Full compatibility | Tested on [model list]; other models may work but are not verified |
| Fujifilm X-series support | Tested X-series models: [list] |
| Uses Fujifilm SDK | Implements the PTP/PTP-IP protocol as observed on Fujifilm cameras |
| Fujifilm cloud / FUJIFILM X APP | Not affiliated with FUJIFILM X APP or any Fujifilm cloud service |

## Acceptable framing pillars

- **Third-party companion app** — Frameport is an independent, community-built app.
- **Protocol reverse-engineering disclaimer** — implementation is based on observed PTP/PTP-IP behavior; not derived from proprietary SDK.
- **Tested model list** — compatibility claims scoped to models with documented test results.
- **Privacy-first, local-only** — no cloud, no Fujifilm account required, no telemetry.
- **Open protocol** — PTP (ISO 15740) is a published standard; PTP-IP and Fujifilm extensions are community-documented.

## What NOT to do during review

- Do not delete technical sections explaining PTP/PTP-IP/BLE protocol implementation — these are legitimate technical descriptions.
- Do not flag use of "Fujifilm" as a noun identifying the camera brand — that is acceptable referential use. Only flag claims of affiliation, endorsement, or licensing.
- Do not flag model names (X-T5, GFX 100S, X100VI, etc.) used in compatibility tables with test evidence.

## Procedure

1. Identify the just-edited `*.md` file (path from the user prompt or from the most recent edit).
2. Confirm scope: must be inside the published repo (`README.md`, `docs/**.md`, `*.md` at repo root). Skip if path is in `docs/tasks/`, `_meta/`, or otherwise gitignored.
3. Grep the file for hard-block markers. Report each hit with line number, exact phrase, and the recommended replacement from the table above.
4. Grep for soft-flag markers. Report each with line number, current phrase, and suggested rephrasing.
5. Verdict: PASS or FAIL.
6. If FAIL, propose specific edits.

## Output format

```
File reviewed: <path>

Hard-block hits:
- L<line>: "<phrase>" → recommended: "<replacement>"
- ...

Soft-flag hits:
- L<line>: "<phrase>" → suggested: "<rephrasing>"
- ...

Verdict: PASS | FAIL

Recommended next edits (if FAIL):
- ...
```

If everything passes: `Verdict: PASS — no affiliation/IP risk detected.` plus the list of trigger phrases that were checked.
