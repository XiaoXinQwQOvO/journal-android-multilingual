use std::io::{Cursor, Read};

use axum::{
    body::Bytes,
    extract::{multipart::Field, State},
    http::StatusCode,
    Json,
};
use serde::Deserialize;
use url::Url;
use zip::ZipArchive;

use crate::{
    error::AppError,
    extract::{multipart_error, ApiMultipart},
    models::{now_millis, PackResponse, PackRow},
    storage, validate, AppState,
};

const MANIFEST_LIMIT: usize = 64 * 1024;
const ICON_LIMIT: usize = 2 * 1024 * 1024;
const MAX_ZIP_ENTRIES: usize = 10_000;

#[derive(Debug, Deserialize)]
struct PackManifest {
    #[serde(rename = "registerName")]
    register_name: String,
    #[serde(rename = "versionName")]
    version_name: String,
    #[serde(rename = "versionCode")]
    version_code: i64,
    #[serde(rename = "officalLink", alias = "officialLink")]
    official_link: Option<String>,
    #[serde(default)]
    icon: Option<String>,
}

/// Admin upload. The multipart body is capped by the route's
/// `DefaultBodyLimit`; the zip is never extracted to disk, only its manifest
/// and optional icon are read from the archive.
pub async fn upload_pack(
    State(state): State<AppState>,
    ApiMultipart(mut multipart): ApiMultipart,
) -> Result<Json<PackResponse>, AppError> {
    let mut file_bytes: Option<Bytes> = None;
    let mut title: Option<String> = None;
    let mut description: Option<String> = None;
    let mut official_link: Option<String> = None;

    while let Some(field) = multipart.next_field().await.map_err(multipart_error)? {
        match field.name().unwrap_or("") {
            "file" => {
                if file_bytes.is_some() {
                    return Err(AppError::bad_request(
                        "invalid_multipart",
                        "only one file field is allowed",
                    ));
                }
                let data = field.bytes().await.map_err(multipart_error)?;
                if data.is_empty() {
                    return Err(AppError::bad_request("missing_file", "pack file is empty"));
                }
                if data.len() > state.config.max_pack_bytes {
                    return Err(AppError::payload_too_large(format!(
                        "pack must be at most {} bytes",
                        state.config.max_pack_bytes
                    )));
                }
                file_bytes = Some(data);
            }
            "title" => {
                title = non_empty_text(read_text_field(field, 4 * 800).await?);
            }
            "description" => {
                description = non_empty_text(read_text_field(field, 60_000).await?);
            }
            "official_link" => {
                official_link = non_empty_text(read_text_field(field, 4 * 512).await?);
            }
            _ => {}
        }
    }

    let file_bytes = file_bytes.ok_or_else(|| {
        AppError::bad_request("missing_file", "multipart field 'file' is required")
    })?;

    let mut archive = ZipArchive::new(Cursor::new(file_bytes.as_ref()))
        .map_err(|_| AppError::bad_request("invalid_pack", "uploaded file is not a valid zip"))?;
    if archive.len() > MAX_ZIP_ENTRIES {
        return Err(AppError::bad_request(
            "invalid_pack",
            "zip contains too many entries",
        ));
    }

    let manifest_bytes = read_zip_entry(&mut archive, "manifest.json", MANIFEST_LIMIT)?
        .ok_or_else(|| {
            AppError::bad_request("invalid_pack", "zip must contain manifest.json at its root")
        })?;
    let manifest: PackManifest = serde_json::from_slice(&manifest_bytes).map_err(|error| {
        AppError::bad_request("invalid_pack", format!("invalid manifest.json: {error}"))
    })?;

    validate::validate_register_name(&manifest.register_name)
        .map_err(|message| AppError::bad_request("invalid_pack", message))?;
    let version_name = clean_version_name(&manifest.version_name)?;
    if manifest.version_code < 1 || manifest.version_code > u32::MAX as i64 {
        return Err(AppError::bad_request(
            "invalid_pack",
            "versionCode must be a positive 32-bit integer",
        ));
    }
    let version_code = manifest.version_code as u32;

    let official_link = match official_link.or(manifest.official_link) {
        Some(link) => Some(validate_official_link(&link)?),
        None => None,
    };

    let (icon_bytes, icon_file_name) = load_icon(&mut archive, manifest.icon.as_deref())?;
    drop(archive);

    let previous: Option<(u32, Option<String>)> = sqlx::query_as(
        "SELECT version_code, icon_file_name FROM extension_packs WHERE register_name = ?",
    )
    .bind(&manifest.register_name)
    .fetch_optional(&state.db)
    .await?;
    if let Some((previous_version, _)) = &previous {
        if version_code <= *previous_version {
            return Err(AppError::conflict(
                "version_not_incremented",
                format!("versionCode must be greater than {previous_version}"),
            ));
        }
    }

    let register_name = manifest.register_name;
    let title = title.unwrap_or_else(|| register_name.clone());
    if title.chars().count() > 200 {
        return Err(AppError::bad_request(
            "invalid_pack",
            "title must be at most 200 characters",
        ));
    }
    let description = description.unwrap_or_default();

    let sha = storage::sha256_hex(&file_bytes);
    let size_bytes = file_bytes.len() as u64;
    let file_name = format!("{version_code}.zip");

    storage::write_atomic(
        &storage::pack_zip_path(&state.config, &register_name, version_code),
        &file_bytes,
    )
    .await?;
    if let (Some(bytes), Some(name)) = (&icon_bytes, &icon_file_name) {
        storage::write_atomic(
            &storage::pack_icon_path(&state.config, &register_name, name),
            bytes,
        )
        .await?;
    }

    let now = now_millis();
    sqlx::query(
        "INSERT INTO extension_packs \
         (register_name, title, description, official_link, version_name, version_code, \
          sha256, file_name, size_bytes, icon_file_name, created_at, updated_at) \
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) \
         ON DUPLICATE KEY UPDATE title = VALUES(title), description = VALUES(description), \
         official_link = VALUES(official_link), version_name = VALUES(version_name), \
         version_code = VALUES(version_code), sha256 = VALUES(sha256), \
         file_name = VALUES(file_name), size_bytes = VALUES(size_bytes), \
         icon_file_name = VALUES(icon_file_name), updated_at = VALUES(updated_at)",
    )
    .bind(&register_name)
    .bind(&title)
    .bind(&description)
    .bind(&official_link)
    .bind(&version_name)
    .bind(version_code)
    .bind(&sha)
    .bind(&file_name)
    .bind(size_bytes)
    .bind(&icon_file_name)
    .bind(now)
    .bind(now)
    .execute(&state.db)
    .await?;

    if let Some((previous_version, previous_icon)) = previous {
        if previous_version != version_code {
            storage::remove_file_if_exists(&storage::pack_zip_path(
                &state.config,
                &register_name,
                previous_version,
            ))
            .await;
        }
        if let Some(previous_icon) = previous_icon {
            if icon_file_name.as_deref() != Some(previous_icon.as_str()) {
                storage::remove_file_if_exists(&storage::pack_icon_path(
                    &state.config,
                    &register_name,
                    &previous_icon,
                ))
                .await;
            }
        }
    }

    let row = sqlx::query_as::<_, PackRow>(
        "SELECT register_name, title, description, official_link, version_name, \
         version_code, sha256, size_bytes, icon_file_name, updated_at \
         FROM extension_packs WHERE register_name = ?",
    )
    .bind(&register_name)
    .fetch_one(&state.db)
    .await?;

    Ok(Json(row.into_response(&state.config)))
}

