# Journal server

Backend for the multilingual PsychonautWiki Journal fork: accounts, passkeys,
whole-journal snapshots, avatars, extension pack hosting, and Android App Links
verification.

- Rust + axum, MySQL 8.0, no Docker.
- Listens on `0.0.0.0:8965` by default. **TLS is not terminated here**; put it
  behind nginx or Caddy on the same host.
- All API routes are under `/api/v1`; `/.well-known/assetlinks.json` and
  `/healthz` are at the root.

## Requirements

- MySQL 8.0 (utf8mb4).
- Rust 1.88 or newer (webauthn-rs 0.5.5 requires 1.88).
- OpenSSL development files on the build machine (`libssl-dev` /
  `openssl-devel`). webauthn-rs links against OpenSSL.
- Python 3.8+ for the optional scheduled substance sync script.
- For serving the built binary: glibc and the OpenSSL 3 runtime
  (`libssl3`) of a compatible distribution.

## MySQL setup

Run as a MySQL administrator:

```sql
CREATE DATABASE journal_server
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

CREATE USER 'journal'@'127.0.0.1' IDENTIFIED BY 'replace-this-password';
GRANT ALL PRIVILEGES ON journal_server.* TO 'journal'@'127.0.0.1';
FLUSH PRIVILEGES;
```

`GRANT ALL` on the database is used because migrations create and alter tables.
The server records migration checksums in `_sqlx_migrations` and applies
`migrations/` automatically at startup.

Journal snapshots can be up to 25 MiB (configurable) and avatars/packs are
uploaded through the connection, so MySQL must allow large packets. Add to
`/etc/mysql/mysql.conf.d/mysqld.cnf` (or an equivalent config include):

```ini
[mysqld]
max_allowed_packet = 64M
character-set-server = utf8mb4
collation-server = utf8mb4_0900_ai_ci
```

Restart MySQL and verify:

```bash
mysql -e "SHOW VARIABLES WHERE Variable_name IN ('max_allowed_packet','character_set_server','collation_server');"
```

`max_allowed_packet` must be at least 64M (the SQL statement carrying a 25 MiB
journal snapshot is larger than the payload). If you raise
`MAX_JOURNAL_BYTES`, raise the packet limit accordingly.

## Configuration

Copy `.env.example` to `.env` and edit it. The process loads `.env` from its
working directory (and normal process environment wins).

| Variable | Required | Default | Notes |
|---|---|---|---|
| `DATABASE_URL` | yes | - | `mysql://user:pass@127.0.0.1:3306/journal_server` |
| `JWT_SECRET` | yes | - | At least 32 bytes; `openssl rand -base64 48` |
| `ADMIN_TOKEN` | yes | - | At least 16 bytes; sent as `X-Admin-Token` |
| `BIND_ADDR` | no | `0.0.0.0:8965` | Use `127.0.0.1:8965` if the proxy is local |
| `RP_ID` | no | `journal.xiaoxinww.xin` | WebAuthn relying party ID |
| `RP_ORIGIN` | no | `https://journal.xiaoxinww.xin` | Must share a domain with `RP_ID` |
| `DATA_DIR` | no | `./data` | Pack zips/icons are stored under `packs/` |
| `MAX_JOURNAL_BYTES` | no | `26214400` | 25 MiB |
| `MAX_PACK_BYTES` | no | `104857600` | 100 MiB |
| `MAX_AVATAR_BYTES` | no | `2097152` | 2 MiB |
| `ANDROID_APP_CERT_SHA256` | yes for Android | empty | Comma-separated signing-cert SHA-256 fingerprints |

`ANDROID_APP_CERT_SHA256` serves two purposes:

1. It is rendered into `/.well-known/assetlinks.json` (Android App Links).
2. The server derives the native Android WebAuthn origin
   `android:apk-key-hash:<base64url>` from each fingerprint. Without it,
   Android Credential Manager passkeys fail origin validation.

