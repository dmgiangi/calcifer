# Therment

Firmware ESP32 per una cella di fermentazione. La regolazione resta locale;
Wi-Fi e MQTT collegano il dispositivo a Home Assistant. Il modulo fa parte del
repository Calcifer e vive in `therment/`; eseguire i comandi di questa guida
da tale directory (`cd therment` dalla radice del repository). Il firmware
non viene distribuito da Flux.

## Prerequisiti di compilazione

Su Linux servono Rust e Cargo tramite `rustup`, gli strumenti di build C/C++,
Git, Python 3 con `venv` e `pip`, CMake, Ninja, Clang e le librerie di sviluppo
libudev. Per esempio, su Debian o Ubuntu:

```sh
sudo apt-get install build-essential git curl pkg-config libudev-dev clang libclang-dev python3 python3-pip python3-venv cmake ninja-build flex bison gperf libffi-dev libssl-dev
```

Installare il toolchain Espressif per ESP32, il linker `ldproxy` e `espflash`:

```sh
cargo install espup --locked
espup install --targets esp32
cargo install ldproxy --locked
cargo install espflash --locked
```

Caricare nella shell le variabili create da `espup` prima di compilare:

```sh
source "$HOME/export-esp.sh"
```

Se durante l'installazione è stato scelto un percorso diverso per il file di
export, usare quello. `rust-toolchain.toml` seleziona il toolchain `esp`;
`.cargo/config.toml` seleziona il target `xtensa-esp32-espidf` e ESP-IDF
v5.4.4. Al primo avvio della build `esp-idf-sys` scarica e prepara ESP-IDF e
gli strumenti richiesti: servono accesso alla rete e spazio su disco. La cache
locale `.embuild/` e gli output `target/` sono generati e ignorati da Git;
non vanno aggiunti al repository Calcifer. Una nuova macchina li ricreerà.
`Cargo.lock` mantiene le versioni delle dipendenze Rust.

## Configurazione e flash USB

Copiare
`.wifi-credentials.example.toml` in `.wifi-credentials.toml` e
`.mqtt-credentials.example.toml` in `.mqtt-credentials.toml`, quindi compilare
i valori. I file reali sono ignorati da Git e devono restare locali. Il broker
Home è `192.168.0.102:1883` e l’utente MQTT è `therment`; seguire la
[guida operativa MQTT](../docs/zigbee2mqtt-operations.md#therment-lan-mqtt)
per ottenere la password in sicurezza e consultare il contratto dei topic.
MQTT sulla LAN non usa TLS: credenziali e messaggi transitano in chiaro. Le credenziali sono incorporate
nel firmware: proteggere anche binari, artefatti e log di build.

```sh
cargo build --locked
espflash flash --port /dev/ttyUSB0 --chip esp32 target/xtensa-esp32-espidf/debug/therment
espflash monitor --port /dev/ttyUSB0 --chip esp32 -a hard-reset
```

Il GPIO13 è collegato alla sonda OneWire, GPIO17 al riscaldamento e GPIO5 al
raffreddamento. Il firmware assume relè attivi a livello basso.

## Migrazione e OTA

`partitions-ota.csv` è una proposta per due slot OTA: non è ancora configurata
nella build e non va flashata separatamente. La NVS è prevista allo stesso
offset della tabella ESP-IDF attuale; verificare la tabella effettiva del
dispositivo prima di migrare. Il firmware attuale non implementa OTA.

La direzione concordata è scaricare le immagini da un server HTTPS. Prima del
primo flash OTA servono integrazione del client, configurazione del bootloader
con rollback, verifiche di avvio, gestione dei relè e una procedura di
migrazione USB che preservi i dati. La distribuzione su Calcifer deve inoltre
gestire credenziali e artefatti firmware come segreti e fornire un certificato
HTTPS verificabile dall'ESP32.
