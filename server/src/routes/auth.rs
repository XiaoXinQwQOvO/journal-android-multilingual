use chrono::{Duration, Utc};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use sqlx::MySqlPool;
use uuid::Uuid;
use webauthn_rs::prelude::{
    CredentialID, Passkey, PasskeyAuthentication, PasskeyRegistration, PublicKeyCredential,
    RegisterPublicKeyCredential,
};

use axum::{extract::State, http::StatusCode, response::IntoResponse, Json};

use crate::{
    auth::{jwt, middleware::AuthUser, passkey, password, totp},
    error::AppError,
    extract::ApiJson,
    models::{AuthResponse, UserResponse, UserRow},
    validate, AppState,
};

#[derive(Debug, Deserialize)]
pub struct RegisterRequest {
    pub username: String,
    pub password: String,
}

#[derive(Debug, Deserialize)]
pub struct LoginRequest {
    pub username: String,
    pub password: String,
}

#[derive(Debug, Deserialize)]
pub struct TotpVerifyRequest {
    pub totp_token: String,
    pub code: String,
}

#[derive(Debug, Deserialize)]
pub struct TotpCodeRequest {
    pub code: String,
}

#[derive(Debug, Serialize)]
pub struct LoginResponse {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub token: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub user: Option<UserResponse>,
    pub requires_totp: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub totp_token: Option<String>,
}

#[derive(Debug, Serialize)]
pub struct TotpSetupResponse {
    pub secret: String,
    pub otpauth_url: String,
}

#[derive(Debug, Serialize)]
pub struct TotpStateResponse {
    pub totp_enabled: bool,
}

#[derive(Debug, Deserialize)]
pub struct PasskeyLoginBeginRequest {
    pub username: String,
}

#[derive(Debug, Deserialize)]
pub struct PasskeyFinishRequest {
    pub challenge_id: String,
    pub credential: Value,
}

#[derive(Debug, Serialize)]
pub struct ChallengeResponse {
    challenge_id: String,
    options: Value,
}

#[derive(Debug, Serialize)]
pub struct OkResponse {
    ok: bool,
}

const CHALLENGE_TTL_MINUTES: i64 = 5;

pub async fn register(
    State(state): State<AppState>,
    ApiJson(payload): ApiJson<RegisterRequest>,
) -> Result<impl IntoResponse, AppError> {
    validate::validate_username(&payload.username)
        .map_err(|message| AppError::bad_request("invalid_username", message))?;
    validate::validate_password(&payload.password)
        .map_err(|message| AppError::bad_request("invalid_password", message))?;

    let username = payload.username;
    let norm = validate::username_norm(&username);
    let password_for_hash = payload.password;
    let hash = tokio::task::spawn_blocking(move || password::hash_password(&password_for_hash))
        .await
        .map_err(|error| AppError::internal_with("password hashing task", error))?
        .map_err(|error| AppError::internal_with("hash password", error))?;

    let result =
        sqlx::query("INSERT INTO users (username, username_norm, password_hash) VALUES (?, ?, ?)")
            .bind(&username)
            .bind(&norm)
            .bind(&hash)
            .execute(&state.db)
            .await;

    let user_id = match result {
        Ok(result) => result.last_insert_id(),
        Err(sqlx::Error::Database(error)) if error.is_unique_violation() => {
            return Err(AppError::conflict(
                "username_taken",
                "this username is already taken",
            ));
        }
        Err(error) => return Err(error.into()),
    };

    let token = jwt::issue(&state.config, user_id)?;
    Ok((
        StatusCode::CREATED,
        Json(AuthResponse {
            token,
            user: UserResponse::from_parts(user_id, username, None, false, &state.config),
        }),
    ))
}

pub async fn login(
    State(state): State<AppState>,
    ApiJson(payload): ApiJson<LoginRequest>,
) -> Result<Json<LoginResponse>, AppError> {
    let norm = validate::username_norm(&payload.username);
    let user = load_user_by_norm(&state.db, &norm).await?;
    let Some(hash) = user.password_hash.clone() else {
        // Accounts created only through a passkey have no password.
        return Err(AppError::unauthorized("invalid username or password"));
    };

    let password_for_check = payload.password;
    let verified =
        tokio::task::spawn_blocking(move || password::verify_password(&password_for_check, &hash))
            .await
            .map_err(|error| AppError::internal_with("password verification task", error))?;
    if !verified {
        return Err(AppError::unauthorized("invalid username or password"));
    }

    if user.totp_enabled {
        if user.totp_secret.is_none() {
            return Err(AppError::internal_with(
                "totp state",
                "TOTP is enabled without a stored secret",
            ));
        }
        let totp_token = jwt::issue_totp_pending(&state.config, user.id)?;
        return Ok(Json(LoginResponse {
            token: None,
            user: None,
            requires_totp: true,
            totp_token: Some(totp_token),
        }));
    }

    let token = jwt::issue(&state.config, user.id)?;
    Ok(Json(LoginResponse {
        token: Some(token),
        user: Some(UserResponse::from_row(user, &state.config)),
        requires_totp: false,
        totp_token: None,
    }))
}

