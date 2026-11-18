use axum::{
    extract::State,
    http::{header, HeaderMap, HeaderValue},
    Json,
};
use serde::Serialize;

use crate::AppState;

#[derive(Debug, Serialize)]
pub struct Statement {
    relation: Vec<&'static str>,
    target: Target,
}

#[derive(Debug, Serialize)]
pub struct Target {
    namespace: &'static str,
    package_name: &'static str,
    sha256_cert_fingerprints: Vec<String>,
}

pub async fn assetlinks(State(state): State<AppState>) -> (HeaderMap, Json<Vec<Statement>>) {
    let statement = Statement {
        // get_login_creds is what Credential Manager requires for passkeys;
        // handle_all_urls covers App Link verification for the same app.
        relation: vec![
            "delegate_permission/common.get_login_creds",
            "delegate_permission/common.handle_all_urls",
        ],
        target: Target {
            namespace: "android_app",
            package_name: "in.kawaiis.journal",
            sha256_cert_fingerprints: state.config.cert_fingerprints.clone(),
        },
    };
    let mut headers = HeaderMap::new();
    headers.insert(
        header::CACHE_CONTROL,
        HeaderValue::from_static("public, max-age=300"),
    );
    (headers, Json(vec![statement]))
}
