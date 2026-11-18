use axum::{
    body::Body,
    extract::State,
    http::{header, HeaderMap, HeaderValue, StatusCode},
    response::Response,
    Json,
};
use serde::{Deserialize, Serialize};

use crate::{
    auth::middleware::AuthUser,
    error::AppError,
    extract::{multipart_error, ApiJson, ApiMultipart, ApiPath, ApiQuery},
    models::UserResponse,
    routes::auth::load_user_by_id,
    storage, validate, AppState,
};

#[derive(Debug, Deserialize)]
pub struct UpdateMeRequest {
    pub username: String,
}

#[derive(Debug, Deserialize)]
pub struct AvatarQuery {
    pub sha: Option<String>,
}

#[derive(Debug, Serialize)]
pub struct AvatarResponse {
    pub avatar_url: String,
    pub avatar_sha256: String,
}

pub async fn get_me(
    State(state): State<AppState>,
    auth: AuthUser,
) -> Result<Json<UserResponse>, AppError> {
    let user = load_user_by_id(&state.db, auth.0).await?;
    Ok(Json(UserResponse::from_row(user, &state.config)))
}

pub async fn update_me(
    State(state): State<AppState>,
    auth: AuthUser,
    ApiJson(payload): ApiJson<UpdateMeRequest>,
) -> Result<Json<UserResponse>, AppError> {
    validate::validate_username(&payload.username)
        .map_err(|message| AppError::bad_request("invalid_username", message))?;
    let norm = validate::username_norm(&payload.username);

    let update = sqlx::query("UPDATE users SET username = ?, username_norm = ? WHERE id = ?")
        .bind(&payload.username)
        .bind(&norm)
        .bind(auth.0)
        .execute(&state.db)
        .await;
    match update {
        Ok(_) => {}
        Err(sqlx::Error::Database(error)) if error.is_unique_violation() => {
            return Err(AppError::conflict(
                "username_taken",
                "this username is already taken",
            ));
        }
        Err(error) => return Err(error.into()),
    }

    let user = load_user_by_id(&state.db, auth.0).await?;
    Ok(Json(UserResponse::from_row(user, &state.config)))
}

pub async fn upload_avatar(
    State(state): State<AppState>,
    auth: AuthUser,
    ApiMultipart(mut multipart): ApiMultipart,
) -> Result<Json<AvatarResponse>, AppError> {
    let mut file_bytes: Option<Vec<u8>> = None;
    while let Some(field) = multipart.next_field().await.map_err(multipart_error)? {
        if field.name() != Some("file") {
            continue;
        }
        if file_bytes.is_some() {
            return Err(AppError::bad_request(
                "invalid_multipart",
                "only one file field is allowed",
            ));
        }
        let data = field.bytes().await.map_err(multipart_error)?;
        if data.is_empty() {
            return Err(AppError::bad_request("missing_file", "file is empty"));
        }
        if data.len() > state.config.max_avatar_bytes {
            return Err(AppError::payload_too_large(format!(
                "avatar must be at most {} bytes",
                state.config.max_avatar_bytes
            )));
        }
        file_bytes = Some(data.to_vec());
    }

    let bytes = file_bytes.ok_or_else(|| {
        AppError::bad_request("missing_file", "multipart field 'file' is required")
    })?;
    let image = storage::sniff_image_mime(&bytes).ok_or_else(|| {
        AppError::bad_request("unsupported_image", "avatar must be PNG, JPEG or WebP")
    })?;
    let sha = storage::sha256_hex(&bytes);

    sqlx::query("UPDATE users SET avatar = ?, avatar_mime = ?, avatar_sha256 = ? WHERE id = ?")
        .bind(&bytes)
        .bind(image.mime)
        .bind(&sha)
        .bind(auth.0)
        .execute(&state.db)
        .await?;

    Ok(Json(AvatarResponse {
        avatar_url: state.config.avatar_url(auth.0, &sha),
        avatar_sha256: sha,
    }))
}

pub async fn get_avatar(
    State(state): State<AppState>,
    ApiPath(user_id): ApiPath<u64>,
    ApiQuery(query): ApiQuery<AvatarQuery>,
    request_headers: HeaderMap,
) -> Result<Response, AppError> {
    let row: Option<(Option<Vec<u8>>, Option<String>, Option<String>)> =
        sqlx::query_as("SELECT avatar, avatar_mime, avatar_sha256 FROM users WHERE id = ?")
            .bind(user_id)
            .fetch_optional(&state.db)
            .await?;

    let Some((Some(avatar), mime, Some(sha))) = row else {
        return Err(AppError::not_found("avatar_not_found", "avatar not found"));
    };
    if let Some(requested) = query.sha.as_deref() {
        if requested != sha {
            return Err(AppError::not_found(
                "avatar_not_found",
                "avatar has changed",
            ));
        }
    }

    let mime = mime.unwrap_or_else(|| "application/octet-stream".to_owned());
    let etag = format!("\"{sha}\"");
    let cache_control = if query.sha.is_some() {
        "public, max-age=31536000, immutable"
    } else {
        "public, max-age=300"
    };

    let mut headers = HeaderMap::new();
    headers.insert(
        header::CONTENT_TYPE,
        HeaderValue::from_str(&mime)
            .unwrap_or_else(|_| HeaderValue::from_static("application/octet-stream")),
    );
    headers.insert(
        header::ETAG,
        HeaderValue::from_str(&etag).map_err(|e| AppError::internal_with("avatar etag", e))?,
    );
    headers.insert(
        header::CACHE_CONTROL,
        HeaderValue::from_static(cache_control),
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

    let mut response = Response::new(Body::from(avatar));
    *response.headers_mut() = headers;
    Ok(response)
}