pub async fn totp_verify(
    State(state): State<AppState>,
    ApiJson(payload): ApiJson<TotpVerifyRequest>,
) -> Result<Json<AuthResponse>, AppError> {
    let user_id = jwt::decode_totp_pending(&state.config, &payload.totp_token)?;
    let user = load_user_by_id(&state.db, user_id).await?;
    if !user.totp_enabled {
        return Err(AppError::unauthorized(
            "two-factor authentication is not enabled",
        ));
    }
    let secret = user
        .totp_secret
        .clone()
        .ok_or_else(|| AppError::internal_with("totp state", "missing TOTP secret"))?;
    let code = parse_totp_code(&payload.code)?;
    if !totp::verify_code(&secret, code, current_unix_time()) {
        return Err(AppError::unauthorized("invalid two-factor code"));
    }
    let token = jwt::issue(&state.config, user.id)?;
    Ok(Json(AuthResponse {
        token,
        user: UserResponse::from_row(user, &state.config),
    }))
}

pub async fn totp_setup(
    State(state): State<AppState>,
    auth: AuthUser,
) -> Result<Json<TotpSetupResponse>, AppError> {
    let user = load_user_by_id(&state.db, auth.0).await?;
    if user.totp_enabled {
        return Err(AppError::conflict(
            "totp_already_enabled",
            "two-factor authentication is already enabled",
        ));
    }
    let secret = totp::generate_secret();
    sqlx::query("UPDATE users SET totp_secret = ?, totp_enabled = FALSE WHERE id = ?")
        .bind(&secret)
        .bind(auth.0)
        .execute(&state.db)
        .await?;
    Ok(Json(TotpSetupResponse {
        secret: totp::secret_base32(&secret),
        otpauth_url: totp::otpauth_url(&secret, "Journal", &user.username),
    }))
}

pub async fn totp_enable(
    State(state): State<AppState>,
    auth: AuthUser,
    ApiJson(payload): ApiJson<TotpCodeRequest>,
) -> Result<Json<TotpStateResponse>, AppError> {
    let user = load_user_by_id(&state.db, auth.0).await?;
    if user.totp_enabled {
        return Err(AppError::conflict(
            "totp_already_enabled",
            "two-factor authentication is already enabled",
        ));
    }
    let secret = user
        .totp_secret
        .clone()
        .ok_or_else(|| AppError::bad_request("totp_setup_required", "start TOTP setup first"))?;
    let code = parse_totp_code(&payload.code)?;
    if !totp::verify_code(&secret, code, current_unix_time()) {
        return Err(AppError::unauthorized("invalid two-factor code"));
    }
    sqlx::query("UPDATE users SET totp_enabled = TRUE WHERE id = ?")
        .bind(auth.0)
        .execute(&state.db)
        .await?;
    Ok(Json(TotpStateResponse { totp_enabled: true }))
}

pub async fn totp_disable(
    State(state): State<AppState>,
    auth: AuthUser,
    ApiJson(payload): ApiJson<TotpCodeRequest>,
) -> Result<Json<TotpStateResponse>, AppError> {
    let user = load_user_by_id(&state.db, auth.0).await?;
    if !user.totp_enabled {
        return Err(AppError::bad_request(
            "totp_not_enabled",
            "two-factor authentication is not enabled",
        ));
    }
    let secret = user
        .totp_secret
        .clone()
        .ok_or_else(|| AppError::internal_with("totp state", "missing TOTP secret"))?;
    let code = parse_totp_code(&payload.code)?;
    if !totp::verify_code(&secret, code, current_unix_time()) {
        return Err(AppError::unauthorized("invalid two-factor code"));
    }
    sqlx::query("UPDATE users SET totp_secret = NULL, totp_enabled = FALSE WHERE id = ?")
        .bind(auth.0)
        .execute(&state.db)
        .await?;
    Ok(Json(TotpStateResponse {
        totp_enabled: false,
    }))
}

