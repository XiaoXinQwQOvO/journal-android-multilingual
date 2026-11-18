#!/usr/bin/env bash
# Build (and optionally publish) the PsychonautWiki substance data pack.
#
#   ASSETS_DIR=/opt/journal-repo/app/src/main/assets/substances \
#   PW_TRANSLATE_API_KEY=... ADMIN_TOKEN=... \
#     ./update-substances.sh --publish https://journal.xiaoxinww.xin
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
: "${ASSETS_DIR:?set ASSETS_DIR to app/src/main/assets/substances}"
OUT_DIR="${OUT_DIR:-$SCRIPT_DIR/../../dist}"

exec python3 "$SCRIPT_DIR/pw_sync.py" \
    --assets-dir "$ASSETS_DIR" \
    --out-dir "$OUT_DIR" \
    "$@"