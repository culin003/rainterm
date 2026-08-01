#!/usr/bin/env bash
#
# Push to GitHub using the PAT stored in .env, without ever writing the
# token into .git/config (which `git remote set-url` would do, and which
# is easy to leak via `git config --list` or a stray `git remote -v`).
#
# Usage:
#   ./scripts/git-push.sh main        # push a branch
#   ./scripts/git-push.sh v1.2.0      # push a tag (triggers release.yml)
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$REPO_ROOT/.env"

if [[ $# -lt 1 ]]; then
    echo "usage: $(basename "$0") <branch-or-tag>" >&2
    exit 2
fi
REF="$1"

if [[ ! -f "$ENV_FILE" ]]; then
    echo "error: $ENV_FILE not found. Copy .env.example to .env and fill it in." >&2
    exit 1
fi

# shellcheck disable=SC1090
set -a; source "$ENV_FILE"; set +a

: "${GITHUB_TOKEN:?GITHUB_TOKEN missing from .env}"
: "${GITHUB_REPO:?GITHUB_REPO missing from .env}"

# The token appears only in this subshell's argv, never on disk.
# Output is scrubbed so a failure message can't echo the token back.
git -C "$REPO_ROOT" push \
    "https://x-access-token:${GITHUB_TOKEN}@github.com/${GITHUB_REPO}.git" \
    "$REF" 2>&1 | sed "s|${GITHUB_TOKEN}|***|g"