fn current_unix_time() -> u64 {
    Utc::now().timestamp().max(0) as u64
}

fn parse_totp_code(raw: &str) -> Result<u32, AppError> {
    let trimmed = raw.trim();
    if trimmed.len() != 6 || !trimmed.chars().all(|c| c.is_ascii_digit()) {
        return Err(AppError::bad_request(
            "invalid_totp_code",
            "code must be 6 digits",
        ));
    }
    trimmed
        .parse::<u32>()
        .map_err(|_| AppError::bad_request("invalid_totp_code", "code must be 6 digits"))
}

pub async fn passkey_register_begin(
    State(state): State<AppState>,
    auth: AuthUser,
) -> Result<Json<ChallengeResponse>, AppError> {
    let user = load_user_by_id(&state.db, auth.0).await?;

    let existing: Vec<(Vec<u8>,)> =
        sqlx::query_as("SELECT credential_id FROM passkey_credentials WHERE user_id = ?")
            .bind(auth.0)
            .fetch_all(&state.db)
            .await?;
    let exclude: Vec<CredentialID> = existing
        .into_iter()
        .map(|(bytes,)| CredentialID::from(bytes))
        .collect();

    let (challenge, registration_state) = state
        .webauthn
        .start_passkey_registration(
            passkey::user_handle(auth.0),
            &user.username,
            &user.username,
            (!exclude.is_empty()).then_some(exclude),
        )
        .map_err(|error| {
            tracing::warn!(error = %error, "could not start passkey registration");
            AppError::bad_request("passkey_error", "could not start passkey registration")
        })?;

    let challenge_id = Uuid::new_v4().to_string();
    let state_json = serde_json::to_string(&registration_state)
        .map_err(|error| AppError::internal_with("serialize passkey state", error))?;
    store_challenge(
        &state.db,
        &challenge_id,
        Some(auth.0),
        "register",
        &state_json,
    )
    .await?;

    Ok(Json(ChallengeResponse {
        challenge_id,
        options: serde_json::to_value(challenge)
            .map_err(|error| AppError::internal_with("serialize challenge", error))?,
    }))
}

pub async fn passkey_register_finish(
    State(state): State<AppState>,
    auth: AuthUser,
    ApiJson(payload): ApiJson<PasskeyFinishRequest>,
) -> Result<Json<OkResponse>, AppError> {
    let challenge_id = canonical_challenge_id(&payload.challenge_id)?;
    let (_, state_json) =
        take_challenge(&state.db, &challenge_id, "register", Some(auth.0)).await?;

    let registration_state: PasskeyRegistration = serde_json::from_value(state_json)
        .map_err(|error| AppError::internal_with("deserialize passkey state", error))?;
    let credential: RegisterPublicKeyCredential = serde_json::from_value(payload.credential)
        .map_err(|error| {
            AppError::bad_request("invalid_credential", format!("invalid credential: {error}"))
        })?;

    let webauthn = state.webauthn.clone();
    let passkey = tokio::task::spawn_blocking(move || {
        webauthn.finish_passkey_registration(&credential, &registration_state)
    })
    .await
    .map_err(|error| AppError::internal_with("passkey registration task", error))?
    .map_err(|error| {
        tracing::warn!(error = %error, "passkey registration rejected");
        AppError::bad_request(
            "invalid_credential",
            "passkey registration could not be verified",
        )
    })?;

    let credential_id = passkey.cred_id().as_ref().to_vec();
    if credential_id.len() > 255 {
        return Err(AppError::bad_request(
            "invalid_credential",
            "credential id is too long",
        ));
    }
    let passkey_json = serde_json::to_string(&passkey)
        .map_err(|error| AppError::internal_with("serialize passkey", error))?;

    let insert = sqlx::query(
        "INSERT INTO passkey_credentials \
         (user_id, credential_id, passkey_json, sign_count, created_at) \
         VALUES (?, ?, ?, 0, ?)",
    )
    .bind(auth.0)
    .bind(&credential_id)
    .bind(&passkey_json)
    .bind(crate::models::now_millis())
    .execute(&state.db)
    .await;

    match insert {
        Ok(_) => Ok(Json(OkResponse { ok: true })),
        Err(sqlx::Error::Database(error)) if error.is_unique_violation() => Err(
            AppError::conflict("credential_exists", "this passkey is already registered"),
        ),
        Err(error) => Err(error.into()),
    }
}

