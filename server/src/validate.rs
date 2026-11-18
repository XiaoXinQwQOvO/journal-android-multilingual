pub const USERNAME_MIN_CHARS: usize = 3;
pub const USERNAME_MAX_CHARS: usize = 32;
pub const PASSWORD_MIN_CHARS: usize = 8;
pub const PASSWORD_MAX_CHARS: usize = 128;
pub const REGISTER_NAME_MAX_CHARS: usize = 64;

/// Usernames may contain Unicode letters and digits so the multilingual fork
/// accepts names such as 中文用户名. They are compared case-insensitively via
/// `username_norm`.
pub fn validate_username(username: &str) -> Result<(), &'static str> {
    let len = username.chars().count();
    if !(USERNAME_MIN_CHARS..=USERNAME_MAX_CHARS).contains(&len) {
        return Err("username must be 3-32 characters");
    }
    if !username
        .chars()
        .all(|c| c.is_alphanumeric() || matches!(c, '_' | '.' | '-'))
    {
        return Err("username may only contain letters, digits, '_', '.' and '-'");
    }
    Ok(())
}

pub fn username_norm(username: &str) -> String {
    username.to_lowercase()
}

pub fn validate_password(password: &str) -> Result<(), &'static str> {
    let len = password.chars().count();
    if !(PASSWORD_MIN_CHARS..=PASSWORD_MAX_CHARS).contains(&len) {
        return Err("password must be 8-128 characters");
    }
    Ok(())
}

/// Register names become directory names under DATA_DIR/packs, so they are
/// restricted to a path-safe character set.
pub fn validate_register_name(name: &str) -> Result<(), &'static str> {
    if name.is_empty() || name.len() > REGISTER_NAME_MAX_CHARS {
        return Err("registerName must be 1-64 characters");
    }
    if name == "." || name == ".." {
        return Err("invalid registerName");
    }
    let mut chars = name.chars();
    let first = chars.next().unwrap_or(' ');
    if !first.is_ascii_alphanumeric() {
        return Err("registerName must start with a letter or digit");
    }
    if !chars.all(|c| c.is_ascii_alphanumeric() || matches!(c, '_' | '.' | '-')) {
        return Err("registerName may only contain letters, digits, '_', '.' and '-'");
    }
    Ok(())
}

/// A relative path inside a zip archive. Used for the optional icon entry so
/// that a malicious manifest cannot address an unexpected archive member.
pub fn is_safe_zip_relative_path(path: &str) -> bool {
    if path.is_empty() || path.starts_with('/') || path.contains('\\') {
        return false;
    }
    path.split('/').all(|segment| {
        !segment.is_empty()
            && segment != "."
            && segment != ".."
            && segment
                .chars()
                .all(|c| c.is_ascii_alphanumeric() || matches!(c, '_' | '.' | '-' | ' '))
    })
}