pub async fn delete_pack(
    State(state): State<AppState>,
    crate::extract::ApiPath(register_name): crate::extract::ApiPath<String>,
) -> Result<StatusCode, AppError> {
    let result = sqlx::query("DELETE FROM extension_packs WHERE register_name = ?")
        .bind(&register_name)
        .execute(&state.db)
        .await?;
    if result.rows_affected() == 0 {
        return Err(AppError::not_found(
            "pack_not_found",
            "extension pack not found",
        ));
    }
    storage::remove_dir_if_exists(&storage::pack_dir(&state.config, &register_name)).await;
    Ok(StatusCode::NO_CONTENT)
}

async fn read_text_field(field: Field<'_>, max_bytes: usize) -> Result<String, AppError> {
    let bytes = field.bytes().await.map_err(multipart_error)?;
    if bytes.len() > max_bytes {
        return Err(AppError::bad_request(
            "invalid_multipart",
            "form field is too large",
        ));
    }
    String::from_utf8(bytes.to_vec())
        .map_err(|_| AppError::bad_request("invalid_multipart", "form field must be UTF-8"))
}

fn non_empty_text(value: String) -> Option<String> {
    let trimmed = value.trim();
    if trimmed.is_empty() {
        None
    } else {
        Some(trimmed.to_owned())
    }
}

