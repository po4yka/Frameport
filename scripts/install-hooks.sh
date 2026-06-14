#!/bin/sh
# install-hooks.sh — idempotently install the checked-in hook sources into
# .git/hooks/.
#
# Usage:  sh scripts/install-hooks.sh
#
# Run this once after cloning, or after updating hook sources in scripts/.
# .git/hooks/ is NOT tracked by git; this script bridges the gap.
#
# The script is idempotent: running it multiple times has the same effect
# as running it once.

set -eu

REPO_ROOT=$(git rev-parse --show-toplevel)
HOOKS_DIR="${REPO_ROOT}/.git/hooks"
SCRIPTS_DIR="${REPO_ROOT}/scripts"

install_hook() {
    src="${SCRIPTS_DIR}/$1"
    dst="${HOOKS_DIR}/$1"

    if [ ! -f "${src}" ]; then
        echo "ERROR: hook source not found: ${src}" >&2
        exit 1
    fi

    cp "${src}" "${dst}"
    chmod +x "${dst}"
    echo "Installed: ${dst}"
}

install_hook pre-commit

echo "Hook installation complete."
