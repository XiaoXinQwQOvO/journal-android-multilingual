mod auth;
mod config;
mod db;
mod error;
mod extract;
mod models;
mod routes;
mod storage;
mod validate;

use std::sync::Arc;

use axum::Router;
use sqlx::MySqlPool;
use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt, EnvFilter};
use webauthn_rs::prelude::Webauthn;

use config::Config;

#[derive(Clone)]
pub struct AppState {
    pub db: MySqlPool,
    pub config: Arc<Config>,
    pub webauthn: Arc<Webauthn>,
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    dotenvy::dotenv().ok();

    tracing_subscriber::registry()
        .with(
            EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| EnvFilter::new("info,tower_http=info")),
        )
        .with(tracing_subscriber::fmt::layer())
        .init();

    let config = match Config::from_env() {
        Ok(config) => config,
        Err(error) => {
            tracing::error!(error = %error, "invalid configuration");
            std::process::exit(1);
        }
    };

    let pool = match db::connect(&config.database_url).await {
        Ok(pool) => pool,
        Err(error) => {
            tracing::error!(error = %error, "could not connect to MySQL");
            std::process::exit(1);
        }
    };

    if let Err(error) = db::migrate(&pool).await {
        tracing::error!(error = %error, "database migration failed");
        std::process::exit(1);
    }

    if let Err(error) = storage::ensure_dirs(&config).await {
        tracing::error!(error = %error.message, "could not create data directory");
        std::process::exit(1);
    }

    let webauthn = match auth::passkey::build_webauthn(&config) {
        Ok(webauthn) => Arc::new(webauthn),
        Err(error) => {
            tracing::error!(error = %error, "invalid WebAuthn configuration");
            std::process::exit(1);
        }
    };

    if config.cert_fingerprints.is_empty() {
        tracing::warn!(
            "ANDROID_APP_CERT_SHA256 is empty: assetlinks.json will not authorize any app, \
             and native Android passkeys will fail origin validation"
        );
    }

    let state = AppState {
        db: pool,
        config: Arc::new(config),
        webauthn,
    };

    let app: Router = routes::router(state.clone());
    let listener = tokio::net::TcpListener::bind(state.config.bind_addr).await?;
    tracing::info!(
        addr = %state.config.bind_addr,
        "journal server listening; TLS must be terminated by a reverse proxy"
    );

    axum::serve(listener, app)
        .with_graceful_shutdown(shutdown_signal())
        .await?;

    Ok(())
}

async fn shutdown_signal() {
    let ctrl_c = async {
        if let Err(error) = tokio::signal::ctrl_c().await {
            tracing::error!(error = %error, "failed to install Ctrl-C handler");
        }
    };

    #[cfg(unix)]
    let terminate = async {
        match tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate()) {
            Ok(mut signal) => {
                signal.recv().await;
            }
            Err(error) => {
                tracing::error!(error = %error, "failed to install SIGTERM handler");
                std::future::pending::<()>().await;
            }
        }
    };

    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();

    tokio::select! {
        _ = ctrl_c => {},
        _ = terminate => {},
    }
}
