//! Pure validation and scheduling rules for OTA. No networking or flash access.

use serde_json::Value;
use sha2::{Digest, Sha256};
use std::io::Read;
use std::time::Duration;

const MAX_METADATA_BYTES: usize = 16 * 1024;

#[derive(Debug, PartialEq, Eq)]
pub struct Artifact {
    pub action_id: String,
    pub version: String,
    pub url: String,
    pub size: u64,
    pub sha256: String,
}

/// Parse the DDI polling response without following links outside the Home
/// origin. Cancellation takes precedence over installation.
pub fn parse_poll(json: &[u8]) -> Result<Option<String>, &'static str> {
    if json.len() > MAX_METADATA_BYTES {
        return Err("poll metadata is too large");
    }
    let root: Value = serde_json::from_slice(json).map_err(|_| "invalid poll JSON")?;
    let links = root.get("_links").ok_or("missing poll links")?;
    if links.get("cancelAction").is_some() {
        return Err("deployment cancellation requires handling");
    }
    let Some(deployment) = links.get("deploymentBase") else {
        return Ok(None);
    };
    let url = deployment["href"]
        .as_str()
        .ok_or("missing deployment URL")?;
    if deployment_id(url).is_none() {
        return Err("untrusted deployment URL");
    }
    Ok(Some(url.to_owned()))
}

pub fn deployment_id(url: &str) -> Option<&str> {
    if !trusted_url(url) {
        return None;
    }
    let path = url
        .strip_prefix("https://ota.calcifer.tech/")
        .or_else(|| url.strip_prefix("https://ota.calcifer.tech:443/"))?;
    let action = path
        .split('?')
        .next()?
        .strip_prefix("DEFAULT/controller/v1/therment-01/deploymentBase/")?;
    if action.is_empty() || action.len() > 20 || !action.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    Some(action)
}

/// Parse one complete hawkBit deployment. Multiple artifacts are ambiguous and
/// must be rejected rather than silently installing an arbitrary component.
pub fn parse_deployment(json: &[u8], slot_size: u64) -> Result<Artifact, &'static str> {
    if json.len() > MAX_METADATA_BYTES {
        return Err("deployment metadata is too large");
    }
    let root: Value = serde_json::from_slice(json).map_err(|_| "invalid deployment JSON")?;
    let id = root["id"].as_str().ok_or("missing action id")?;
    if id.is_empty() || id.len() > 20 || !id.bytes().all(|b| b.is_ascii_digit()) {
        return Err("invalid action id");
    }
    let deployment = root.get("deployment").ok_or("missing deployment")?;
    if !matches!(deployment["update"].as_str(), Some("forced" | "attempt"))
        || !matches!(deployment["download"].as_str(), Some("forced" | "attempt"))
    {
        return Err("deployment does not request installation");
    }
    let chunks = deployment["chunks"].as_array().ok_or("missing chunks")?;
    if chunks.len() != 1 || chunks[0]["part"].as_str() != Some("os") {
        return Err("expected one OS module");
    }
    let chunk = &chunks[0];
    let version = chunk["version"]
        .as_str()
        .filter(|v| {
            !v.is_empty()
                && v.len() <= 64
                && v.bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b"._-".contains(&b))
        })
        .ok_or("invalid version")?;
    let artifacts = chunk["artifacts"].as_array().ok_or("missing artifacts")?;
    if artifacts.len() != 1 {
        return Err("expected one artifact");
    }
    let artifact = &artifacts[0];
    let size = artifact["size"].as_u64().ok_or("invalid size")?;
    let sha256 = artifact["hashes"]["sha256"]
        .as_str()
        .ok_or("missing SHA-256")?;
    let url = artifact["_links"]["download-http"]["href"]
        .as_str()
        .ok_or("missing download URL")?;
    if !valid_artifact(size, slot_size, sha256) || !trusted_url(url) {
        return Err("invalid artifact metadata");
    }
    Ok(Artifact {
        action_id: id.to_owned(),
        version: version.to_owned(),
        url: url.to_owned(),
        size,
        sha256: sha256.to_ascii_lowercase(),
    })
}

