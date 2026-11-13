# TV Maintenance Daemon 📺⚡

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Python: 3.9+](https://img.shields.io/badge/python-3.9+-blue.svg)](https://www.python.org/)
[![Docker](https://img.shields.io/badge/docker-ready-green.svg)](Dockerfile)
[![Platform](https://img.shields.io/badge/platform-Android%20TV%20%7C%20Google%20TV-brightgreen.svg)]()
[![AI Pair-Programmed](https://img.shields.io/badge/Built%20with-AI%20Pair--Programming-blueviolet.svg)]()

> Autonomous 24/7 background maintenance daemon, persistent AOT speed-compilation, and live-trimming companion for Android TV and Google TV devices.

---

## 🌟 Why TV Maintenance Daemon?

Most smart TVs (especially those powered by budget MediaTek or Realtek SoCs with 1.5–2 GB RAM) suffer from progressive performance degradation over time:
* Memory leaks and bloated app caches cause playback buffering and sluggish navigation.
* Standard Android JIT (Just-In-Time) compilation forces weak quad-core TV CPUs to compile code while apps are loading, leading to 10–25 second cold-start delays.
* **The Google Play Update Trap:** Even if you manually compile apps via ADB, Google Play resets them back to unoptimized bytecode whenever an app auto-updates.
* Manufacturer telemetries, content recommendation rows, and retail demo loops hog background CPU.
* **The Night Maintenance Dilemma:** Traditional automated ADB scripts or Wake-on-LAN packets often wake the physical TV screen in the middle of the night, lighting up dark bedrooms and living rooms.

**TV Maintenance Daemon** solves all of this autonomously from a local home server, NAS (Synology, TrueNAS, Unraid), or Raspberry Pi.

---

## 🚀 Key Features

### 1. 🌙 100% Dark Headless Night Maintenance
Wakes the TV's Linux kernel and Wi-Fi interface at night (e.g., 04:30 AM) via Wake-on-LAN into **Headless Standby** (`mWakefulness=Asleep`, `mState=OFF`). 
* By strictly maintaining `Settings.Global.STAY_ON_WHILE_PLUGGED_IN = 0` (preventing AOSP `PowerManagerService` from triggering `wakeUpNoUpdateLocked`), intensive maintenance and AOT compiling run completely in the dark without the panel or backlights ever turning on.

### 2. ⚡ Autonomous AOT Speed-Compilation (`compile -m speed`)
Checks configured streaming apps (Netflix, YouTube, Prime Video, Disney+, Apple TV, Max, Plex, etc.) daily. If an app was updated by Google Play, the engine automatically compiles its DEX bytecode to native machine code using Android's Ahead-of-Time compiler:
```bash
cmd package compile -m speed -f <package>
```
Apps launch instantly from cold start with zero JIT lag.

### 3. 🎬 Non-Intrusive Live-Trimming
The 24/7 daemon detects when someone is actively watching TV. After 5 minutes of steady streaming playback in a supported app, it quietly executes:
```bash
pm trim-caches 4096M && sm fstrim
```
Memory is reclaimed and flash storage blocks are trimmed **in the background without interrupting the video stream**.

### 4. 🛡️ Configurable Package Policy & Remote Lag Shield
Safely keeps bloatware, aggressive OEM recommendations, and background telemetry disabled at the OS level (`pm disable-user --user 0`) based on your custom `disabled_packages` configuration, without touching system partitions.
* **Tip for streaming-only sets:** On TVs where boot routines or accidental remote presses trigger a modal broadcast tuner screen (e.g. `org.droidtv.playtv`) that traps the Home or Back button, adding the tuner package to `disabled_packages` safely prevents the dialog. *(Users who watch live broadcast TV via an aerial antenna should leave tuner packages enabled).*
* **Opt-In Remote Lag Shield:** Optional guard against accessibility-service key filtering lag (`disable_accessibility_services: false` by default). When opted in, it preserves the launcher's own accessibility helpers while clearing non-essential background key filters that introduce 100–300 ms remote D-pad delay or `KEYCODE_BACK` drops.

### 5. ⏰ Cold-Boot RTC & Private DNS Healer
Many smart TVs lack a hardware battery backup for their Real-Time Clock (RTC). Following a power outage, the clock resets to the firmware build date. If Private DNS (DoT via TLS) is enabled, TLS certificate validation fails and network access deadlocks before NTP can sync.
* The optimizer detects clock desynchronization (`year < current`), synchronizes system time immediately, locks NTP to `time.google.com`, and enforces router-level DNS safety.

### 6. 🏎️ 0.5x UI Animation Scale Injector
Locks Android's global window, transition, and animator duration scales to `0.5x` (or custom), eliminating UI latency and giving launcher navigation an immediate, Apple TV-like responsiveness.

### 7. 📊 Home Automation & Telemetry Integration
Publishes atomic, clean JSON status metrics including **True Available Memory** (`MemAvailable` parsed from `/proc/meminfo`, rather than misleading `MemFree`), storage metrics, ANRs, and standby duration metrics directly to a customizable output path (e.g. for Home Assistant file/command sensors, Prometheus, or Grafana).

---

## 📋 Architecture Overview

```
+--------------------------------------------------------+
|       Home Server / NAS / Raspberry Pi / Docker        |
|                                                        |
|  - 24/7 Monitor Daemon:   core/daemon.py               |
|  - Nightly Headless Cron: core/optimizer.py --wake     |
|  - Cold-Boot Healer:      core/power_recovery.py       |
|  - Metrics / Telemetry:   system_jobs_status.json      |
+---------------------------+----------------------------+
                            |
                 ADB over TCP (Port 5555)
                 Wake-on-LAN (UDP 9 Broadcast)
                            |
+---------------------------v----------------------------+
|             Android TV / Google TV Device              |
|                                                        |
|  - Headless Kernel Standby:  mWakefulness=Asleep       |
|  - Physical Screen Status:   mState=OFF (Dark Panel)   |
|  - Core Streaming Apps:      AOT native compiled       |
|  - UI Animation Scales:      0.5x (Speed Boost)        |
+--------------------------------------------------------+
```

---

## 🛠️ Quickstart Guide

### Option A: Docker Compose (Recommended)

1. Clone the repository:
   ```bash
   git clone https://github.com/Tekno74-ai/tv-maintenance-daemon.git
   cd tv-maintenance-daemon
   ```
2. Copy and customize configuration:
   ```bash
   cp config.example.json config.json
   nano config.json
   ```
3. Start the container with Docker Compose:
   ```bash
   docker compose up -d
   ```

*(Note: `network_mode: host` is required for Wake-on-LAN UDP broadcast packets and low-latency ADB connection).*

---

### Option B: Native Linux / Synology NAS / Cron

1. Install Python 3 and Android Platform Tools (`adb`):
   ```bash
   # Debian / Ubuntu / Raspberry Pi OS
   sudo apt update && sudo apt install -y python3 adb
   ```
2. Enable Network Debugging on your TV (*Settings -> Device Preferences -> Developer Options -> USB/Network Debugging*).
3. Connect ADB once to authorize the host computer's RSA key:
   ```bash
   adb connect <TV_IP>:5555
   ```
   *(Accept the "Always allow from this computer" prompt on the TV screen).*
4. Set up crontab for headless nightly maintenance (e.g. at 04:30 AM):
   ```cron
   30 4 * * * /usr/bin/python3 /path/to/tv-maintenance-daemon/core/optimizer.py --wake >> /path/to/tv_cron.log 2>&1
   ```
5. Run the 24/7 playback and live-trimming daemon via systemd or supervisor:
   ```bash
   python3 core/daemon.py --config config.json
   ```

---

## ⚙️ Configuration Reference (`config.json`)

| Option | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `tv_ip` | string | `"192.168.1.50"` | Static IP address of your Android TV. |
| `tv_port` | integer | `5555` | ADB port (standard is 5555). |
| `tv_mac` | string | `""` | TV MAC address for Wake-on-LAN packets. |
| `wol_broadcast` | string | `"255.255.255.255"` | Subnet broadcast address for WoL packets. |
| `allow_wol_wake` | boolean | `true` | Send WoL packet before nightly maintenance. |
| `enforce_sleep_after_maintenance` | boolean | `true` | Extra safety check to guarantee screen remains asleep. |
| `disable_accessibility_services` | boolean | `false` | Opt-in guard: when `true`, clears non-essential key filters to prevent Back-button freezes and remote D-pad lag while preserving your launcher's Home-key and input helpers. |
| `force_stop_background_apps` | boolean | `true` | Selectively stop inactive streaming apps during headless night maintenance (never uses `am kill-all` or stops the active launcher). |
| `animation_scale` | float | `0.5` | Global Android animation scale multiplier. |
| `desired_private_dns` | string | `"off"` | Recommended `"off"` to prevent cold-boot TLS deadlocks. |
| `live_playback_wait_minutes`| integer | `5` | Minutes of continuous streaming before live-trimming. |
| `min_hours_between_maintenance` | integer | `18` | Minimum hours between live-trimming runs. |
| `default_launcher` | string | `""` | Activity of preferred custom launcher (e.g. Projectivy). |
| `core_apps` | array | `[...]` | List of packages to keep AOT speed-compiled. |
| `disabled_packages` | array | `[...]` | Packages to disable at OS level for user 0. |

---

## 🤝 Community & Upstream References

* **On-Device Android TV App Roadmap (v3.0):** [docs/ON_DEVICE_ARCHITECTURE.md](docs/ON_DEVICE_ARCHITECTURE.md) — Event-driven native Android TV companion (`ACTION_SCREEN_OFF` + `WRITE_SECURE_SETTINGS` ephemeral loopback ADB) requiring zero open network ports.
* Community debloat profiles and model knowledge: [CleanYourTV (Gathin23/cleanyourtv)](https://github.com/Gathin23/cleanyourtv)
* Recommended lightweight launcher: [Projectivy Launcher](https://github.com/spocky/projectivy-launcher)

---

## 🤖 Built with AI Pair-Programming

This project was architected and vibe-coded through continuous human-AI pair-programming (using Google DeepMind Antigravity) and empirically verified across real-world Android TV hardware and 24/7 server environments.

---

## 📄 License

This project is licensed under the [MIT License](LICENSE) - see the LICENSE file for details.
