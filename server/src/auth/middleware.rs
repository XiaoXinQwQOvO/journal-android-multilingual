use sha2::{Digest, Sha256};
use subtle::ConstantTimeEq;

use axum::{
    extract::{Request, State},
    http::header,
    middleware::Next,
    response::Response,
};

use crate::{auth::jwt, error::AppError, AppState};

#[derive(Debug, Clone, Copy)]
pub struct AuthUser(pub u64);

pub async fn require_jwt(
    State(state): State<AppState>,
    mut request: Request,
    next: Next,
) -> Result<Response, AppError> {
    let token = request
        .headers()
        .get(header::AUTHORIZATION)
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.strip_prefix("Bearer "))
        .filter(|token| !token.is_empty())
        .ok_or_else(|| AppError::unauthorized("missing bearer token"))?;

    let user_id = jwt::decode_user_id(&state.config, token)?;
    request.extensions_mut().insert(AuthUser(user_id));
    Ok(next.run(request).await)
}

pub async fn require_admin(
    State(state): State<AppState>,
    request: Request,
    next: Next,
) -> Result<Response, AppError> {
    let provided = request
        .headers()
        .get("x-admin-token")
        .map(|value| value.as_bytes())
        .unwrap_or_default();
    // Compare digests rather than raw tokens so lengths are identical and the
    // check cannot reveal the configured token's length.
    let digest = Sha256::digest(provided);
    let matches = digest
        .as_slice()
        .ct_eq(&state.config.admin_token_hash[..])
        .unwrap_u8()
        == 1;
    if !matches {
        return Err(AppError::unauthorized("invalid admin token"));
    }
    Ok(next.run(request).await)
}

impl<S> axum::extract::FromRequestParts<S> for AuthUser
where
    S: Send + Sync,
{
    type Rejection = AppError;

    async fn from_request_parts(
        parts: &mut axum::http::request::Parts,
        _state: &S,
    ) -> Result<Self, Self::Rejection> {
        parts
            .extensions
            .get::<AuthUser>()
            .copied()
            .ok_or_else(|| AppError::unauthorized("authentication required"))
    }
}
