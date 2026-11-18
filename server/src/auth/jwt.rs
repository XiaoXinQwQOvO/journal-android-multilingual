use chrono::Utc;
use jsonwebtoken::{decode, encode, Algorithm, DecodingKey, EncodingKey, Header, Validation};
use serde::{Deserialize, Serialize};

use crate::{config::Config, error::AppError};

const TOKEN_TTL_SECONDS: i64 = 30 * 24 * 60 * 60;

#[derive(Debug, Serialize, Deserialize)]
struct Claims {
    sub: String,
    iat: i64,
    exp: i64,
}

pub fn issue(config: &Config, user_id: u64) -> Result<String, AppError> {
    let now = Utc::now().timestamp();
    let claims = Claims {
        sub: user_id.to_string(),
        iat: now,
        exp: now + TOKEN_TTL_SECONDS,
    };
    encode(
        &Header::new(Algorithm::HS256),
        &claims,
        &EncodingKey::from_secret(&config.jwt_secret),
    )
    .map_err(|e| AppError::internal_with("encode JWT", e))
}

pub fn decode_user_id(config: &Config, token: &str) -> Result<u64, AppError> {
    let validation = Validation::new(Algorithm::HS256);
    let data = decode::<Claims>(
        token,
        &DecodingKey::from_secret(&config.jwt_secret),
        &validation,
    )
    .map_err(|_| AppError::unauthorized("invalid or expired token"))?;
    data.claims
        .sub
        .parse::<u64>()
        .map_err(|_| AppError::unauthorized("invalid token subject"))
}
