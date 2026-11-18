SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE users (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    username VARCHAR(32) NOT NULL,
    username_norm VARCHAR(32) NOT NULL,
    password_hash VARCHAR(255) NULL,
    avatar MEDIUMBLOB NULL,
    avatar_mime VARCHAR(64) NULL,
    avatar_sha256 CHAR(64) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_users_username_norm (username_norm)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE passkey_credentials (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    credential_id VARBINARY(255) NOT NULL,
    passkey_json JSON NOT NULL,
    sign_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    nickname VARCHAR(64) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    last_used_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_passkey_credentials_credential_id (credential_id),
    KEY idx_passkey_credentials_user_id (user_id),
    CONSTRAINT fk_passkey_credentials_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE webauthn_states (
    challenge_id CHAR(36) NOT NULL,
    user_id BIGINT UNSIGNED NULL,
    kind ENUM ('register', 'login') NOT NULL,
    state_json JSON NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    PRIMARY KEY (challenge_id),
    KEY idx_webauthn_states_expires_at (expires_at),
    CONSTRAINT fk_webauthn_states_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE journal_snapshots (
    user_id BIGINT UNSIGNED NOT NULL,
    payload LONGTEXT NOT NULL,
    payload_bytes INT UNSIGNED NOT NULL,
    sha256 CHAR(64) NOT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id),
    CONSTRAINT fk_journal_snapshots_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE extension_packs (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    register_name VARCHAR(64) NOT NULL,
    title VARCHAR(200) NOT NULL,
    description TEXT NOT NULL,
    official_link VARCHAR(512) NULL,
    version_name VARCHAR(64) NOT NULL,
    version_code INT UNSIGNED NOT NULL,
    sha256 CHAR(64) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    size_bytes BIGINT UNSIGNED NOT NULL,
    icon_file_name VARCHAR(255) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_extension_packs_register_name (register_name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;