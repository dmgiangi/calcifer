use std::{env, fs, path::PathBuf};

fn main() {
    embuild::espidf::sysenv::output();

    let manifest_dir =
        env::var("CARGO_MANIFEST_DIR").expect("Cargo did not set CARGO_MANIFEST_DIR");
    let credentials_path = PathBuf::from(&manifest_dir).join(".wifi-credentials.toml");
    println!("cargo:rerun-if-changed={}", credentials_path.display());

    let contents = fs::read_to_string(&credentials_path).unwrap_or_else(|_| {
        panic!(
            "Wi-Fi credentials file is missing: {}. Copy .wifi-credentials.example.toml to .wifi-credentials.toml and fill it in.",
            credentials_path.display()
        )
    });
    let config: toml::Value = toml::from_str(&contents)
        .unwrap_or_else(|error| panic!("Could not parse .wifi-credentials.toml: {error}"));

    let ssid = required_string(&config, "ssid");
    let password = required_string(&config, "password");

    assert!(
        !ssid.is_empty() && ssid.len() <= 32,
        "Wi-Fi SSID must contain 1 to 32 UTF-8 bytes"
    );
    assert!(
        (8..=63).contains(&password.len())
            || (password.len() == 64 && password.bytes().all(|byte| byte.is_ascii_hexdigit())),
        "Wi-Fi password must be 8 to 63 bytes, or a 64-character hexadecimal PSK"
    );

    let out_dir = PathBuf::from(env::var("OUT_DIR").expect("Cargo did not set OUT_DIR"));
    let generated = format!(
        "pub const WIFI_SSID: &str = {ssid:?};\npub const WIFI_PASSWORD: &str = {password:?};\n"
    );
    fs::write(out_dir.join("wifi_credentials.rs"), generated)
        .expect("Could not write generated Wi-Fi credentials module");

    let mqtt_path = PathBuf::from(manifest_dir).join(".mqtt-credentials.toml");
    println!("cargo:rerun-if-changed={}", mqtt_path.display());
    let mqtt_contents = fs::read_to_string(&mqtt_path).unwrap_or_else(|_| {
        panic!("MQTT configuration missing: copy .mqtt-credentials.example.toml to .mqtt-credentials.toml and fill it in")
    });
    let mqtt: toml::Value = toml::from_str(&mqtt_contents)
        .unwrap_or_else(|error| panic!("Could not parse .mqtt-credentials.toml: {error}"));
    let mqtt_string = |key: &str| {
        mqtt.get(key)
            .and_then(toml::Value::as_str)
            .filter(|value| !value.trim().is_empty() && !value.contains('\0'))
            .unwrap_or_else(|| panic!(".mqtt-credentials.toml must contain a non-empty string `{key}` without NUL characters"))
    };
    let host = mqtt_string("host");
    let username = mqtt_string("username");
    let password = mqtt_string("password");
    let port = mqtt
        .get("port")
        .and_then(toml::Value::as_integer)
        .filter(|port| (1..=65535).contains(port))
        .expect(".mqtt-credentials.toml must contain a port between 1 and 65535");
    let generated = format!(
        "pub const MQTT_HOST: &str = {host:?};\npub const MQTT_PORT: u16 = {port};\npub const MQTT_USERNAME: &str = {username:?};\npub const MQTT_PASSWORD: &str = {password:?};\n"
    );
    fs::write(out_dir.join("mqtt_credentials.rs"), generated)
        .expect("Could not write generated MQTT credentials module");
}

fn required_string<'a>(config: &'a toml::Value, key: &str) -> &'a str {
    config
        .get(key)
        .and_then(toml::Value::as_str)
        .filter(|value| !value.trim().is_empty())
        .unwrap_or_else(|| panic!(".wifi-credentials.toml must contain a non-empty string `{key}`"))
}
