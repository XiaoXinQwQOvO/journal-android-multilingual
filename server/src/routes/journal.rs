use axum::{
    body::to_bytes,
    extract::{Request, State},
    http::{header, HeaderMap, HeaderName, HeaderValue},
    Json,
};
use serde::Serialize;
use serde_json::Value;

use crate::{
    auth::middleware::AuthUser,
    error::AppError,
    models::{now_millis, rfc3339, JournalRow},
    storage, AppState,
};

const UPDATED_AT_HEADER: HeaderName = HeaderName::from_static("x-updated-at");
const CHECKSUM_HEADER: HeaderName = HeaderName::from_static("x-checksum-sha256");

#[derive(Debug, Serialize)]
pub struct JournalPutResponse {
    pub sha256: String,
    pub updated_at: String,
    pub bytes: u64,
}

pub async fn get_journal(
    State(state): State<AppState>,
    auth: AuthUser,
) -> Result<(HeaderMap, String), AppError> {
    let row = sqlx::query_as::<_, JournalRow>(
        "SELECT payload, sha256, updated_at \
         FROM journal_snapshots WHERE user_id = ?",
    )
    .bind(auth.0)
    .fetch_optional(&state.db)
    .await?;
    let Some(row) = row else {
        return Err(AppError::not_found(
            "journal_not_found",
            "no journal has been uploaded yet",
        ));
    };

    let mut headers = HeaderMap::new();
    headers.insert(
        header::CONTENT_TYPE,
        HeaderValue::from_static("application/json; charset=utf-8"),
    );
    headers.insert(
        UPDATED_AT_HEADER,
        HeaderValue::from_str(&rfc3339(row.updated_at))
            .map_err(|e| AppError::internal_with("journal timestamp header", e))?,
    );
    headers.insert(
        CHECKSUM_HEADER,
        HeaderValue::from_str(&row.sha256)
            .map_err(|e| AppError::internal_with("journal checksum header", e))?,
    );
    Ok((headers, row.payload))
}

pub async fn put_journal(
    State(state): State<AppState>,
    auth: AuthUser,
    request: Request,
) -> Result<Json<JournalPutResponse>, AppError> {
    let body = to_bytes(request.into_body(), state.config.max_journal_bytes)
        .await
        .map_err(|_| {
            AppError::payload_too_large(format!(
                "journal must be at most {} bytes",
                state.config.max_journal_bytes
            ))
        })?;

    let text = std::str::from_utf8(&body).map_err(|_| {
        AppError::bad_request("journal_invalid_json", "journal must be valid UTF-8 JSON")
    })?;
    let value: Value = serde_json::from_str(text).map_err(|error| {
        AppError::bad_request(
            "journal_invalid_json",
            format!("journal is not valid JSON: {error}"),
        )
    })?;
    if !value.is_object() {
        return Err(AppError::bad_request(
            "journal_invalid_json",
            "journal must be a JSON object",
        ));
    }
    drop(value);

    let sha = storage::sha256_hex(&body);
    let now = now_millis();
    sqlx::query(
        "INSERT INTO journal_snapshots (user_id, payload, payload_bytes, sha256, updated_at) \
         VALUES (?, ?, ?, ?, ?) \
         ON DUPLICATE KEY UPDATE payload = VALUES(payload), \
         payload_bytes = VALUES(payload_bytes), sha256 = VALUES(sha256), \
         updated_at = VALUES(updated_at)",
    )
    .bind(auth.0)
    .bind(text)
    .bind(body.len() as u32)
    .bind(&sha)
    .bind(now)
    .execute(&state.db)
    .await?;

    Ok(Json(JournalPutResponse {
        sha256: sha,
        updated_at: rfc3339(now),
        bytes: body.len() as u64,
    }))
}
