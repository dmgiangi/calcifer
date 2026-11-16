//! OTA worker. Temperature regulation remains on the main controller thread.

use crate::{Command, ota_credentials, ota_http, ota_policy};
use esp_idf_svc::nvs::{EspDefaultNvs, EspDefaultNvsPartition};
use esp_idf_svc::ota::EspOta;
use log::warn;
use std::sync::{Arc, Mutex, mpsc};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

#[derive(Clone, PartialEq)]
pub struct Status {
    pub installed: String,
    pub latest: String,
    pub phase: &'static str,
    pub outcome: &'static str,
    pub progress: Option<u8>,
    pub in_progress: bool,
}

impl Default for Status {
    fn default() -> Self {
        Self {
            installed: ota_credentials::RELEASE_VERSION.into(),
            latest: ota_credentials::RELEASE_VERSION.into(),
            phase: "idle",
            outcome: "unknown",
            progress: None,
            in_progress: false,
        }
    }
}

pub fn running_unverified() -> Result<bool, esp_idf_svc::sys::EspError> {
    use esp_idf_svc::sys::*;

    // EspOta::get_running_slot also decodes the build descriptor into a
    // 24-byte version buffer. ESP-IDF's generated project version may be
    // longer even though only the OTA state is needed here.
    let partition = unsafe { esp_ota_get_running_partition() };
    if partition.is_null() {
        return Err(EspError::from_infallible::<ESP_ERR_NOT_FOUND>());
    }
    let mut state: esp_ota_img_states_t = 0;
    let result = unsafe { esp_ota_get_state_partition(partition, &mut state) };
    if result == ESP_ERR_NOT_FOUND || result == ESP_ERR_NOT_SUPPORTED {
        return Ok(false);
    }
    EspError::convert(result)?;
    Ok(state == esp_ota_img_states_t_ESP_OTA_IMG_NEW
        || state == esp_ota_img_states_t_ESP_OTA_IMG_PENDING_VERIFY)
}

pub fn confirm_running_image(storage: &EspDefaultNvs) -> Result<(), esp_idf_svc::sys::EspError> {
    if running_unverified()? {
        let mut ota = EspOta::new()?;
        if stored(storage, "ota_action")?.is_none()
            || stored(storage, "ota_version")?.as_deref() != Some(ota_credentials::RELEASE_VERSION)
        {
            return Err(ota.mark_running_slot_invalid_and_reboot());
        }
        ota.mark_running_slot_valid()?;
    }
    Ok(())
}

fn set_status(
    status: &Arc<Mutex<Status>>,
    phase: &'static str,
    active: bool,
    progress: Option<u8>,
) {
    let mut state = status.lock().unwrap();
    state.phase = phase;
    state.in_progress = active;
    state.progress = progress;
}

fn stored(
    storage: &EspDefaultNvs,
    key: &str,
) -> Result<Option<String>, esp_idf_svc::sys::EspError> {
    let mut buf = [0_u8; 160];
    Ok(storage.get_str(key, &mut buf)?.map(str::to_owned))
}

fn report_previous(
    storage: &EspDefaultNvs,
    status: &Arc<Mutex<Status>>,
) -> Result<(), &'static str> {
    let Some(action) = stored(storage, "ota_action").map_err(|_| "could not read OTA action")?
    else {
        return Ok(());
    };
    let version = stored(storage, "ota_version")
        .map_err(|_| "could not read OTA version")?
        .ok_or("missing pending version")?;
    let success = version == ota_credentials::RELEASE_VERSION;
    set_status(
        status,
        if success { "success" } else { "rollback" },
        false,
        None,
    );
    status.lock().unwrap().outcome = if success { "success" } else { "rollback" };
    if !ota_http::feedback(ota_credentials::OTA_TOKEN, &action, success)? {
        // The action may already have been acknowledged, canceled or removed.
        // Clear the retry record without asserting what the server recorded.
        set_status(status, "feedback_closed", false, None);
        status.lock().unwrap().outcome = "unknown";
    }
    storage
        .remove("ota_action")
        .map_err(|_| "could not clear OTA action")?;
    storage
        .remove("ota_version")
        .map_err(|_| "could not clear OTA version")?;
    Ok(())
}

