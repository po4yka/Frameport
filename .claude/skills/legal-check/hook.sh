#!/usr/bin/env bash
# PostToolUse hook for *.md edits.
# Scans the file for proprietary-IP and false-affiliation risk phrases (hard blocks per
# .claude/skills/legal-check/SKILL.md). Emits a non-blocking warning to
# stderr if any are found. Always exits 0.
#
# Wire-up: referenced from .claude/settings.json PostToolUse hooks
# under matcher "Edit|Write|MultiEdit". Both this script and the
# settings.json that invokes it are gitignored.

set -u

f=$(jq -r '.tool_input.file_path // empty' 2>/dev/null)
[ -z "$f" ] && exit 0

case "$f" in
  *.md|*.MD|*.markdown) ;;
  *) exit 0 ;;
esac

[ -f "$f" ] || exit 0

# Skip files inside internal / private directories — those are
# legitimately allowed to use direct research vocabulary.
case "$f" in
  */docs/tasks/*|*/_meta/*|*/ops/*|*/.claude/*|*/.codex/*) exit 0 ;;
esac

hard=$(grep -niE \
  'official Fujifilm|officially supported by Fujifilm|official Fujifilm app|Fujifilm partner|Fujifilm certified|Fujifilm approved|endorsed by Fujifilm|licensed by Fujifilm|authorized by Fujifilm|genuine Fujifilm|Fujifilm verified|works with all Fujifilm|works with every Fujifilm|supports all Fujifilm|compatible with all X-series|compatible with every GFX' \
  "$f" 2>/dev/null || true)

if [ -n "$hard" ]; then
  {
    echo "[legal-check] affiliation/IP risk markers detected in $f:"
    echo "$hard" | sed 's/^/  /'
    echo "Run the legal-check skill (.claude/skills/legal-check/SKILL.md) before publishing."
  } >&2
fi

exit 0
