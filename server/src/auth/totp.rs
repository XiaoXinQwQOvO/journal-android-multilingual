use data_encoding::BASE32_NOPAD;
use hmac::{Hmac, Mac};
use rand::RngCore;
use sha1::Sha1;
use subtle::ConstantTimeEq;
use url::form_urlencoded::byte_serialize;

const STEP_SECONDS: u64 = 30;
const DIGITS_MODULO: u32 = 1_000_000;

pub fn generate_secret() -> Vec<u8> {
    let mut secret = [0u8; 20];
    rand::thread_rng().fill_bytes(&mut secret);
    secret.to_vec()
}

pub fn secret_base32(secret: &[u8]) -> String {
    BASE32_NOPAD.encode(secret)
}

pub fn otpauth_url(secret: &[u8], issuer: &str, account: &str) -> String {
    let label = format!("{issuer}:{account}");
    let encoded_label: String = byte_serialize(label.as_bytes()).collect();
    let encoded_issuer: String = byte_serialize(issuer.as_bytes()).collect();
    format!(
        "otpauth://totp/{encoded_label}?secret={}&issuer={encoded_issuer}&digits=6&period={STEP_SECONDS}",
        secret_base32(secret)
    )
}

pub fn verify_code(secret: &[u8], code: u32, unix_time: u64) -> bool {
    let step = unix_time / STEP_SECONDS;
    let mut matched = 0u8;
    for skew in [-1i64, 0, 1] {
        let counter = (step as i64 + skew) as u64;
        let expected = hotp(secret, counter);
        matched |= expected.ct_eq(&code).unwrap_u8();
    }
    matched == 1
}

fn hotp(secret: &[u8], counter: u64) -> u32 {
    let mut mac = Hmac::<Sha1>::new_from_slice(secret).expect("HMAC accepts keys of any length");
    mac.update(&counter.to_be_bytes());
    let digest = mac.finalize().into_bytes();
    let offset = (digest[19] & 0x0f) as usize;
    let binary = ((digest[offset] & 0x7f) as u32) << 24
        | (digest[offset + 1] as u32) << 16
        | (digest[offset + 2] as u32) << 8
        | (digest[offset + 3] as u32);
    binary % DIGITS_MODULO
}
