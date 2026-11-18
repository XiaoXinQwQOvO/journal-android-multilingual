#!/usr/bin/env bash
# Build (and optionally publish) the PsychonautWiki substance data pack.
#
#   ASSETS_DIR=/opt/journal-repo/app/src/main/assets/substances \
#   PW_TRANSLATE_API_KEY=... ADMIN_TOKEN=... \
#     ./update-substances.sh --publish https://journal.xiaoxinww.xin
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Reuse the server environment file; it also carries ADMIN_TOKEN and the
# optional PW_TRANSLATE_* settings for this job.
ENV_FILE="${ENV_FILE:-$SCRIPT_DIR/../.env}"
if [[ -f "$ENV_FILE" ]]; then
    set -a
    # shellcheck disable=SC1090
    . "$ENV_FILE"
    set +a
fi

: "${ASSETS_DIR:?set ASSETS_DIR to app/src/main/assets/substances}"
OUT_DIR="${OUT_DIR:-$SCRIPT_DIR/../../dist}"

exec "${PYTHON_BIN:-python3}" "$SCRIPT_DIR/pw_sync.py" \
    --assets-dir "$ASSETS_DIR" \
    --out-dir "$OUT_DIR" \
    "$@"