# On-Device Architecture (v3.0 Roadmap): Event-Driven Android TV App

> **Status:** Architecture & Parallel Validation Blueprint  
> **Goal:** Run all TV maintenance, AOT speed-compilation (`dex2oat`), cache trimming, and Home Assistant telemetry **100% natively on the Android TV itself**, triggered by OS lifecycle events (`ACTION_SCREEN_OFF`, `ACTION_PACKAGE_REPLACED`, `ACTION_BOOT_COMPLETED`) with **zero open network ADB ports** in standalone production.

---

## 1. Why Event-Driven On-Device Beats 24/7 Network Polling

1. **Zero Open Network Ports (CleanYourTV Showcase Compliance):**
   - An external server (NAS / Raspberry Pi) requires leaving the TV's network ADB (`port 5555`) open 24/7.
   - By running natively on the TV and holding the one-time `android.permission.WRITE_SECURE_SETTINGS` grant (which persists across reboots), the app keeps `Settings.Global.ADB_ENABLED = 0` **99.9% of the time**, opening loopback ADB (`127.0.0.1:5555`) for only **5–10 seconds** inside a `try / finally` guard when maintenance is needed.
2. **Why `ACTION_SCREEN_OFF` Beats 04:30 AM Cron Jobs:**
   - When an Android TV enters Deep Sleep (Suspend-to-RAM), the SoC CPU is frozen. Waking the TV internally at 04:30 AM via `AlarmManager` RTC wakeups risks turning on the physical display panel on many OEM firmwares.
   - More importantly, **a sleeping TV generates zero new cache bloat, zero background processes, and zero Google Play updates**. All maintenance debt is accumulated *while watching TV*.
   - By triggering maintenance on `Intent.ACTION_SCREEN_OFF` (holding a `PowerManager.PARTIAL_WAKE_LOCK` for 5–15 seconds right after the user turns the TV off with the remote, while the physical panel and Ambilight are already 100% black), the TV cleans RAM, trims caches, compiles any updated apps, reports to Home Assistant, and then drops cleanly into Deep Sleep.

---

## 2. Event-Driven Execution Model

| Android OS Broadcast / Trigger | When It Fires | On-Device Action |
| :--- | :--- | :--- |
| **`Intent.ACTION_SCREEN_ON`** | User presses Power/Home on the remote to wake the TV. | 1. Cancels any pending `SCREEN_OFF` job immediately.<br>2. Records session start timestamp.<br>3. Pushes `state = "ON"` + `MemAvailable` to Home Assistant REST API. |
| **`Intent.ACTION_SCREEN_OFF`** | User turns off the TV via remote (panel & Ambilight turn off immediately). | 1. Acquires `PARTIAL_WAKE_LOCK` (max 3 min timeout, keeps CPU awake while screen stays 100% black) + `WifiLock`.<br>2. **15-second debounce** (aborts immediately if `SCREEN_ON` fires within 15s).<br>3. Opens `AdbSessionGuard` (`127.0.0.1:5555`), force-stops lingering background streaming apps, runs `pm trim-caches 4096M` & `sm fstrim`, compiles any dirty packages (`cmd package compile -m speed`), and closes ADB.<br>4. Pushes `state = "OFF"` + cleaned RAM metrics to Home Assistant.<br>5. Releases `WakeLock` -> TV enters Deep Sleep. |
| **`Intent.ACTION_PACKAGE_REPLACED`** | Google Play auto-updates an app in the background. | If the updated package is in `core_apps`, marks it in the `DirtyPackageQueue` so `dex2oat` (`compile -m speed`) runs only when actually needed (on the next `SCREEN_OFF` or after 5 min of idle). |
| **`Intent.ACTION_BOOT_COMPLETED`** | Cold boot (e.g., after a power outage). | Starts `WatcherService`, verifies system clock/NTP health, enforces `stay_on_while_plugged_in = 0` and `0.5x` animation scales, and sends boot telemetry to Home Assistant. |

---

## 3. Modular Software Architecture (Single Responsibility)

To keep the codebase clean, testable, and easy to maintain, `android-app/` is divided into **6 decoupled modules** under `io.github.tekno74.tvmaintenance`:

