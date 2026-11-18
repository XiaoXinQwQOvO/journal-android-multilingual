pub mod admin;
pub mod auth;
pub mod journal;
pub mod me;
pub mod packs;
pub mod well_known;

use axum::{
    extract::DefaultBodyLimit,
    middleware,
    routing::{delete, get, post, put},
    Router,
};
use tower_http::trace::TraceLayer;

use crate::{error::AppError, AppState};

pub fn router(state: AppState) -> Router {
    let avatar_limit = state.config.max_avatar_bytes.saturating_add(256 * 1024);
    let pack_limit = state.config.max_pack_bytes.saturating_add(1024 * 1024);

    let public = Router::new()
        .route("/auth/register", post(auth::register))
        .route("/auth/login", post(auth::login))
        .route("/auth/passkey/login/begin", post(auth::passkey_login_begin))
        .route(
            "/auth/passkey/login/finish",
            post(auth::passkey_login_finish),
        )
        .route("/auth/totp/verify", post(auth::totp_verify))
        .route("/users/{id}/avatar", get(me::get_avatar))
        .route("/packs/{name}/icon", get(packs::get_pack_icon));

    let protected = Router::new()
        .route(
            "/auth/passkey/register/begin",
            post(auth::passkey_register_begin),
        )
        .route(
            "/auth/passkey/register/finish",
            post(auth::passkey_register_finish),
        )
        .route("/auth/totp/setup", post(auth::totp_setup))
        .route("/auth/totp/enable", post(auth::totp_enable))
        .route("/auth/totp/disable", post(auth::totp_disable))
        .route("/me", get(me::get_me).patch(me::update_me))
        .route(
            "/me/avatar",
            put(me::upload_avatar).layer(DefaultBodyLimit::max(avatar_limit)),
        )
        .route(
            "/journal",
            get(journal::get_journal).put(journal::put_journal),
        )
        .route("/packs", get(packs::list_packs))
        .route("/packs/{name}/download", get(packs::download_pack))
        .route_layer(middleware::from_fn_with_state(
            state.clone(),
            crate::auth::middleware::require_jwt,
        ));

    let admin = Router::new()
        .route(
            "/admin/packs",
            post(admin::upload_pack).layer(DefaultBodyLimit::max(pack_limit)),
        )
        .route("/admin/packs/{name}", delete(admin::delete_pack))
        .route_layer(middleware::from_fn_with_state(
            state.clone(),
            crate::auth::middleware::require_admin,
        ));

    let api = public
        .merge(protected)
        .merge(admin)
        .method_not_allowed_fallback(method_not_allowed);

    Router::new()
        .route("/healthz", get(healthz))
        .route("/.well-known/assetlinks.json", get(well_known::assetlinks))
        .nest("/api/v1", api)
        .fallback(fallback)
        .method_not_allowed_fallback(method_not_allowed)
        .layer(TraceLayer::new_for_http())
        .with_state(state)
}

async fn healthz() -> &'static str {
    "ok"
}

async fn fallback() -> AppError {
    AppError::not_found("not_found", "route not found")
}

async fn method_not_allowed() -> AppError {
    AppError::new(
        axum::http::StatusCode::METHOD_NOT_ALLOWED,
        "method_not_allowed",
        "method not allowed",
    )
}
