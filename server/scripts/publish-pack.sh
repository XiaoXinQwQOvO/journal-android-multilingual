#!/usr/bin/env bash
# Publish (upload or update) an extension pack.
#
# Usage:
#   ADMIN_TOKEN=... ./scripts/publish-pack.sh path/to/pack.zip
#
# Optional environment variables:
#   SERVER_URL      defaults to https://journal.xiaoxinww.xin
#   TITLE           display title; defaults to registerName
#   DESCRIPTION     display description
#   OFFICIAL_LINK   project URL (http/https)
#   CURL_INSECURE=1 skip TLS verification (local testing only; never in production)

set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <pack.zip>" >&2
  exit 2
fi

pack_file="$1"
if [[ ! -f "$pack_file" ]]; then
  echo "error: file not found: $pack_file" >&2
  exit 2
fi
if [[ -z "${ADMIN_TOKEN:-}" ]]; then
  echo "error: ADMIN_TOKEN is not set" >&2
  exit 2
fi

server_url="${SERVER_URL:-https://journal.xiaoxinww.xin}"
server_url="${server_url%/}"

curl_args=(
  --silent
  --show-error
  --fail-with-body
  --request POST
  --header "X-Admin-Token: ${ADMIN_TOKEN}"
  --form "file=@${pack_file};type=application/zip"
)

if [[ -n "${TITLE:-}" ]]; then
  curl_args+=(--form "title=${TITLE}")
fi
if [[ -n "${DESCRIPTION:-}" ]]; then
  curl_args+=(--form "description=${DESCRIPTION}")
fi
if [[ -n "${OFFICIAL_LINK:-}" ]]; then
  curl_args+=(--form "official_link=${OFFICIAL_LINK}")
fi
if [[ "${CURL_INSECURE:-0}" == "1" ]]; then
  curl_args+=(--insecure)
fi

echo "Uploading ${pack_file} to ${server_url}/api/v1/admin/packs ..."
curl "${curl_args[@]}" "${server_url}/api/v1/admin/packs"
echo