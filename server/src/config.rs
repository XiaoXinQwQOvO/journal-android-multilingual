use std::{env, net::SocketAddr, path::PathBuf};

use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine as _};
use sha2::{Digest, Sha256};
use url::Url;

const DEFAULT_BIND_ADDR: &str = "0.0.0.0:8965";
const DEFAULT_RP_ID: &str = "journal.xiaoxinww.xin";
const DEFAULT_RP_ORIGIN: &str = "https://journal.xiaoxinww.xin";
const DEFAULT_DATA_DIR: &str = "./data";
const DEFAULT_MAX_JOURNAL_BYTES: usize = 26_214_400; // 25 MiB
const DEFAULT_MAX_PACK_BYTES: usize = 104_857_600; // 100 MiB
const DEFAULT_MAX_AVATAR_BYTES: usize = 2_097_152; // 2 MiB

#[derive(Debug, thiserror::Error)]
pub enum ConfigError {
    #[error("missing required environment variable {0}")]
    Missing(&'static str),
    #[error("invalid value for {name}: {message}")]
    Invalid { name: &'static str, message: String },
}

#[derive(Clone)]
pub struct Config {
    pub database_url: String,
    pub jwt_secret: Vec<u8>,
    /// SHA-256 of ADMIN_TOKEN, so the comparison in middleware operates on
    /// fixed-length digests and cannot leak the token length.
    pub admin_token_hash: [u8; 32],
    pub bind_addr: SocketAddr,
    pub rp_id: String,
    pub rp_origin: Url,
    /// `RP_ORIGIN` without a trailing slash.
    pub origin_base: String,
    /// `origin_base` + `/api/v1`, used to build absolute asset URLs.
    pub api_base: String,
    pub data_dir: PathBuf,
    pub max_journal_bytes: usize,
    pub max_pack_bytes: usize,
    pub max_avatar_bytes: usize,
    /// Normalized colon-separated SHA-256 fingerprints for assetlinks.json.
    pub cert_fingerprints: Vec<String>,
    /// `android:apk-key-hash:` WebAuthn origins derived from the fingerprints.
    pub android_origins: Vec<Url>,
}

impl Config {
    pub fn from_env() -> Result<Self, ConfigError> {
        let database_url = required("DATABASE_URL")?;

        let jwt_secret = required("JWT_SECRET")?;
        if jwt_secret.as_bytes().len() < 32 {
            return Err(ConfigError::Invalid {
                name: "JWT_SECRET",
                message: "must be at least 32 bytes".into(),
            });
        }

        let admin_token = required("ADMIN_TOKEN")?;
        if admin_token.as_bytes().len() < 16 {
            return Err(ConfigError::Invalid {
                name: "ADMIN_TOKEN",
                message: "must be at least 16 bytes".into(),
            });
        }
        let mut admin_token_hash = [0u8; 32];
        admin_token_hash.copy_from_slice(&Sha256::digest(admin_token.as_bytes()));

        let bind_addr_raw = optional("BIND_ADDR", DEFAULT_BIND_ADDR);
        let bind_addr = bind_addr_raw
            .parse::<SocketAddr>()
            .map_err(|_| ConfigError::Invalid {
                name: "BIND_ADDR",
                message: "must be an IP:PORT socket address, e.g. 0.0.0.0:8965".into(),
            })?;

        let rp_id = optional("RP_ID", DEFAULT_RP_ID);
        if rp_id.is_empty() {
            return Err(ConfigError::Invalid {
                name: "RP_ID",
                message: "must not be empty".into(),
            });
        }

        let rp_origin_raw = optional("RP_ORIGIN", DEFAULT_RP_ORIGIN);
        let rp_origin = Url::parse(&rp_origin_raw).map_err(|e| ConfigError::Invalid {
            name: "RP_ORIGIN",
            message: e.to_string(),
        })?;
        let origin_base = rp_origin.as_str().trim_end_matches('/').to_owned();
        let api_base = format!("{origin_base}/api/v1");

        let data_dir = PathBuf::from(optional("DATA_DIR", DEFAULT_DATA_DIR));
        if data_dir.as_os_str().is_empty() {
            return Err(ConfigError::Invalid {
                name: "DATA_DIR",
                message: "must not be empty".into(),
            });
        }

        let max_journal_bytes = parse_limit(
            "MAX_JOURNAL_BYTES",
            DEFAULT_MAX_JOURNAL_BYTES,
            64 * 1024 * 1024,
        )?;
        let max_pack_bytes =
            parse_limit("MAX_PACK_BYTES", DEFAULT_MAX_PACK_BYTES, 512 * 1024 * 1024)?;
        let max_avatar_bytes = parse_limit(
            "MAX_AVATAR_BYTES",
            DEFAULT_MAX_AVATAR_BYTES,
            8 * 1024 * 1024,
        )?;

        let cert_fingerprints = parse_cert_fingerprints(&optional("ANDROID_APP_CERT_SHA256", ""))?;
        let android_origins = cert_fingerprints
            .iter()
            .map(|fingerprint| {
                let raw = hex::decode(fingerprint.replace(':', "")).map_err(|e| {
                    ConfigError::Invalid {
                        name: "ANDROID_APP_CERT_SHA256",
                        message: e.to_string(),
                    }
                })?;
                let digest = URL_SAFE_NO_PAD.encode(raw);
                // Native Credential Manager passkeys report this opaque origin
                // in clientDataJSON; webauthn-rs accepts it only when it is in
                // the allowed-origin list (exact URL equality).
                Url::parse(&format!("android:apk-key-hash:{digest}")).map_err(|e| {
                    ConfigError::Invalid {
                        name: "ANDROID_APP_CERT_SHA256",
                        message: format!("could not build android origin: {e}"),
                    }
                })
            })
            .collect::<Result<Vec<_>, _>>()?;

        Ok(Self {
            database_url,
            jwt_secret: jwt_secret.into_bytes(),
            admin_token_hash,
            bind_addr,
            rp_id,
            rp_origin,
            origin_base,
            api_base,
            data_dir,
            max_journal_bytes,
            max_pack_bytes,
            max_avatar_bytes,
            cert_fingerprints,
            android_origins,
        })
    }

    pub fn avatar_url(&self, user_id: u64, sha256: &str) -> String {
        format!("{}/users/{user_id}/avatar?sha={sha256}", self.api_base)
    }

    pub fn pack_download_url(&self, register_name: &str) -> String {
        format!("{}/packs/{register_name}/download", self.api_base)
    }

    pub fn pack_icon_url(&self, register_name: &str, version_code: u32) -> String {
        format!(
            "{}/packs/{register_name}/icon?v={version_code}",
            self.api_base
        )
    }
}

fn required(name: &'static str) -> Result<String, ConfigError> {
    match env::var(name) {
        Ok(value) if !value.trim().is_empty() => Ok(value),
        _ => Err(ConfigError::Missing(name)),
    }
}

fn optional(name: &str, default: &str) -> String {
    env::var(name)
        .ok()
        .filter(|value| !value.is_empty())
        .unwrap_or_else(|| default.to_owned())
}

fn parse_limit(name: &'static str, default: usize, max: usize) -> Result<usize, ConfigError> {
    let raw = optional(name, &default.to_string());
    let value = raw.parse::<usize>().map_err(|_| ConfigError::Invalid {
        name,
        message: "must be a positive integer byte count".into(),
    })?;
    if value == 0 || value > max {
        return Err(ConfigError::Invalid {
            name,
            message: format!("must be between 1 and {max} bytes"),
        });
    }
    Ok(value)
}

/// Accepts comma-separated SHA-256 certificate fingerprints, with or without
/// colon separators, and returns uppercase colon-separated fingerprints.
fn parse_cert_fingerprints(raw: &str) -> Result<Vec<String>, ConfigError> {
    let mut out = Vec::new();
    for part in raw.split(',') {
        let part = part.trim();
        if part.is_empty() {
            continue;
        }
        let hex_only: String = part.chars().filter(|c| *c != ':').collect();
        if hex_only.len() != 64 || !hex_only.chars().all(|c| c.is_ascii_hexdigit()) {
            return Err(ConfigError::Invalid {
                name: "ANDROID_APP_CERT_SHA256",
                message: "each fingerprint must be 32 hex-encoded bytes".into(),
            });
        }
        let upper = hex_only.to_ascii_uppercase();
        let grouped = upper
            .as_bytes()
            .chunks(2)
            .map(|pair| std::str::from_utf8(pair).unwrap_or_default().to_owned())
            .collect::<Vec<_>>()
            .join(":");
        out.push(grouped);
    }
    Ok(out)
}
