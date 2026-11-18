use uuid::Uuid;
use webauthn_rs::prelude::{Webauthn, WebauthnBuilder};

use crate::config::Config;

/// Build the process-wide WebAuthn relying party. The Android origins are
/// added explicitly because Credential Manager reports
/// `android:apk-key-hash:<sha256>` for native app passkeys rather than the
/// HTTPS RP origin.
pub fn build_webauthn(config: &Config) -> Result<Webauthn, String> {
    let mut builder = WebauthnBuilder::new(&config.rp_id, &config.rp_origin)
        .map_err(|error| {
            format!(
                "RP_ID {:?} is not valid for RP_ORIGIN: {error}",
                config.rp_id
            )
        })?
        .rp_name("Journal");

    for origin in &config.android_origins {
        builder = builder.append_allowed_origin(origin);
    }

    builder.build().map_err(|error| error.to_string())
}

/// Deterministic 16-byte user handle for a numeric account id. It must remain
/// stable for the lifetime of the account (it is embedded in authenticators).
pub fn user_handle(user_id: u64) -> Uuid {
    Uuid::from_u128(user_id as u128)
}