Startup fails fast when `DATABASE_URL`, `JWT_SECRET`, or `ADMIN_TOKEN` are
missing or invalid.

## Build (Linux amd64)

The development machine may be Windows. The easiest way to produce the Linux
binary is WSL (Ubuntu) or any Linux host:

```bash
sudo apt update
sudo apt install build-essential pkg-config libssl-dev
# rustup: https://rustup.rs

cd server
cargo build --release
```

The binary is `target/release/journal-server`. When building on x86_64 Linux no
cross target is needed. To make the target explicit:

```bash
rustup target add x86_64-unknown-linux-gnu
cargo build --release --target x86_64-unknown-linux-gnu
# target/x86_64-unknown-linux-gnu/release/journal-server
```

Cross-compiling from Windows/macOS requires a Linux C cross toolchain plus
OpenSSL headers and libraries for the target. Set `OPENSSL_DIR` to the target
OpenSSL prefix and `CC_x86_64_unknown_linux_gnu` /
`CARGO_TARGET_X86_64_UNKNOWN_LINUX_GNU_LINKER` to the cross compiler. Building
inside WSL avoids this and is recommended.

### Cross-compiling a static Linux binary from Windows

The following produces a fully static musl binary (no libssl/glibc on the
server) and needs no WSL:

```bash
rustup target add x86_64-unknown-linux-musl
cargo install cargo-zigbuild

# Alpine's musl OpenSSL static libraries
mkdir -p /tmp/openssl-musl && cd /tmp/openssl-musl
curl -LO https://dl-cdn.alpinelinux.org/alpine/v3.22/main/x86_64/openssl-dev-3.5.9-r0.apk
curl -LO https://dl-cdn.alpinelinux.org/alpine/v3.22/main/x86_64/openssl-libs-static-3.5.9-r0.apk
tar -xzf openssl-dev-3.5.9-r0.apk && tar -xzf openssl-libs-static-3.5.9-r0.apk
cd /path/to/repo/server
OPENSSL_DIR=/tmp/openssl-musl/usr OPENSSL_STATIC=1 \
  cargo zigbuild --release --target x86_64-unknown-linux-musl
```

The result is `target/x86_64-unknown-linux-musl/release/journal-server`, an ELF
x86-64 statically linked executable. Zig must be installed and on `PATH`.

Copy the binary to the server, for example `/opt/journal-server/journal-server`.
Keep `migrations/` in the source tree only for building: migrations are embedded
in the binary by `sqlx::migrate!`.

## Run

```bash
cp .env.example .env
# edit .env
./journal-server
```

It applies migrations, creates `DATA_DIR/packs`, and listens on port **8965**.
Check:

```bash
curl -s http://127.0.0.1:8965/healthz     # ok
curl -s http://127.0.0.1:8965/.well-known/assetlinks.json
```

Do not expose 8965 directly to the internet: bearer tokens and passwords cross
this listener and must be protected by TLS at the reverse proxy.

### systemd example

`/etc/systemd/system/journal-server.service`:

```ini
[Unit]
Description=Journal sync server
After=network-online.target mysql.service
Wants=network-online.target

[Service]
Type=simple
User=journal
Group=journal
WorkingDirectory=/opt/journal-server
EnvironmentFile=/etc/journal-server.env
ExecStart=/opt/journal-server/journal-server
Restart=on-failure
RestartSec=3
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/lib/journal-server

[Install]
WantedBy=multi-user.target
```