pub async fn passkey_login_begin(
    State(state): State<AppState>,
    ApiJson(payload): ApiJson<PasskeyLoginBeginRequest>,
) -> Result<Json<ChallengeResponse>, AppError> {
    let norm = validate::username_norm(&payload.username);
    let user: Option<(u64,)> = sqlx::query_as("SELECT id FROM users WHERE username_norm = ?")
        .bind(&norm)
        .fetch_optional(&state.db)
        .await?;
    let Some((user_id,)) = user else {
        return Err(AppError::not_found("user_not_found", "user not found"));
    };

    let rows: Vec<(Value,)> =
        sqlx::query_as("SELECT passkey_json FROM passkey_credentials WHERE user_id = ?")
            .bind(user_id)
            .fetch_all(&state.db)
            .await?;
    let mut passkeys: Vec<Passkey> = Vec::with_capacity(rows.len());
    for (json,) in rows {
        match serde_json::from_value::<Passkey>(json) {
            Ok(passkey) => passkeys.push(passkey),
            Err(error) => {
                tracing::error!(user_id, error = %error, "corrupt stored passkey skipped");
            }
        }
    }
    if passkeys.is_empty() {
        return Err(AppError::not_found(
            "no_passkeys",
            "no passkeys are registered for this user",
        ));
    }

    let (challenge, authentication_state) = state
        .webauthn
        .start_passkey_authentication(&passkeys)
        .map_err(|error| {
            tracing::warn!(error = %error, "could not start passkey authentication");
            AppError::bad_request("passkey_error", "could not start passkey login")
        })?;

    let challenge_id = Uuid::new_v4().to_string();
    let state_json = serde_json::to_string(&authentication_state)
        .map_err(|error| AppError::internal_with("serialize passkey state", error))?;
    store_challenge(
        &state.db,
        &challenge_id,
        Some(user_id),
        "login",
        &state_json,
    )
    .await?;

    Ok(Json(ChallengeResponse {
        challenge_id,
        options: serde_json::to_value(challenge)
            .map_err(|error| AppError::internal_with("serialize challenge", error))?,
    }))
}

pub async fn passkey_login_finish(
    State(state): State<AppState>,
    ApiJson(payload): ApiJson<PasskeyFinishRequest>,
) -> Result<Json<AuthResponse>, AppError> {
    let challenge_id = canonical_challenge_id(&payload.challenge_id)?;
    let (user_id, state_json) = take_challenge(&state.db, &challenge_id, "login", None).await?;
    let user_id = user_id
        .ok_or_else(|| AppError::bad_request("invalid_challenge", "challenge has no user"))?;

    let authentication_state: PasskeyAuthentication = serde_json::from_value(state_json)
        .map_err(|error| AppError::internal_with("deserialize passkey state", error))?;
    let credential: PublicKeyCredential =
        serde_json::from_value(payload.credential).map_err(|error| {
            AppError::bad_request("invalid_credential", format!("invalid credential: {error}"))
        })?;

    let webauthn = state.webauthn.clone();
    let result = tokio::task::spawn_blocking(move || {
        webauthn.finish_passkey_authentication(&credential, &authentication_state)
    })
    .await
    .map_err(|error| AppError::internal_with("passkey authentication task", error))?
    .map_err(|error| {
        tracing::warn!(error = %error, "passkey authentication rejected");
        AppError::unauthorized("passkey authentication failed")
    })?;

    let credential_id = result.cred_id().as_ref().to_vec();
    let row: Option<(u64, Value)> = sqlx::query_as(
        "SELECT id, passkey_json FROM passkey_credentials \
         WHERE user_id = ? AND credential_id = ?",
    )
    .bind(user_id)
    .bind(&credential_id)
    .fetch_optional(&state.db)
    .await?;
    let Some((passkey_row_id, passkey_json)) = row else {
        return Err(AppError::unauthorized("passkey authentication failed"));
    };

    let mut passkey: Passkey = serde_json::from_value(passkey_json)
        .map_err(|error| AppError::internal_with("deserialize stored passkey", error))?;
    let updated_json = match passkey.update_credential(&result) {
        Some(true) => Some(
            serde_json::to_string(&passkey)
                .map_err(|error| AppError::internal_with("serialize passkey", error))?,
        ),
        _ => None,
    };
    let sign_count = result.counter() as u64;
    let now = crate::models::now_millis();
    if let Some(updated_json) = updated_json {
        sqlx::query(
            "UPDATE passkey_credentials SET passkey_json = ?, sign_count = ?, \
             last_used_at = ? WHERE id = ?",
        )
        .bind(updated_json)
        .bind(sign_count)
        .bind(now)
        .bind(passkey_row_id)
        .execute(&state.db)
        .await?;
    } else {
        sqlx::query("UPDATE passkey_credentials SET sign_count = ?, last_used_at = ? WHERE id = ?")
            .bind(sign_count)
            .bind(now)
            .bind(passkey_row_id)
            .execute(&state.db)
            .await?;
    }

    let user = load_user_by_id(&state.db, user_id).await?;
    let token = jwt::issue(&state.config, user.id)?;
    Ok(Json(AuthResponse {
        token,
        user: UserResponse::from_row(user, &state.config),
    }))
}

