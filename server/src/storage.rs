use std::path::{Path, PathBuf};

use sha2::{Digest, Sha256};
use uuid::Uuid;

use crate::{config::Config, error::AppError};

#[derive(Debug, Clone, Copy)]
pub struct ImageMime {
    pub mime: &'static str,
    pub extension: &'static str,
}

pub fn sha256_hex(bytes: &[u8]) -> String {
    hex::encode(Sha256::digest(bytes))
}

/// Sniff the three image formats accepted for avatars and pack icons. Using
/// magic bytes rather than the client-supplied content type prevents storing
/// arbitrary content under an image MIME type.
pub fn sniff_image_mime(bytes: &[u8]) -> Option<ImageMime> {
    if bytes.starts_with(b"\x89PNG\r\n\x1a\n") {
        return Some(ImageMime {
            mime: "image/png",
            extension: "png",
        });
    }
    if bytes.starts_with(&[0xFF, 0xD8, 0xFF]) {
        return Some(ImageMime {
            mime: "image/jpeg",
            extension: "jpg",
        });
    }
    if bytes.len() >= 12 && &bytes[0..4] == b"RIFF" && &bytes[8..12] == b"WEBP" {
        return Some(ImageMime {
            mime: "image/webp",
            extension: "webp",
        });
    }
    None
}

pub fn packs_root(config: &Config) -> PathBuf {
    config.data_dir.join("packs")
}

pub fn pack_dir(config: &Config, register_name: &str) -> PathBuf {
    packs_root(config).join(register_name)
}

pub fn pack_zip_path(config: &Config, register_name: &str, version_code: u32) -> PathBuf {
    pack_dir(config, register_name).join(format!("{version_code}.zip"))
}

pub fn pack_icon_path(config: &Config, register_name: &str, file_name: &str) -> PathBuf {
    pack_dir(config, register_name).join(file_name)
}

pub async fn ensure_dirs(config: &Config) -> Result<(), AppError> {
    tokio::fs::create_dir_all(packs_root(config))
        .await
        .map_err(|e| AppError::internal_with("create data directory", e))
}

/// Write via a temporary file in the destination directory so a crash never
/// leaves a partially written pack or icon at the canonical path.
pub async fn write_atomic(path: &Path, bytes: &[u8]) -> Result<(), AppError> {
    if let Some(parent) = path.parent() {
        tokio::fs::create_dir_all(parent)
            .await
            .map_err(|e| AppError::internal_with("create pack directory", e))?;
    }
    let file_name = path
        .file_name()
        .and_then(|name| name.to_str())
        .unwrap_or("file");
    let tmp = path.with_file_name(format!(".{file_name}.{}.tmp", Uuid::new_v4()));
    tokio::fs::write(&tmp, bytes)
        .await
        .map_err(|e| AppError::internal_with("write temp file", e))?;
    // Windows rename does not replace an existing file.
    if tokio::fs::try_exists(path).await.unwrap_or(false) {
        let _ = tokio::fs::remove_file(path).await;
    }
    tokio::fs::rename(&tmp, path)
        .await
        .map_err(|e| AppError::internal_with("rename temp file", e))
}

pub async fn remove_file_if_exists(path: &Path) {
    if tokio::fs::try_exists(path).await.unwrap_or(false) {
        if let Err(error) = tokio::fs::remove_file(path).await {
            tracing::warn!(path = %path.display(), error = %error, "failed to remove file");
        }
    }
}

pub async fn remove_dir_if_exists(path: &Path) {
    if tokio::fs::try_exists(path).await.unwrap_or(false) {
        if let Err(error) = tokio::fs::remove_dir_all(path).await {
            tracing::warn!(path = %path.display(), error = %error, "failed to remove directory");
        }
    }
}
