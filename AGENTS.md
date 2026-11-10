# AGENTS.md – Rules for AI Agents in TV Maintenance Daemon

> **READ THIS FIRST** before reading, creating, or editing code in this repository.  
> See [`README.md`](README.md) for full architectural documentation.

---

## 1. Critical Invariants & Safety Guardrails

1. **100% Dark Headless Maintenance:**
   - Nightly maintenance runs with the screen completely OFF (`mWakefulness=Asleep`, `mState=OFF`).
   - `Settings.Global.STAY_ON_WHILE_PLUGGED_IN` MUST always remain `0`.
   - NEVER execute `svc power stayon true` or any command that wakes the physical display backlights.
2. **Accessibility Key-Filter Guard:**
   - Keep `enabled_accessibility_services` disabled (`null`) by default unless the user explicitly enables an assistive service. This prevents 100–300 ms D-pad lag and `KEYCODE_BACK` dropouts caused by third-party launcher key hooking.
3. **Never Disable Wi-Fi Interface:**
   - Disabling Wi-Fi via `svc wifi disable` completely cuts remote ADB connectivity and bricks Wake-on-LAN reception.
4. **Configurable Package Policy:**
   - Respect user configurations in `disabled_packages` (`pm disable-user --user 0`). Never force-disable tuners for users who rely on aerial antennas.
5. **AOT Speed-Compilation Invariant:**
   - Ahead-of-Time compilation must use `cmd package compile -m speed -f <pkg>`.
   - Never force compilation during active user streaming sessions.

---

## 2. Deterministic Commands & Verification

Run from the project root:

```bash
# 1. Run Python syntax & import check on core modules:
python -m py_compile core/daemon.py core/optimizer.py core/power_recovery.py

# 2. Test Docker build:
docker build -t tv-maintenance-daemon:test .

# 3. Test optimizer status check (dry run):
python core/optimizer.py --status
```

---

## 3. Project Structure

* **`core/daemon.py`**: 24/7 background listener monitoring playback state.
* **`core/optimizer.py`**: AOT compiler, cache trimmer, and tuner guard.
* **`core/power_recovery.py`**: RTC clock and TLS/DNS recovery after power cuts.
* **`config.example.json`**: Template configuration for devices and target apps.
