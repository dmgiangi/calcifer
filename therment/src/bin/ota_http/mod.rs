//! HTTPS metadata transport for the private hawkBit DDI endpoint.
//!
//! This module is not yet connected to the control loop. It deliberately
//! performs no flash operations and does not follow redirects.

use embedded_svc::http::Method;
use embedded_svc::http::client::Client;
use esp_idf_svc::http::client::{Configuration, EspHttpConnection, FollowRedirectsPolicy};
use std::time::{Duration, Instant};

const MAX_BODY: usize = 16 * 1024;
const POLL_URL: &str = "https://ota.calcifer.tech/DEFAULT/controller/v1/therment-01";

fn connection() -> Result<Client<EspHttpConnection>, &'static str> {
    let configuration = Configuration {
        timeout: Some(Duration::from_secs(15)),
        follow_redirects_policy: FollowRedirectsPolicy::FollowNone,
        crt_bundle_attach: Some(esp_idf_svc::sys::esp_crt_bundle_attach),
        ..Default::default()
    };
    Ok(Client::wrap(
        EspHttpConnection::new(&configuration).map_err(|_| "HTTPS setup failed")?,
    ))
}

fn valid_token(token: &str) -> bool {
    token.len() == 32 && token.bytes().all(|byte| byte.is_ascii_alphanumeric())
}

/// Check the assigned deployment without writing to flash. The caller must
/// arrange clock synchronization and a network connection first.
pub fn check_deployment(
    token: &str,
    slot_size: u64,
) -> Result<Option<crate::ota_policy::Artifact>, &'static str> {
    let poll = get_metadata(POLL_URL, token)?;
    let Some(url) = crate::ota_policy::parse_poll(&poll)? else {
        return Ok(None);
    };
    let action_id = crate::ota_policy::deployment_id(&url).ok_or("invalid deployment URL")?;
    let deployment = get_metadata(&url, token)?;
    let artifact = crate::ota_policy::parse_deployment(&deployment, slot_size)?;
    if artifact.action_id != action_id {
        return Err("deployment action does not match its URL");
    }
    Ok(Some(artifact))
}

pub fn get_metadata(url: &str, token: &str) -> Result<Vec<u8>, &'static str> {
    if !crate::ota_policy::trusted_url(url) {
        return Err("untrusted HTTPS origin");
    }
    if !valid_token(token) {
        return Err("invalid target credential");
    }
    let mut client = connection()?;
    let authorization = format!("TargetToken {token}");
    let headers = [
        ("Authorization", authorization.as_str()),
        ("Accept", "application/json"),
    ];
    let request = client
        .request(Method::Get, url, &headers)
        .map_err(|_| "HTTPS request failed")?;
    let mut response = request.submit().map_err(|_| "HTTPS request failed")?;
    if response.status() != 200 {
        return Err("unexpected HTTPS status");
    }
    let mut body = Vec::new();
    let mut buffer = [0_u8; 1024];
    let started = Instant::now();
    loop {
        if started.elapsed() > Duration::from_secs(60) {
            return Err("HTTPS response timed out");
        }
        let count = response
            .read(&mut buffer)
            .map_err(|_| "HTTPS response interrupted")?;
        if count == 0 {
            break;
        }
        if body.len() + count > MAX_BODY {
            return Err("HTTPS response too large");
        }
        body.extend_from_slice(&buffer[..count]);
    }
    Ok(body)
}

/// Stream an authenticated artifact directly to an inactive OTA handle.
/// The caller must finish ESP-IDF image validation before selecting it.
pub fn download(
    token: &str,
    artifact: &crate::ota_policy::Artifact,
    update: &mut esp_idf_svc::ota::EspOtaUpdate<'_>,
    mut progress: impl FnMut(u64),
) -> Result<(), &'static str> {
    if !valid_token(token) || !crate::ota_policy::trusted_url(&artifact.url) {
        return Err("invalid artifact origin or credential");
    }
    let mut client = connection()?;
    let authorization = format!("TargetToken {token}");
    let headers = [("Authorization", authorization.as_str())];
    let request = client
        .request(Method::Get, &artifact.url, &headers)
        .map_err(|_| "artifact request failed")?;
    let mut response = request.submit().map_err(|_| "artifact request failed")?;
    if response.status() != 200 {
        return Err("unexpected artifact status");
    }
    struct Reader<'a, R> {
        response: &'a mut R,
        started: Instant,
    }
    impl<R: embedded_svc::io::Read> std::io::Read for Reader<'_, R> {
        fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
            if self.started.elapsed() > Duration::from_secs(600) {
                return Err(std::io::ErrorKind::TimedOut.into());
            }
            self.response
                .read(buf)
                .map_err(|_| std::io::ErrorKind::ConnectionAborted.into())
        }
    }
    let mut written = 0_u64;
    crate::ota_policy::write_verified(
        Reader {
            response: &mut response,
            started: Instant::now(),
        },
        artifact.size,
        &artifact.sha256,
        |bytes| {
            update.write(bytes).map_err(|_| ())?;
            written += bytes.len() as u64;
            progress(written);
            Ok(())
        },
    )
}

/// `false` means the server no longer exposes the action (HTTP 410); the
/// caller must not claim the remote outcome in that ambiguous case.
pub fn feedback(token: &str, id: &str, success: bool) -> Result<bool, &'static str> {
    if !valid_token(token) || id.is_empty() || !id.bytes().all(|b| b.is_ascii_digit()) {
        return Err("invalid feedback identity");
    }
    let url = format!("{POLL_URL}/deploymentBase/{id}/feedback");
    let data = serde_json::json!({"id": id, "status": {"execution": "closed", "result": {"finished": if success {"success"} else {"failure"}}, "details": []}}).to_string();
    let authorization = format!("TargetToken {token}");
    let length = data.len().to_string();
    let headers = [
        ("Authorization", authorization.as_str()),
        ("Content-Type", "application/json"),
        ("Content-Length", length.as_str()),
    ];
    let mut client = connection()?;
    let mut request = client
        .post(&url, &headers)
        .map_err(|_| "feedback request failed")?;
    let mut remaining = data.as_bytes();
    while !remaining.is_empty() {
        let n = request
            .write(remaining)
            .map_err(|_| "feedback write failed")?;
        if n == 0 {
            return Err("feedback write failed");
        }
        remaining = &remaining[n..];
    }
    let response = request.submit().map_err(|_| "feedback request failed")?;
    if response.status() == 410 {
        return Ok(false);
    }
    if !(200..300).contains(&response.status()) {
        return Err("unexpected feedback status");
    }
    Ok(true)
}