fn update(
    storage: &EspDefaultNvs,
    commands: &mpsc::Sender<Command>,
    status: &Arc<Mutex<Status>>,
) -> Result<(), &'static str> {
    set_status(status, "checking", true, None);
    let mut ota = EspOta::new().map_err(|_| "OTA unavailable")?;
    let next = unsafe { esp_idf_svc::sys::esp_ota_get_next_update_partition(std::ptr::null()) };
    if next.is_null() {
        return Err("inactive OTA slot missing");
    }
    let capacity = unsafe { (*next).size as u64 };
    let Some(artifact) = ota_http::check_deployment(ota_credentials::OTA_TOKEN, capacity)? else {
        let mut current = status.lock().unwrap();
        current.latest = current.installed.clone();
        drop(current);
        set_status(status, "idle", false, None);
        return Ok(());
    };
    if artifact.version == ota_credentials::RELEASE_VERSION {
        return Err("assigned release has the running version");
    }
    status.lock().unwrap().latest = artifact.version.clone();
    set_status(status, "downloading", true, Some(0));
    let mut image = ota
        .initiate_update_with_known_size(artifact.size as usize)
        .map_err(|_| "could not start OTA slot write")?;
    let download = ota_http::download(ota_credentials::OTA_TOKEN, &artifact, &mut image, |bytes| {
        let percent = ((bytes.saturating_mul(100) / artifact.size).min(100)) as u8;
        set_status(status, "downloading", true, Some(percent));
    });
    if let Err(reason) = download {
        if matches!(
            reason,
            "artifact exceeds declared size"
                | "artifact length mismatch"
                | "artifact checksum mismatch"
                | "slot write failed"
        ) {
            status.lock().unwrap().outcome = "error";
            let _ = ota_http::feedback(ota_credentials::OTA_TOKEN, &artifact.action_id, false);
        }
        return Err(reason);
    }
    set_status(status, "verifying", true, None);
    let finished = match image.finish() {
        Ok(finished) => finished,
        Err(_) => {
            status.lock().unwrap().outcome = "error";
            let _ = ota_http::feedback(ota_credentials::OTA_TOKEN, &artifact.action_id, false);
            return Err("ESP image validation failed");
        }
    };
    set_status(status, "waiting_for_relays", true, None);
    let (ready_tx, ready_rx) = mpsc::channel();
    commands
        .send(Command::OtaReady(ready_tx))
        .map_err(|_| "controller unavailable")?;
    if ready_rx.recv_timeout(Duration::from_secs(1800)).is_err() {
        status.lock().unwrap().outcome = "error";
        let _ = commands.send(Command::OtaResume);
        let _ = storage.remove("ota_action");
        let _ = ota_http::feedback(ota_credentials::OTA_TOKEN, &artifact.action_id, false);
        return Err("timed out waiting for relays");
    }
    let selected = ota_http::check_deployment(ota_credentials::OTA_TOKEN, capacity);
    if !matches!(selected, Ok(Some(ref current)) if current == &artifact) {
        let _ = commands.send(Command::OtaResume);
        return Err("deployment changed while waiting for relays");
    }
    if storage.set_str("ota_version", &artifact.version).is_err()
        || storage.set_str("ota_action", &artifact.action_id).is_err()
    {
        let _ = commands.send(Command::OtaResume);
        let _ = storage.remove("ota_action");
        return Err("could not persist pending OTA action");
    }
    if finished.activate().is_err() {
        let _ = commands.send(Command::OtaResume);
        let _ = storage.remove("ota_action");
        return Err("could not select OTA slot");
    }
    set_status(status, "rebooting", true, None);
    esp_idf_svc::hal::reset::restart();
}

pub fn run(
    trigger: mpsc::Receiver<()>,
    nvs: EspDefaultNvsPartition,
    commands: mpsc::Sender<Command>,
    status: Arc<Mutex<Status>>,
) {
    let storage = match EspDefaultNvs::new(nvs, "therment", true) {
        Ok(storage) => storage,
        Err(_) => {
            set_status(&status, "storage_error", false, None);
            return;
        }
    };
    let _sntp = match esp_idf_svc::sntp::EspSntp::new_default() {
        Ok(sntp) => sntp,
        Err(_) => {
            set_status(&status, "clock_error", false, None);
            return;
        }
    };
    let mut failures = 0_u32;
    loop {
        let clock_ready = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .is_ok_and(|d| d.as_secs() >= 1_700_000_000);
        let result = if !clock_ready {
            Err("clock not synchronized")
        } else {
            report_previous(&storage, &status).and_then(|()| update(&storage, &commands, &status))
        };
        let delay = match result {
            Ok(()) => {
                failures = 0;
                Duration::from_secs(300)
            }
            Err(reason) => {
                warn!("OTA check failed: {reason}");
                set_status(&status, "error", false, None);
                let delay = ota_policy::retry_delay(failures);
                failures = failures.saturating_add(1);
                delay
            }
        };
        let _ = trigger.recv_timeout(delay);
        // Coalesce repeated checks and impose a minimum interval.
        while trigger.try_recv().is_ok() {}
        std::thread::sleep(Duration::from_secs(5));
    }
}
