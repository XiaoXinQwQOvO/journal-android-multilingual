use chrono::Utc;
use jsonwebtoken::{decode, encode, Algorithm, DecodingKey, EncodingKey, Header, Validation};
use serde::{Deserialize, Serialize};

use crate::{config::Config, error::AppError};

const TOKEN_TTL_SECONDS: i64 = 30 * 24 * 60 * 60;
const TOTP_TOKEN_TTL_SECONDS: i64 = 5 * 60;

#[derive(Debug, Serialize, Deserialize)]
struct Claims {
    sub: String,
    iat: i64,
    exp: i64,
    // Pending two-factor tokens carry "totp"; normal access tokens leave it unset.
    #[serde(default)]
    purpose: Option<String>,
}

pub fn issue(config: &Config, user_id: u64) -> Result<String, AppError> {
    encode_claims(config, user_id, TOKEN_TTL_SECONDS, None)
}

/// Short-lived token issued after a correct password when TOTP is enabled.
pub fn issue_totp_pending(config: &Config, user_id: u64) -> Result<String, AppError> {
    encode_claims(
        config,
        user_id,
        TOTP_TOKEN_TTL_SECONDS,
        Some("totp".to_string()),
    )
}

fn encode_claims(
    config: &Config,
    user_id: u64,
    ttl_seconds: i64,
    purpose: Option<String>,
) -> Result<String, AppError> {
    let now = Utc::now().timestamp();
    let claims = Claims {
        sub: user_id.to_string(),
        iat: now,
        exp: now + ttl_seconds,
        purpose,
    };
    encode(
        &Header::new(Algorithm::HS256),
        &claims,
        &EncodingKey::from_secret(&config.jwt_secret),
    )
    .map_err(|e| AppError::internal_with("encode JWT", e))
}

pub fn decode_user_id(config: &Config, token: &str) -> Result<u64, AppError> {
    let claims = decode_claims(config, token)?;
    if claims.purpose.is_some() {
        return Err(AppError::unauthorized("invalid or expired token"));
    }
    claims
        .sub
        .parse::<u64>()
        .map_err(|_| AppError::unauthorized("invalid token subject"))
}

pub fn decode_totp_pending(config: &Config, token: &str) -> Result<u64, AppError> {
    let claims = decode_claims(config, token)?;
    if claims.purpose.as_deref() != Some("totp") {
        return Err(AppError::unauthorized("invalid two-factor token"));
    }
    claims
        .sub
        .parse::<u64>()
        .map_err(|_| AppError::unauthorized("invalid token subject"))
}

fn decode_claims(config: &Config, token: &str) -> Result<Claims, AppError> {
    let validation = Validation::new(Algorithm::HS256);
    let data = decode::<Claims>(
        token,
        &DecodingKey::from_secret(&config.jwt_secret),
        &validation,
    )
    .map_err(|_| AppError::unauthorized("invalid or expired token"))?;
    Ok(data.claims)
}