Use `DATA_DIR=/var/lib/journal-server` in the environment file and install the
environment file with mode `0600` because it contains secrets. Then:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now journal-server
journalctl -u journal-server -f
```

## TLS reverse proxy

The API uploads packs and journals; the proxy must allow up to 110 MB request
bodies. Keep TLS 1.2+.

### nginx

```nginx
server {
    listen 443 ssl;
    http2 on;
    server_name journal.xiaoxinww.xin;

    ssl_certificate     /etc/letsencrypt/live/journal.xiaoxinww.xin/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/journal.xiaoxinww.xin/privkey.pem;

    client_max_body_size 110m;
    proxy_read_timeout 300s;
    proxy_send_timeout 300s;

    location / {
        proxy_pass http://127.0.0.1:8965;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

### Caddy

```caddyfile
journal.xiaoxinww.xin {
    request_body {
        max_size 110MB
    }
    reverse_proxy 127.0.0.1:8965
}
```

Caddy obtains certificates automatically.

## Android signing certificate and App Links

The client package is `in.kawaiis.journal`. App Links and native passkeys only
work when the signing certificate fingerprint in
`/.well-known/assetlinks.json` matches the installed APK.

Get the release certificate fingerprint (a release keystore may not exist yet;
create one before publishing):

```bash
# From an APK (recommended: use the exact artifact users install)
apksigner verify --print-certs app-release.apk

# Or from the keystore
keytool -list -v -keystore release.jks -alias <alias>
```

Copy the SHA-256 digest, including colons, into `ANDROID_APP_CERT_SHA256`. Add
multiple fingerprints separated by commas (for example debug + release). The
server normalizes them and serves:

```json
[{
  "relation": [
    "delegate_permission/common.get_login_creds",
    "delegate_permission/common.handle_all_urls"
  ],
  "target": {
    "namespace": "android_app",
    "package_name": "in.kawaiis.journal",
    "sha256_cert_fingerprints": ["AA:BB:...:99"]
  }
}]
```

`get_login_creds` is required for native passkeys; `handle_all_urls` covers
App Links. The same statement carries both.

Verify `https://journal.xiaoxinww.xin/.well-known/assetlinks.json` is reachable
without a redirect. If you rotate signing keys, update
`ANDROID_APP_CERT_SHA256` and restart the service. Debug builds on different
machines have different debug certificates; a production `assetlinks.json`
should contain the release certificate (debug certs only for testing).

## Publishing an extension pack

Packs are zip files with `manifest.json` at the root. `registerName`,
`versionName`, and `versionCode` are required; the new `versionCode` must be
greater than the currently hosted one. A root `icon.png`, `icon.jpg`,
`icon.jpeg`, or `icon.webp` (or the `icon` path in `manifest.json`) is extracted
and served to clients. The zip is never extracted to disk.

```bash
export ADMIN_TOKEN='the same value as in the server environment'

# Defaults to https://journal.xiaoxinww.xin
./scripts/publish-pack.sh path/to/extension.zip

# Optional metadata and a local test server:
SERVER_URL=http://127.0.0.1:8965 \
TITLE='My extension' \
DESCRIPTION='Adds ...' \
OFFICIAL_LINK='https://example.com/my-extension' \
./scripts/publish-pack.sh path/to/extension.zip
```

Remove a pack:

```bash
curl --fail-with-body -X DELETE \
  -H "X-Admin-Token: $ADMIN_TOKEN" \
  https://journal.xiaoxinww.xin/api/v1/admin/packs/<registerName>
```

Pack hosting layout on disk:

```
DATA_DIR/
  packs/
    <registerName>/
      <versionCode>.zip
      icon.png
```

Only the current version is kept; older files are removed after a successful
update. Back up `DATA_DIR` and the MySQL database together.

## Scheduled substance data sync

`server/scripts/pw_sync.py` refreshes substance data from PsychonautWiki's
GraphQL API (`https://api.psychonautwiki.org/`), keeps the local text unchanged
unless the API provides a replacement, translates changed fields with the
configured OpenAI-compatible endpoint, and builds an extension pack that the
client installs through the extension store.

The translation endpoint defaults to `http://60.204.204.179:10087/v1` with model
`deepseek-v4.1-flash-1017`. It requires an API key, which belongs in the
`PW_TRANSLATE_API_KEY` line of `server/.env`; the wrapper sources that file so
the same file also supplies `ADMIN_TOKEN` for uploads. The server process ignores
the `PW_TRANSLATE_*` variables.

```bash
export ASSETS_DIR=/opt/journal-repo/app/src/main/assets/substances
export PW_TRANSLATE_API_KEY='<key>'
export ADMIN_TOKEN='<same value as in the server environment>'

# Dry run: build dist/pw_core_data_<version>.zip without publishing.
./server/scripts/update-substances.sh

# Publish the pack; the job must also pass --include-new to add substances that
# are not present in the bundled assets yet.
./server/scripts/update-substances.sh --include-new --publish https://journal.xiaoxinww.xin
```

Useful flags:

| Flag | Effect |
|---|---|
| `--no-translate` | Update English/base data only (no API key needed) |
| `--include-new` | Add API substances missing from the local assets |
| `--limit N` | Process only the first N API substances (smoke testing) |
| `--out-dir DIR` | Where to write the zip (default `dist/`) |
| `--state-dir DIR` | Snapshot used to avoid republishing unchanged data (default `scripts/.pw_state`) |
| `--advance-state` | Mark the generated pack as synced when uploading it manually |
| `--publish URL` | Upload with `X-Admin-Token` and advance the state |

The pack is named `pw_core_data`; clients see it in the extension store and the
loader merges `substances/root`, `substances/en_us`, `substances/zh_cn` and
`substances/zh_tw` over the bundled data. Long-form text such as `summary`,
`generalRisks` and `saferUse` is preserved for existing substances because the
API's summary field still contains wiki leftovers; review generated diffs before
enabling fully automatic publishing. `--include-new` is best used with review:
upstream records have been observed carrying another substance's aliases and
class, so the script only trusts aliases that contain the record's own name and
leaves new entries uncategorised.

Weekly cron example (run from a repository checkout and update it first):

```cron
0 4 * * 1 cd /opt/journal-repo && git pull --ff-only && \
  ASSETS_DIR=/opt/journal-repo/app/src/main/assets/substances \
  PW_TRANSLATE_API_KEY=... ADMIN_TOKEN=... \
  ./server/scripts/update-substances.sh --include-new --publish https://journal.xiaoxinww.xin
```

The translation cache lives in `server/scripts/.pw_translation_cache.json` and
is gitignored; keep it between runs so unchanged text is not re-translated. A
state snapshot in `server/scripts/.pw_state` (also gitignored) records the last
published data so an unchanged weekly run produces no new pack; state is only
advanced after a successful publish (or with `--advance-state` for manual
uploads).

## API contract summary

- Authentication: `POST /api/v1/auth/register`, `POST /api/v1/auth/login`.
  Both return `{ "token": "...", "user": { "id": 1, "username": "...",
  "avatar_url": null, "avatar_sha256": null } }`. Register returns 201.
- Passkeys: `POST /api/v1/auth/passkey/{register|login}/{begin|finish}`.
  Begin returns `{ "challenge_id": "...", "options": { ...WebAuthn JSON... } }`;
  finish accepts `{ "challenge_id": "...", "credential": { ... } }`.
  Register begin/finish require the bearer token; login begin/finish do not.
  Login finish returns the same `{token,user}` object.
- Journal: `GET /api/v1/journal` returns the raw exported JSON body with
  `X-Updated-At` and `X-Checksum-Sha256`; `PUT /api/v1/journal` accepts the raw
  JSON object (25 MiB limit, last write wins) and returns
  `{ "sha256": "...", "updated_at": "...", "bytes": 123 }`.
- Catalogue: `GET /api/v1/packs` (JWT) returns an array with snake_case fields
  including `register_name`, `download_url`, `icon_url`, `sha256`,
  `size_bytes`, `version_name`, and `version_code`. Download requires JWT;
  pack icons are public.
- Admin: `POST /api/v1/admin/packs` (multipart `file`, optional `title`,
  `description`, `official_link`) and `DELETE /api/v1/admin/packs/{name}`;
  both require `X-Admin-Token`.
- Errors use `{"error":"code","message":"..."}`. A 401 means the session is
  invalid or expired and the client should clear it.