use axum::{
    body::Body,
    extract::State,
    http::{header, HeaderMap, HeaderValue, StatusCode},
    response::Response,
    Json,
};
use serde::Deserialize;
use tokio_util::io::ReaderStream;

use crate::{
    error::AppError,
    extract::{ApiPath, ApiQuery},
    models::{PackResponse, PackRow},
    storage, AppState,
};

pub async fn list_packs(
    State(state): State<AppState>,
) -> Result<Json<Vec<PackResponse>>, AppError> {
    let rows = sqlx::query_as::<_, PackRow>(
        "SELECT register_name, title, description, official_link, version_name, \
         version_code, sha256, size_bytes, icon_file_name, updated_at \
         FROM extension_packs ORDER BY register_name",
    )
    .fetch_all(&state.db)
    .await?;
    Ok(Json(
        rows.into_iter()
            .map(|row| row.into_response(&state.config))
            .collect(),
    ))
}

pub async fn download_pack(
    State(state): State<AppState>,
    ApiPath(register_name): ApiPath<String>,
) -> Result<Response, AppError> {
    let row: Option<(u32, String)> =
        sqlx::query_as("SELECT version_code, sha256 FROM extension_packs WHERE register_name = ?")
            .bind(&register_name)
            .fetch_optional(&state.db)
            .await?;
    let Some((version_code, sha)) = row else {
        return Err(AppError::not_found(
            "pack_not_found",
            "extension pack not found",
        ));
    };

    let path = storage::pack_zip_path(&state.config, &register_name, version_code);
    let file = tokio::fs::File::open(&path).await.map_err(|error| {
        if error.kind() == std::io::ErrorKind::NotFound {
            AppError::not_found("pack_file_missing", "extension pack file is missing")
        } else {
            AppError::internal_with("open pack file", error)
        }
    })?;
    let content_length = file
        .metadata()
        .await
        .map_err(|e| AppError::internal_with("pack file metadata", e))?
        .len();

    let mut headers = HeaderMap::new();
    headers.insert(
        header::CONTENT_TYPE,
        HeaderValue::from_static("application/zip"),
    );
    headers.insert(
        header::CONTENT_LENGTH,
        HeaderValue::from_str(&content_length.to_string())
            .map_err(|e| AppError::internal_with("pack content-length", e))?,
    );
    headers.insert(
        header::HeaderName::from_static("x-checksum-sha256"),
        HeaderValue::from_str(&sha)
            .map_err(|e| AppError::internal_with("pack checksum header", e))?,
    );
    headers.insert(
        header::CONTENT_DISPOSITION,
        HeaderValue::from_str(&format!(
            "attachment; filename=\"{register_name}-{version_code}.zip\""
        ))
        .map_err(|e| AppError::internal_with("pack content-disposition", e))?,
    );
    headers.insert(
        header::ETAG,
        HeaderValue::from_str(&format!("\"{sha}\""))
            .map_err(|e| AppError::internal_with("pack etag", e))?,
    );

    let mut response = Response::new(Body::from_stream(ReaderStream::new(file)));
    *response.headers_mut() = headers;
    Ok(response)
}

#[derive(Debug, Deserialize)]
pub struct IconQuery {
    pub v: Option<u32>,
}

pub async fn get_pack_icon(
    State(state): State<AppState>,
    ApiPath(register_name): ApiPath<String>,
    ApiQuery(query): ApiQuery<IconQuery>,
    request_headers: HeaderMap,
) -> Result<Response, AppError> {
    let row: Option<(Option<String>,)> =
        sqlx::query_as("SELECT icon_file_name FROM extension_packs WHERE register_name = ?")
            .bind(&register_name)
            .fetch_optional(&state.db)
            .await?;
    let Some((Some(icon_file_name),)) = row else {
        return Err(AppError::not_found(
            "icon_not_found",
            "extension pack has no icon",
        ));
    };

    let path = storage::pack_icon_path(&state.config, &register_name, &icon_file_name);
    let bytes = tokio::fs::read(&path).await.map_err(|error| {
        if error.kind() == std::io::ErrorKind::NotFound {
            AppError::not_found("icon_file_missing", "extension pack icon is missing")
        } else {
            AppError::internal_with("read pack icon", error)
        }
    })?;
    let mime = storage::sniff_image_mime(&bytes)
        .map(|image| image.mime)
        .unwrap_or("application/octet-stream");
    let sha = storage::sha256_hex(&bytes);
    let etag = format!("\"{sha}\"");

    let mut headers = HeaderMap::new();
    headers.insert(header::CONTENT_TYPE, HeaderValue::from_static(mime));
    headers.insert(
        header::ETAG,
        HeaderValue::from_str(&etag).map_err(|e| AppError::internal_with("icon etag", e))?,
    );
    headers.insert(
        header::CACHE_CONTROL,
        HeaderValue::from_static(if query.v.is_some() {
            // The listing appends ?v=<versionCode>, so this URL is version-scoped.
            "public, max-age=31536000, immutable"
        } else {
            "public, max-age=300"
        }),
    );

    if request_headers
        .get(header::IF_NONE_MATCH)
        .and_then(|value| value.to_str().ok())
        == Some(etag.as_str())
    {
        let mut response = Response::new(Body::empty());
        *response.status_mut() = StatusCode::NOT_MODIFIED;
        *response.headers_mut() = headers;
        return Ok(response);
    }

    let mut response = Response::new(Body::from(bytes));
    *response.headers_mut() = headers;
    Ok(response)
}