async fn store_challenge(
    db: &MySqlPool,
    challenge_id: &str,
    user_id: Option<u64>,
    kind: &str,
    state_json: &str,
) -> Result<(), AppError> {
    let now = Utc::now().naive_utc();
    let expires_at = now + Duration::minutes(CHALLENGE_TTL_MINUTES);
    let mut tx = db.begin().await?;
    sqlx::query("DELETE FROM webauthn_states WHERE expires_at < ?")
        .bind(now)
        .execute(&mut *tx)
        .await?;
    sqlx::query(
        "INSERT INTO webauthn_states (challenge_id, user_id, kind, state_json, expires_at) \
         VALUES (?, ?, ?, ?, ?)",
    )
    .bind(challenge_id)
    .bind(user_id)
    .bind(kind)
    .bind(state_json)
    .bind(expires_at)
    .execute(&mut *tx)
    .await?;
    tx.commit().await?;
    Ok(())
}

/// Fetch and delete a challenge atomically so it can be used exactly once.
async fn take_challenge(
    db: &MySqlPool,
    challenge_id: &str,
    kind: &str,
    required_user: Option<u64>,
) -> Result<(Option<u64>, Value), AppError> {
    let now = Utc::now().naive_utc();
    let mut tx = db.begin().await?;
    let row: Option<(Option<u64>, Value)> = if let Some(user_id) = required_user {
        sqlx::query_as(
            "SELECT user_id, state_json FROM webauthn_states \
             WHERE challenge_id = ? AND kind = ? AND expires_at > ? AND user_id = ? \
             FOR UPDATE",
        )
        .bind(challenge_id)
        .bind(kind)
        .bind(now)
        .bind(user_id)
        .fetch_optional(&mut *tx)
        .await?
    } else {
        sqlx::query_as(
            "SELECT user_id, state_json FROM webauthn_states \
             WHERE challenge_id = ? AND kind = ? AND expires_at > ? FOR UPDATE",
        )
        .bind(challenge_id)
        .bind(kind)
        .bind(now)
        .fetch_optional(&mut *tx)
        .await?
    };

    let Some(row) = row else {
        tx.rollback().await.ok();
        return Err(AppError::bad_request(
            "invalid_challenge",
            "challenge not found or expired",
        ));
    };

    sqlx::query("DELETE FROM webauthn_states WHERE challenge_id = ? AND kind = ?")
        .bind(challenge_id)
        .bind(kind)
        .execute(&mut *tx)
        .await?;
    tx.commit().await?;
    Ok(row)
}

fn canonical_challenge_id(raw: &str) -> Result<String, AppError> {
    Uuid::parse_str(raw)
        .map(|uuid| uuid.to_string())
        .map_err(|_| AppError::bad_request("invalid_challenge", "malformed challenge id"))
}

async fn load_user_by_norm(db: &MySqlPool, norm: &str) -> Result<UserRow, AppError> {
    let row = sqlx::query_as::<_, UserRow>(
        "SELECT id, username, password_hash, avatar_sha256, totp_secret, totp_enabled \
         FROM users WHERE username_norm = ?",
    )
    .bind(norm)
    .fetch_optional(db)
    .await?;
    row.ok_or_else(|| AppError::unauthorized("invalid username or password"))
}

pub(crate) async fn load_user_by_id(db: &MySqlPool, user_id: u64) -> Result<UserRow, AppError> {
    let row = sqlx::query_as::<_, UserRow>(
        "SELECT id, username, password_hash, avatar_sha256, totp_secret, totp_enabled \
         FROM users WHERE id = ?",
    )
    .bind(user_id)
    .fetch_optional(db)
    .await?;
    row.ok_or_else(|| AppError::unauthorized("account no longer exists"))
}
