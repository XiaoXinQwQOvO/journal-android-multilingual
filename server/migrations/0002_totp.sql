ALTER TABLE users
    ADD COLUMN totp_secret VARBINARY(64) NULL AFTER password_hash,
    ADD COLUMN totp_enabled BOOLEAN NOT NULL DEFAULT FALSE AFTER totp_secret;