```text
android-app/
└── app/src/main/java/io/github/tekno74/tvmaintenance/
    ├── config/
    │   └── DaemonConfig.java          # Reads/validates config (mode: "parallel" | "standalone", core_apps, HA webhook, cooldowns)
    ├── adb/
    │   ├── AdbSessionGuard.java       # AutoCloseable try-with-resources guard for WRITE_SECURE_SETTINGS (adb_enabled toggle)
    │   └── LocalAdbClient.java        # Executes shell commands strictly against 127.0.0.1:5555 with timeouts
    ├── events/
    │   ├── SystemEventReceiver.java   # BroadcastReceiver for BOOT_COMPLETED and MY_PACKAGE_REPLACED
    │   ├── WatcherService.java        # Lightweight persistent service listening to SCREEN_ON, SCREEN_OFF, PACKAGE_REPLACED
    │   └── DirtyPackageQueue.java     # Persistent SharedPreferences set of updated core_apps awaiting dex2oat compilation
    ├── engine/
    │   ├── MaintenanceEngine.java     # Orchestrates standby cleanup, cache trimming, AOT compilation, and package guards
    │   └── SafetyInvariants.java      # Hardcoded runtime assertions protecting launcher accessibility & dark-sleep flags
    ├── telemetry/
    │   ├── MetricsCollector.java      # Reads /proc/meminfo (MemAvailable) and StatFs(/data) directly via Java/Linux APIs (0 ADB!)
    │   └── HomeAssistantClient.java   # Pushes JSON payloads via HTTP POST to Home Assistant Webhook / REST API
    └── ui/
        ├── MainActivity.java          # Minimalist Leanback D-pad status screen (mode toggle, last run, manual trigger)
        └── ModuleCardProvider.java    # Optional CleanYourTV ContentProvider card showing live free RAM & AOT status
```

### Key Design Pattern: `AdbSessionGuard` (`parallel` vs `standalone` mode)

```java
public final class AdbSessionGuard implements AutoCloseable {
    private final ContentResolver resolver;
    private final boolean standaloneMode;

    public AdbSessionGuard(ContentResolver resolver, boolean standaloneMode) {
        this.resolver = resolver;
        this.standaloneMode = standaloneMode;
        // Ensure ADB is enabled before connecting to 127.0.0.1:5555
        Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 1);
    }

    @Override
    public void close() {
        if (standaloneMode) {
            // Standalone production mode: immediately close port 5555 after maintenance!
            Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 0);
        }
        // In "parallel" validation mode, leave ADB_ENABLED = 1 so the external NAS daemon stays connected.
    }
}
```

---

## 4. Three-Phase Parallel Validation Plan & User-Cases

To ensure zero regressions, the On-Device app is rolled out in three phases alongside the existing NAS daemon:

### Phase 1: Parallel Run (`"mode": "parallel"`)
- Both the Synology NAS daemon (`core/daemon.py` + 04:30 cron) and the On-Device Android TV app run concurrently.
- `AdbSessionGuard` runs with `standaloneMode = false` (leaving `adb_enabled = 1` so the NAS never loses connection).
- The On-Device app reports to separate shadow entities in Home Assistant (`sensor.philips_tv_ondevice_status`) so both telemetry streams can be compared side-by-side.

### Phase 2: Standalone Cutover (`"mode": "standalone"`)
- Pause the NAS daemon and cron job (`--pause` / comment crontab).
- Switch the On-Device app to `"mode": "standalone"`.
- Verify that `adb_enabled` remains `0` (`nmap -p 5555 <TV_IP>` shows `closed`) 99.9% of the day, opening only for 5–10 seconds on `SCREEN_OFF`.

### Phase 3: NAS Retirement & CleanYourTV Showcase Submission
- Decommission the NAS TV scripts once Phase 2 has run flawlessly for 1–2 weeks.
- Submit a new PR to `Gathin23/cleanyourtv` (`SHOWCASE.md`) highlighting the 100% On-Device architecture with zero open network ADB ports.

### Verification Matrix (6 User-Cases)

| ID | User-Case | Pass Criteria |
| :--- | :--- | :--- |
| **UC1** | **Turn off TV via Remote (`SCREEN_OFF`)** | After 15s debounce, CPU stays awake via `PARTIAL_WAKE_LOCK` (screen/Ambilight 100% dark), caches are trimmed, background apps stopped, HA updated with `state=OFF`, and TV enters Deep Sleep. |
| **UC2** | **Quick Regret / Re-Wake (`SCREEN_OFF` -> `SCREEN_ON` < 15s)** | If the user turns the TV back on within 15 seconds, the pending `SCREEN_OFF` job is immediately aborted with zero UI stutter. |
| **UC3** | **Google Play Auto-Update (`PACKAGE_REPLACED`)** | Updating a core streaming app adds it to `DirtyPackageQueue` and runs `cmd package compile -m speed` at the next `SCREEN_OFF`. |
| **UC4** | **Cold Boot After Power Outage (`BOOT_COMPLETED`)** | `WatcherService` auto-starts on boot, verifies NTP clock sync, enforces `stay_on_while_plugged_in=0` & `0.5x` animations, and preserves Projectivy Launcher as Home. |
| **UC5** | **Home Assistant Telemetry** | `MemAvailable` (MB), free storage (GB), power state, and last maintenance timestamp are delivered to Home Assistant without NAS involvement. |
| **UC6** | **Remote Responsiveness & 5 Invariants** | Home/Back keys respond immediately; `enabled_accessibility_services` for the active launcher and `org.droidtv.playtv` disabled state remain untouched. |