fn clean_version_name(raw: &str) -> Result<String, AppError> {
    let trimmed = raw.trim();
    if trimmed.is_empty() || trimmed.chars().count() > 64 || trimmed.chars().any(char::is_control) {
        return Err(AppError::bad_request(
            "invalid_pack",
            "versionName must be 1-64 printable characters",
        ));
    }
    Ok(trimmed.to_owned())
}

fn validate_official_link(raw: &str) -> Result<String, AppError> {
    if raw.chars().count() > 512 || raw.chars().any(char::is_control) {
        return Err(AppError::bad_request(
            "invalid_pack",
            "official_link must be at most 512 printable characters",
        ));
    }
    let url = Url::parse(raw)
        .map_err(|_| AppError::bad_request("invalid_pack", "official_link is not a valid URL"))?;
    if !matches!(url.scheme(), "http" | "https") {
        return Err(AppError::bad_request(
            "invalid_pack",
            "official_link must use http or https",
        ));
    }
    Ok(raw.to_owned())
}

fn load_icon(
    archive: &mut ZipArchive<Cursor<&[u8]>>,
    manifest_icon: Option<&str>,
) -> Result<(Option<Vec<u8>>, Option<String>), AppError> {
    let candidates: Vec<&str> = match manifest_icon {
        Some(path) if !path.is_empty() => {
            if !validate::is_safe_zip_relative_path(path) {
                return Err(AppError::bad_request(
                    "invalid_pack",
                    "manifest icon path must be a safe relative path",
                ));
            }
            vec![path]
        }
        _ => vec!["icon.png", "icon.jpg", "icon.jpeg", "icon.webp"],
    };

    for candidate in candidates {
        if let Some(bytes) = read_zip_entry(archive, candidate, ICON_LIMIT)? {
            let image = storage::sniff_image_mime(&bytes).ok_or_else(|| {
                AppError::bad_request("invalid_pack", "pack icon must be PNG, JPEG or WebP")
            })?;
            return Ok((Some(bytes), Some(format!("icon.{}", image.extension))));
        }
    }
    Ok((None, None))
}

/// Read one archive member without extracting it. Returns None when the entry
/// does not exist; all other malformed-zip conditions are a 400.
fn read_zip_entry(
    archive: &mut ZipArchive<Cursor<&[u8]>>,
    name: &str,
    limit: usize,
) -> Result<Option<Vec<u8>>, AppError> {
    let mut entry = match archive.by_name(name) {
        Ok(entry) => entry,
        Err(zip::result::ZipError::FileNotFound) => return Ok(None),
        Err(_) => {
            return Err(AppError::bad_request(
                "invalid_pack",
                "zip entry could not be read",
            ))
        }
    };
    if entry.is_dir() {
        return Ok(None);
    }
    if entry.size() > limit as u64 {
        return Err(AppError::bad_request(
            "invalid_pack",
            format!("zip entry {name} is too large"),
        ));
    }
    let mut bytes = Vec::with_capacity(entry.size() as usize);
    entry
        .by_ref()
        .take(limit as u64 + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| AppError::bad_request("invalid_pack", "zip entry could not be read"))?;
    if bytes.len() > limit {
        return Err(AppError::bad_request(
            "invalid_pack",
            format!("zip entry {name} is too large"),
        ));
    }
    Ok(Some(bytes))
}
