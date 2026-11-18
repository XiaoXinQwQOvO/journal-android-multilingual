use chrono::{DateTime, NaiveDateTime, SecondsFormat, Timelike, Utc};
use serde::Serialize;

use crate::config::Config;

#[derive(Debug, sqlx::FromRow)]
pub struct UserRow {
    pub id: u64,
    pub username: String,
    pub password_hash: Option<String>,
    pub avatar_sha256: Option<String>,
}

#[derive(Debug, Serialize)]
pub struct UserResponse {
    pub id: u64,
    pub username: String,
    pub avatar_url: Option<String>,
    pub avatar_sha256: Option<String>,
}

impl UserResponse {
    pub fn from_parts(
        id: u64,
        username: String,
        avatar_sha256: Option<String>,
        config: &Config,
    ) -> Self {
        let avatar_url = avatar_sha256.as_ref().map(|sha| config.avatar_url(id, sha));
        Self {
            id,
            username,
            avatar_url,
            avatar_sha256,
        }
    }

    pub fn from_row(row: UserRow, config: &Config) -> Self {
        Self::from_parts(row.id, row.username, row.avatar_sha256, config)
    }
}

#[derive(Debug, Serialize)]
pub struct AuthResponse {
    pub token: String,
    pub user: UserResponse,
}

#[derive(Debug, sqlx::FromRow)]
pub struct PackRow {
    pub register_name: String,
    pub title: String,
    pub description: String,
    pub official_link: Option<String>,
    pub version_name: String,
    pub version_code: u32,
    pub sha256: String,
    pub size_bytes: u64,
    pub icon_file_name: Option<String>,
    pub updated_at: NaiveDateTime,
}

#[derive(Debug, Serialize)]
pub struct PackResponse {
    pub register_name: String,
    pub title: String,
    pub description: String,
    pub official_link: Option<String>,
    pub version_name: String,
    pub version_code: u32,
    pub sha256: String,
    pub size_bytes: u64,
    pub download_url: String,
    pub icon_url: Option<String>,
    pub updated_at: String,
}

impl PackRow {
    pub fn into_response(self, config: &Config) -> PackResponse {
        let download_url = config.pack_download_url(&self.register_name);
        let icon_url = self
            .icon_file_name
            .as_ref()
            .map(|_| config.pack_icon_url(&self.register_name, self.version_code));
        PackResponse {
            register_name: self.register_name,
            title: self.title,
            description: self.description,
            official_link: self.official_link,
            version_name: self.version_name,
            version_code: self.version_code,
            sha256: self.sha256,
            size_bytes: self.size_bytes,
            download_url,
            icon_url,
            updated_at: rfc3339(self.updated_at),
        }
    }
}

#[derive(Debug, sqlx::FromRow)]
pub struct JournalRow {
    pub payload: String,
    pub sha256: String,
    pub updated_at: NaiveDateTime,
}

pub fn rfc3339(value: NaiveDateTime) -> String {
    let utc: DateTime<Utc> = value.and_utc();
    utc.to_rfc3339_opts(SecondsFormat::Millis, true)
}

pub fn now_millis() -> NaiveDateTime {
    let now = Utc::now();
    let millis = now.timestamp_subsec_millis();
    let naive = now.naive_utc();
    naive.with_nanosecond(millis * 1_000_000).unwrap_or(naive)
}