/// Only the Home server is allowed to receive a target token or supply images.
pub fn trusted_url(url: &str) -> bool {
    let path = url
        .strip_prefix("https://ota.calcifer.tech/")
        .or_else(|| url.strip_prefix("https://ota.calcifer.tech:443/"));
    path.is_some_and(|path| {
        !path.is_empty()
            && !path
                .bytes()
                .any(|c| c.is_ascii_control() || c == b'\\' || c == b'#')
    })
}

/// Reject invalid release metadata before allocating or erasing a flash slot.
pub fn valid_artifact(size: u64, slot_size: u64, sha256: &str) -> bool {
    size > 0
        && size <= slot_size
        && sha256.len() == 64
        && sha256.bytes().all(|byte| byte.is_ascii_hexdigit())
}

/// Stream into an inactive-slot writer, but never select it for boot. The
/// caller must abort the OTA handle on any error and validate the ESP image
/// before it can request activation.
pub fn write_verified<R: Read>(
    mut source: R,
    expected_size: u64,
    expected_sha256: &str,
    mut write: impl FnMut(&[u8]) -> Result<(), ()>,
) -> Result<(), &'static str> {
    if !valid_artifact(expected_size, u64::MAX, expected_sha256) {
        return Err("invalid expected artifact metadata");
    }
    let mut hash = Sha256::new();
    let mut total = 0_u64;
    let mut buffer = [0_u8; 4096];
    loop {
        let n = source
            .read(&mut buffer)
            .map_err(|_| "download interrupted")?;
        if n == 0 {
            break;
        }
        total = total.checked_add(n as u64).ok_or("artifact too large")?;
        if total > expected_size {
            return Err("artifact exceeds declared size");
        }
        hash.update(&buffer[..n]);
        write(&buffer[..n]).map_err(|_| "slot write failed")?;
    }
    if total != expected_size {
        return Err("artifact length mismatch");
    }
    let actual: String = hash
        .finalize()
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect();
    if !actual.eq_ignore_ascii_case(expected_sha256) {
        return Err("artifact checksum mismatch");
    }
    Ok(())
}

/// Bound retries even if a service supplies a very large delay.
pub fn retry_delay(attempt: u32) -> Duration {
    Duration::from_secs(5_u64.saturating_mul(1_u64 << attempt.min(7)).min(300))
}

