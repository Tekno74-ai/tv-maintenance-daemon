# AGENTS.md – Rules for AI Agents in TV Maintenance Daemon

> **READ THIS FIRST** before reading, creating, or editing code in this repository.  
> See [`README.md`](README.md) for full architectural documentation.

---

## 1. Critical Invariants & Safety Guardrails

1. **100% Dark Headless Maintenance:**
   - Nightly maintenance runs with the screen completely OFF (`mWakefulness=Asleep`, `mState=OFF`).
   - `Settings.Global.STAY_ON_WHILE_PLUGGED_IN` MUST always remain `0`.
   - NEVER execute `svc power stayon true` or any command that wakes the physical display backlights.
2. **Never Touch Accessibility Services:**
   - Custom launchers (e.g. Projectivy Launcher) rely on system input mapping. Altering `enabled_accessibility_services` breaks remote control Home/Back button capture.
3. **Never Disable Wi-Fi Interface:**
   - Disabling Wi-Fi via `svc wifi disable` completely cuts remote ADB connectivity and bricks Wake-on-LAN reception.
4. **Broadcast Tuner Trap Protection:**
   - Stock broadcast tuners (`org.droidtv.playtv`) must remain disabled (`pm disable-user --user 0`) to prevent modal "No Signal" dialog traps that swallow remote buttons.
5. **AOT Speed-Compilation Invariant:**
   - Ahead-of-Time compilation must use `cmd package compile -m speed -f <pkg>`.
   - Never force compilation during active user streaming sessions.

---

## 2. Deterministic Commands & Verification

Run from the project root (`c:\Users\marcu\antigravity\tv-maintenance-daemon`):

```powershell
# 1. Run Python syntax & import check on core modules:
python -m py_compile core/daemon.py core/optimizer.py core/power_recovery.py

# 2. Test Docker build:
docker build -t tv-maintenance-daemon:test .

# 3. Dry-run optimizer logic:
python -c "from core.optimizer import TVOptimizer; print('TVOptimizer loaded successfully')"
```

---

## 3. Project Structure

* **`core/daemon.py`**: 24/7 background listener monitoring playback state.
* **`core/optimizer.py`**: AOT compiler, cache trimmer, and tuner guard.
* **`core/power_recovery.py`**: RTC clock and TLS/DNS recovery after power cuts.
* **`config.example.json`**: Template configuration for devices and target apps.
