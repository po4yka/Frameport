#!/bin/sh
# test-hook-dry-run.sh — verify the pre-commit hook logic without touching
# the real git index.
#
# Usage:  sh scripts/test-hook-dry-run.sh
#
# Three cases must all PASS:
#   Case 1: FRAMEPORT_BLESS_GOLDENS=1 in env        -> BLOCKED (exit 2)
#   Case 2: golden path staged, no rationale word   -> BLOCKED (exit 2)
#   Case 3: golden path staged, rationale word      -> ALLOWED (exit 0)
#
# The hook reads two external inputs:
#   (a) `git diff --cached --name-only` — stubbed via a fake git on PATH
#   (b) ${GIT_DIR}/COMMIT_EDITMSG      — stubbed via a temp directory

set -e

REPO_ROOT=$(git -C "$(dirname "$0")" rev-parse --show-toplevel)
HOOK="${REPO_ROOT}/scripts/pre-commit"

TOTAL_PASS=0
TOTAL_FAIL=0

check() {
    NUM="$1"; DESC="$2"; EXPECTED="$3"; ACTUAL="$4"
    if [ "${ACTUAL}" = "${EXPECTED}" ]; then
        printf '[PASS] Case %s: %s (exit %s)\n' "${NUM}" "${DESC}" "${ACTUAL}"
        TOTAL_PASS=$((TOTAL_PASS + 1))
    else
        printf '[FAIL] Case %s: %s (expected exit %s, got %s)\n' \
            "${NUM}" "${DESC}" "${EXPECTED}" "${ACTUAL}"
        TOTAL_FAIL=$((TOTAL_FAIL + 1))
    fi
}

echo '--- Running hook dry-run tests ---'
echo ''

# ---------------------------------------------------------------------------
# Case 1: FRAMEPORT_BLESS_GOLDENS=1 -> must exit 2 regardless of git state
# ---------------------------------------------------------------------------
R1=0
FRAMEPORT_BLESS_GOLDENS=1 sh "${HOOK}" 2>/dev/null || R1=$?
check 1 "FRAMEPORT_BLESS_GOLDENS=1 is blocked" 2 "${R1}"

# ---------------------------------------------------------------------------
# Setup for Cases 2 and 3: fake git binary + fake GIT_DIR
# ---------------------------------------------------------------------------
FAKE_BIN=$(mktemp -d)
FAKE_GIT_DIR=$(mktemp -d)

# Fake git always reports one staged golden path.
cat > "${FAKE_BIN}/git" <<'EOF'
#!/bin/sh
printf 'tests/golden/fuji-ptp/packet-encode.bin\n'
EOF
chmod +x "${FAKE_BIN}/git"

# ---------------------------------------------------------------------------
# Case 2: golden staged, commit message has NO rationale -> exit 2
# ---------------------------------------------------------------------------
printf 'refactor: clean up parser internals\n' > "${FAKE_GIT_DIR}/COMMIT_EDITMSG"

R2=0
PATH="${FAKE_BIN}:${PATH}" GIT_DIR="${FAKE_GIT_DIR}" \
    sh "${HOOK}" 2>/dev/null || R2=$?
check 2 "golden staged, no rationale word, is blocked" 2 "${R2}"

# ---------------------------------------------------------------------------
# Case 3: golden staged, commit message HAS rationale -> exit 0
# ---------------------------------------------------------------------------
printf 'fix: intentional behavioral change to PTP packet encoder\n' \
    > "${FAKE_GIT_DIR}/COMMIT_EDITMSG"

R3=0
PATH="${FAKE_BIN}:${PATH}" GIT_DIR="${FAKE_GIT_DIR}" \
    sh "${HOOK}" 2>/dev/null || R3=$?
check 3 "golden staged, rationale word present, is allowed" 0 "${R3}"

# ---------------------------------------------------------------------------
# Cleanup
# ---------------------------------------------------------------------------
rm -rf "${FAKE_BIN}" "${FAKE_GIT_DIR}"

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
printf '\nResults: %s passed, %s failed\n' "${TOTAL_PASS}" "${TOTAL_FAIL}"
if [ "${TOTAL_FAIL}" -gt 0 ]; then
    exit 1
fi
exit 0