/// Firmware can only select the prepared slot while both relays are off.
pub fn may_activate(heating: bool, cooling: bool, image_valid: bool) -> bool {
    !heating && !cooling && image_valid
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_exact_https_origin_is_trusted() {
        assert!(trusted_url(
            "https://ota.calcifer.tech/DEFAULT/controller/v1/device"
        ));
        assert!(trusted_url("https://ota.calcifer.tech:443/path?x=1"));
        for url in [
            "http://ota.calcifer.tech/path",
            "https://ota.calcifer.tech:8443/path",
            "https://ota.calcifer.tech.evil.invalid/path",
            "https://ota.calcifer.tech@evil.invalid/path",
            "https://evil.invalid/ota.calcifer.tech/path",
            "https://ota.calcifer.tech/\\evil.invalid/path",
            "https://ota.calcifer.tech/path#fragment",
            "https://ota.calcifer.tech/\r\nHeader: value",
            "https://ota.calcifer.tech/",
        ] {
            assert!(!trusted_url(url), "accepted {url:?}");
        }
    }

    #[test]
    fn artifact_requires_size_within_slot_and_a_sha256() {
        let hash = "a".repeat(64);
        assert!(valid_artifact(42, 42, &hash));
        assert!(!valid_artifact(0, 42, &hash));
        assert!(!valid_artifact(43, 42, &hash));
        assert!(!valid_artifact(42, 42, &hash[..63]));
        assert!(!valid_artifact(42, 42, &"g".repeat(64)));
    }

    #[test]
    fn retry_is_bounded_and_activation_is_fail_safe() {
        assert_eq!(retry_delay(0), Duration::from_secs(5));
        assert_eq!(retry_delay(u32::MAX), Duration::from_secs(300));
        assert!(may_activate(false, false, true));
        assert!(!may_activate(true, false, true));
        assert!(!may_activate(false, true, true));
        assert!(!may_activate(false, false, false));
    }

    #[test]
    fn deployment_requires_one_trusted_artifact_and_an_install_request() {
        let body = serde_json::json!({
            "id": "123",
            "deployment": {"update": "forced", "download": "attempt", "chunks": [{
                "part": "os", "version": "1.2.3", "artifacts": [{
                    "size": 100, "hashes": {"sha256": "a".repeat(64)},
                    "_links": {"download-http": {"href": "https://ota.calcifer.tech:443/image"}}
                }]
            }]}
        });
        let encode = |v: &Value| serde_json::to_vec(v).unwrap();
        let artifact = parse_deployment(&encode(&body), 200).unwrap();
        assert_eq!(artifact.action_id, "123");
        assert_eq!(artifact.version, "1.2.3");
        assert_eq!(artifact.size, 100);
        assert!(parse_deployment(&encode(&body), 99).is_err());
        let mut invalid = body.clone();
        invalid["deployment"]["update"] = "skip".into();
        assert!(parse_deployment(&encode(&invalid), 200).is_err());
        invalid = body.clone();
        invalid["deployment"]["chunks"][0]["artifacts"][0]["_links"]["download-http"]["href"] =
            "https://evil.invalid/image".into();
        assert!(parse_deployment(&encode(&invalid), 200).is_err());
        invalid = body.clone();
        invalid["deployment"]["chunks"][0]["artifacts"]
            .as_array_mut()
            .unwrap()
            .push(Value::Null);
        assert!(parse_deployment(&encode(&invalid), 200).is_err());
        assert!(parse_deployment(&vec![b' '; MAX_METADATA_BYTES + 1], 200).is_err());
    }

    #[test]
    fn poll_rejects_unknown_origin_and_does_not_ignore_cancellation() {
        assert_eq!(parse_poll(br#"{"_links":{}}"#), Ok(None));
        let valid = br#"{"_links":{"deploymentBase":{"href":"https://ota.calcifer.tech/DEFAULT/controller/v1/therment-01/deploymentBase/1"}}}"#;
        assert!(parse_poll(valid).unwrap().is_some());
        let invalid = br#"{"_links":{"deploymentBase":{"href":"https://evil.invalid/path"}}}"#;
        assert!(parse_poll(invalid).is_err());
        let cancelled = br#"{"_links":{"cancelAction":{},"deploymentBase":{"href":"https://ota.calcifer.tech/path"}}}"#;
        assert!(parse_poll(cancelled).is_err());
        assert_eq!(
            deployment_id(
                "https://ota.calcifer.tech/DEFAULT/controller/v1/therment-01/deploymentBase/42?c=1"
            ),
            Some("42")
        );
        assert!(
            deployment_id(
                "https://ota.calcifer.tech/DEFAULT/controller/v1/other/deploymentBase/42"
            )
            .is_none()
        );
        assert!(deployment_id("https://ota.calcifer.tech/DEFAULT/controller/v1/therment-01/deploymentBase/42/extra").is_none());
    }

    #[test]
    fn stream_is_verified_before_the_caller_can_activate() {
        let input = b"test firmware";
        let expected: String = Sha256::digest(input)
            .iter()
            .map(|byte| format!("{byte:02x}"))
            .collect();
        let mut written = Vec::new();
        assert!(
            write_verified(&input[..], input.len() as u64, &expected, |bytes| {
                written.extend_from_slice(bytes);
                Ok(())
            })
            .is_ok()
        );
        assert_eq!(written, input);
        assert_eq!(
            write_verified(&input[..], 1, &expected, |_| Ok(())),
            Err("artifact exceeds declared size")
        );
        assert_eq!(
            write_verified(&input[..], 100, &expected, |_| Ok(())),
            Err("artifact length mismatch")
        );
        assert_eq!(
            write_verified(&input[..], input.len() as u64, &"0".repeat(64), |_| Ok(())),
            Err("artifact checksum mismatch")
        );
        assert_eq!(
            write_verified(&input[..], input.len() as u64, &expected, |_| Err(())),
            Err("slot write failed")
        );
        struct Broken;
        impl Read for Broken {
            fn read(&mut self, _: &mut [u8]) -> std::io::Result<usize> {
                Err(std::io::ErrorKind::ConnectionReset.into())
            }
        }
        assert_eq!(
            write_verified(Broken, 1, &expected, |_| Ok(())),
            Err("download interrupted")
        );
    }
}
