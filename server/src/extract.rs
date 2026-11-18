use axum::{
    body::to_bytes,
    extract::{
        multipart::MultipartError, FromRequest, FromRequestParts, Multipart, Path, Query, Request,
    },
    http::{request::Parts, StatusCode},
};
use serde::de::DeserializeOwned;

use crate::error::AppError;

/// JSON request extractor that always answers with the API's error envelope
/// instead of axum's plain-text rejection bodies.
pub struct ApiJson<T>(pub T);

const JSON_BODY_LIMIT: usize = 1024 * 1024;

impl<S, T> FromRequest<S> for ApiJson<T>
where
    S: Send + Sync,
    T: DeserializeOwned,
{
    type Rejection = AppError;

    async fn from_request(req: Request, _state: &S) -> Result<Self, Self::Rejection> {
        let bytes = to_bytes(req.into_body(), JSON_BODY_LIMIT)
            .await
            .map_err(|_| AppError::payload_too_large("request body exceeds 1 MiB"))?;
        let value = serde_json::from_slice(&bytes)
            .map_err(|e| AppError::bad_request("invalid_json", format!("invalid JSON: {e}")))?;
        Ok(ApiJson(value))
    }
}

pub struct ApiMultipart(pub Multipart);

pub struct ApiPath<T>(pub T);

pub struct ApiQuery<T>(pub T);

impl<S, T> FromRequestParts<S> for ApiPath<T>
where
    S: Send + Sync,
    T: DeserializeOwned + Send,
{
    type Rejection = AppError;

    async fn from_request_parts(parts: &mut Parts, state: &S) -> Result<Self, Self::Rejection> {
        Path::<T>::from_request_parts(parts, state)
            .await
            .map(|Path(value)| ApiPath(value))
            .map_err(|_| AppError::bad_request("invalid_path", "invalid path parameter"))
    }
}

impl<S, T> FromRequestParts<S> for ApiQuery<T>
where
    S: Send + Sync,
    T: DeserializeOwned + Send,
{
    type Rejection = AppError;

    async fn from_request_parts(parts: &mut Parts, state: &S) -> Result<Self, Self::Rejection> {
        Query::<T>::from_request_parts(parts, state)
            .await
            .map(|Query(value)| ApiQuery(value))
            .map_err(|_| AppError::bad_request("invalid_query", "invalid query parameter"))
    }
}

impl<S> FromRequest<S> for ApiMultipart
where
    S: Send + Sync,
{
    type Rejection = AppError;

    async fn from_request(req: Request, state: &S) -> Result<Self, Self::Rejection> {
        Multipart::from_request(req, state)
            .await
            .map(ApiMultipart)
            .map_err(|error| multipart_status_error(error.status()))
    }
}

pub fn multipart_error(error: MultipartError) -> AppError {
    multipart_status_error(error.status())
}

fn multipart_status_error(status: StatusCode) -> AppError {
    if status == StatusCode::PAYLOAD_TOO_LARGE {
        AppError::payload_too_large("request body too large")
    } else {
        AppError::bad_request("invalid_multipart", "malformed multipart body")
    }
}
