#!/usr/bin/env python3
"""
TV Maintenance Daemon Engine
Autonomous background maintenance, AOT speed-compilation, and memory management for Android TV.
"""

import argparse
import json
import logging
import os
import re
import socket
import subprocess
import sys
import time
from datetime import datetime
from logging.handlers import RotatingFileHandler

try:
    import fcntl
    HAS_FCNTL = True
except ImportError:
    HAS_FCNTL = False

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_CONFIG_PATH = os.path.join(BASE_DIR, "config.json")
LOCK_FILE = os.path.join("/tmp" if os.name != "nt" else os.environ.get("TEMP", "C:\\Temp"), "tv_maintenance_daemon.lock")
LOG_FILE = os.path.join(BASE_DIR, "tv_optimizer.log")

# Setup clean logger
logger = logging.getLogger("tv_optimizer")
logger.setLevel(logging.INFO)

handler = RotatingFileHandler(LOG_FILE, maxBytes=2 * 1024 * 1024, backupCount=2, encoding="utf-8")
formatter = logging.Formatter("[%(asctime)s] [%(levelname)s] %(message)s", datefmt="%Y-%m-%d %H:%M:%S")
handler.setFormatter(formatter)
logger.addHandler(handler)

console_handler = logging.StreamHandler(sys.stdout)
console_handler.setFormatter(formatter)
logger.addHandler(console_handler)


def acquire_lock():
    if not HAS_FCNTL:
        return None
    lf = open(LOCK_FILE, "w", encoding="utf-8")
    try:
        fcntl.flock(lf, fcntl.LOCK_EX | fcntl.LOCK_NB)
        return lf
    except (BlockingIOError, IOError):
        logger.warning("Another instance of the optimizer is already running. Exiting.")
        sys.exit(0)


def release_lock(lock):
    if HAS_FCNTL and lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_UN)
            lock.close()
        except Exception:
            pass


def load_config(path=None) -> dict:
    config_file = path or os.environ.get("TV_CONFIG_PATH", DEFAULT_CONFIG_PATH)
    if not os.path.exists(config_file):
        example_path = os.path.join(BASE_DIR, "config.example.json")
        if os.path.exists(example_path):
            logger.warning(f"Config '{config_file}' not found. Falling back to '{example_path}'.")
            config_file = example_path
        else:
            raise FileNotFoundError(f"Configuration file not found: {config_file}")

    with open(config_file, "r", encoding="utf-8") as f:
        cfg = json.load(f)

    # Defaults & Environment overrides
    cfg["adb_bin"] = os.environ.get("ADB_PATH", cfg.get("adb_path", "adb"))
    cfg["tv_ip"] = os.environ.get("TV_IP", cfg.get("tv_ip", "127.0.0.1"))
    cfg["tv_port"] = int(os.environ.get("TV_PORT", cfg.get("tv_port", 5555)))
    cfg["tv_mac"] = os.environ.get("TV_MAC", cfg.get("tv_mac", ""))
    return cfg


def send_wol(cfg: dict):
    if not cfg.get("tv_mac"):
        return
    try:
        mac = cfg["tv_mac"].replace(":", "").replace("-", "")
        mac_bytes = bytes.fromhex(mac)
        magic_packet = b"\xff" * 6 + mac_bytes * 16
        broadcast_ip = cfg.get("wol_broadcast", "255.255.255.255")
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            sock.sendto(magic_packet, (broadcast_ip, 9))
        logger.info(f"Wake-on-LAN magic packet sent to {cfg['tv_mac']} via {broadcast_ip}:9")
    except Exception as e:
        logger.error(f"Failed to send Wake-on-LAN packet: {e}")


def run_adb(cfg: dict, args: list, timeout=30, retry=True) -> str:
    target = f"{cfg['tv_ip']}:{cfg['tv_port']}"
    cmd = [cfg["adb_bin"], "-s", target] + args
    try:
        res = subprocess.run(cmd, capture_output=True, text=True, errors="replace", timeout=timeout)
        if res.returncode != 0:
            err = (res.stderr or "").lower()
            if retry and any(k in err for k in ["device offline", "device not found", "error: closed", "device unauthorized"]):
                logger.warning(f"ADB disconnected ({err.strip()}). Attempting reconnection...")
                if connect_adb(cfg):
                    return run_adb(cfg, args, timeout=timeout, retry=False)
        return res.stdout.strip()
    except subprocess.TimeoutExpired:
        logger.warning(f"ADB command timed out after {timeout}s: {' '.join(args)}")
        if retry and connect_adb(cfg):
            return run_adb(cfg, args, timeout=timeout, retry=False)
        return ""
    except Exception as e:
        logger.error(f"Error executing ADB: {e}")
        return ""


def connect_adb(cfg: dict, max_retries=3, retry_delay=1.5, resend_wol=False) -> bool:
    target = f"{cfg['tv_ip']}:{cfg['tv_port']}"
    adb = cfg["adb_bin"]
    for attempt in range(1, max_retries + 1):
        try:
            chk = subprocess.run([adb, "devices"], capture_output=True, text=True, timeout=5)
            if f"{target}\toffline" in chk.stdout:
                subprocess.run([adb, "disconnect", target], capture_output=True, timeout=5)
            res = subprocess.run([adb, "connect", target], capture_output=True, text=True, timeout=5)
            out_lower = (res.stdout or "").lower()
            if ("connected to" in out_lower or "already connected" in out_lower) and "cannot" not in out_lower and "failed" not in out_lower:
                # Verify responsive shell
                ping = subprocess.run([adb, "-s", target, "shell", "echo", "ok"], capture_output=True, text=True, timeout=5)
                if ping.returncode == 0 and "ok" in ping.stdout:
                    return True
        except Exception:
            pass
        if resend_wol and attempt == 5:
            send_wol(cfg)
        time.sleep(retry_delay)
    return False


def get_screen_state(cfg: dict) -> dict:
    """Returns wakefulness and physical display state."""
    dump = run_adb(cfg, ["shell", "dumpsys", "display"], timeout=5)
    is_screen_on = False
    m = re.search(r"mState=(ON|OFF)", dump)
    if m:
        is_screen_on = (m.group(1) == "ON")
    else:
        power = run_adb(cfg, ["shell", "dumpsys", "power"], timeout=5)
        is_screen_on = "Display Power: state=ON" in power or "mInteractive=true" in power

    p_dump = run_adb(cfg, ["shell", "dumpsys", "power"], timeout=5)
    wake_m = re.search(r"mWakefulness=(Awake|Asleep|Dreaming|Dozing)", p_dump)
    wakefulness = wake_m.group(1) if wake_m else "Unknown"

    return {"screen_on": is_screen_on, "wakefulness": wakefulness}


def is_protected_launcher(pkg: str, cfg: dict) -> bool:
    default_launcher_pkg = (cfg.get("default_launcher") or "").split("/")[0]
    if default_launcher_pkg and pkg == default_launcher_pkg:
        return True
    return any(k in pkg.lower() for k in ["launcher", "projengmenu", "tvhome", "flauncher"])


def get_foreground_app(cfg: dict) -> str:
    """Identifies the package name of the app currently in the foreground."""
    out = run_adb(cfg, ["shell", "dumpsys", "window"], timeout=5)
    for line in out.splitlines():
        if "mCurrentFocus" in line or "mFocusedApp" in line:
            m = re.search(r"([a-zA-Z0-9_\.]+)/", line)
            if m:
                return m.group(1)
    act = run_adb(cfg, ["shell", "dumpsys", "activity", "activities"], timeout=5)
    for line in act.splitlines():
        if "mResumedActivity" in line:
            m = re.search(r"([a-zA-Z0-9_\.]+)/", line)
            if m:
                return m.group(1)
    return ""


def check_anr_and_health(cfg: dict) -> tuple[int, int]:
    """
    Analyzes /data/anr crash files (distinguishing fresh ANRs <anr_max_age_hours from stale ones)
    and scans logcat for skipped UI frames and major page faults.
    """
    logger.info("Inspecting ANR crash traces and system logcat health...")
    anr_stat = run_adb(cfg, ["shell", "stat -c '%n %Y' /data/anr/* 2>/dev/null"])
    now_epoch = int(time.time())
    max_age_sec = cfg.get("anr_max_age_hours", 24) * 3600

    recent_anr_count = 0
    old_anr_count = 0

    if anr_stat and "No such file" not in anr_stat:
        for line in anr_stat.splitlines():
            parts = line.strip().split()
            if len(parts) >= 2 and parts[-1].isdigit():
                filepath = parts[0]
                mtime = int(parts[-1])
                age_hours = (now_epoch - mtime) / 3600.0
                if (now_epoch - mtime) <= max_age_sec:
                    recent_anr_count += 1
                    logger.warning(f"  -> Fresh ANR detected: {filepath} ({age_hours:.1f}h old)")
                else:
                    old_anr_count += 1
                    del_out = run_adb(cfg, ["shell", f"rm -f {filepath} 2>/dev/null && echo DELETED"])
                    if "DELETED" in del_out:
                        logger.info(f"  -> Removed stale ANR file: {filepath}")

    if recent_anr_count == 0 and old_anr_count == 0:
        logger.info("  -> 0 ANR files in /data/anr.")
    elif recent_anr_count == 0 and old_anr_count > 0:
        logger.info(f"  -> 0 fresh ANRs (ignored {old_anr_count} historical ANR traces >{cfg.get('anr_max_age_hours', 24)}h old).")

    logcat_out = run_adb(cfg, ["shell", "logcat", "-d", "-t", "1000"], timeout=15)
    skipped_frames = 0
    page_faults = 0
    for line in logcat_out.splitlines():
        if "Skipped " in line and " frames!" in line:
            skipped_frames += 1
        if "major page fault" in line.lower():
            page_faults += 1

    logger.info(f"  -> Logcat (last 1000 lines): {skipped_frames} UI frame-drop warnings, {page_faults} major page faults.")
    return recent_anr_count, skipped_frames


def apply_system_policies(cfg: dict):
    """Enforces clock sanity, bloatware shield, launcher, headless power settings, and UI animation scales."""
    logger.info("Applying system policies, bloatware shield, and performance settings...")

    # 0. Cold-Boot RTC Clock Sanity Check
    tv_year_str = run_adb(cfg, ["shell", "date", "+%Y"])
    try:
        if 0 < int(tv_year_str) < datetime.now().year:
            now_ms = int(time.time() * 1000)
            run_adb(cfg, ["shell", "cmd", "alarm", "set-time", str(now_ms)])
            run_adb(cfg, ["shell", "settings", "put", "global", "auto_time", "1"])
            run_adb(cfg, ["shell", "settings", "put", "global", "auto_time_zone", "1"])
            run_adb(cfg, ["shell", "settings", "put", "global", "ntp_server", "time.google.com"])
            logger.info(f"Synchronized desynchronized TV hardware clock (was year {tv_year_str}).")
    except ValueError:
        pass

    # 1. Bloatware & Tuner Shield
    raw_disabled = run_adb(cfg, ["shell", "pm", "list", "packages", "-d", "-u"])
    disabled_set = {
        line.replace("package:", "").strip()
        for line in raw_disabled.splitlines()
        if line.strip().startswith("package:")
    }

    packages_to_disable = cfg.get("disabled_packages", [])
    for pkg in packages_to_disable:
        if pkg not in disabled_set:
            res = run_adb(cfg, ["shell", "pm", "disable-user", "--user", "0", pkg])
            logger.info(f"Disabled package ({pkg}): {res}")

    # 2. Default Launcher Enforcer
    default_launcher = cfg.get("default_launcher")
    if default_launcher:
        run_adb(cfg, ["shell", "cmd", "package", "set-home-activity", default_launcher])

    # 3. Rule 1: Keep accessibility services disabled to prevent remote button lags
    acc = run_adb(cfg, ["shell", "settings", "get", "secure", "enabled_accessibility_services"])
    if acc and acc != "null":
        run_adb(cfg, ["shell", "settings", "put", "secure", "enabled_accessibility_services", "null"])
        run_adb(cfg, ["shell", "settings", "put", "secure", "accessibility_enabled", "0"])
        logger.info("Reset enabled_accessibility_services to null.")

    # 4. Headless Power & Storage Policies
    run_adb(cfg, ["shell", "settings", "put", "global", "wifi_scan_always_enabled", "0"])
    run_adb(cfg, ["shell", "settings", "put", "global", "ble_scan_always_enabled", "0"])
    # CRITICAL: stay_on_while_plugged_in=0 ensures Linux kernel stays headless without waking display
    run_adb(cfg, ["shell", "settings", "put", "global", "stay_on_while_plugged_in", "0"])

    ext_allow = run_adb(cfg, ["shell", "settings", "get", "global", "force_allow_on_external"])
    if ext_allow and ext_allow != "0":
        run_adb(cfg, ["shell", "settings", "put", "global", "force_allow_on_external", "0"])

    # 5. UI Animation Scale Optimization
    anim_scale = str(cfg.get("animation_scale", 0.5))
    for scale_key in ["window_animation_scale", "transition_animation_scale", "animator_duration_scale"]:
        cur = run_adb(cfg, ["shell", "settings", "get", "global", scale_key])
        if cur != anim_scale:
            run_adb(cfg, ["shell", "settings", "put", "global", scale_key, anim_scale])
            logger.info(f"Set {scale_key} to {anim_scale} (was: {cur})")

    # 6. Private DNS Safety Check
    desired_dns = cfg.get("desired_private_dns", "off")
    cur_dns = run_adb(cfg, ["shell", "settings", "get", "global", "private_dns_mode"])
    if desired_dns == "off" and cur_dns != "off":
        run_adb(cfg, ["shell", "settings", "put", "global", "private_dns_mode", "off"])
        logger.info("Set private_dns_mode to 'off' (prevents cold-boot TLS deadlocks)")


def optimize_aot(cfg: dict):
    """Ensures core apps are AOT speed-compiled (dex2oat) on fast internal eMMC."""
    core_apps = cfg.get("core_apps", [])
    if not core_apps:
        return

    logger.info("Checking AOT compilation status for core apps...")
    run_adb(cfg, ["shell", "pm", "set-install-location", "0"])

    for app in core_apps:
        pkg = app["pkg"]
        name = app.get("name", pkg)
        path_out = run_adb(cfg, ["shell", "pm", "path", pkg])
        if not path_out:
            continue

        dump = run_adb(cfg, ["shell", "dumpsys", "package", pkg])
        if not dump or "Unable to find package" in dump:
            continue

        if "[status=speed]" in dump:
            logger.info(f"  -> {name}: Already compiled with speed profile.")
        else:
            logger.info(f"  -> {name}: Compiling AOT speed profile (dex2oat)...")
            res = run_adb(cfg, ["shell", "cmd", "package", "compile", "-m", "speed", "-f", pkg], timeout=90)
            logger.info(f"     Result: {res}")


def measure_cold_starts(cfg: dict) -> dict:
    """
    Opt-in cold-start benchmark for core apps using 'am start -W -S'.
    Note: Launching activities via ActivityManager wakes the display on Android TV,
    so this is disabled by default during unattended night runs.
    """
    logger.info("Measuring cold-start latency (TotalTime ms) for core apps...")
    results = {}
    for app in cfg.get("core_apps", []):
        name = app.get("name", app["pkg"])
        pkg = app["pkg"]
        act = app.get("activity")
        if not act:
            continue

        out = run_adb(cfg, ["shell", "am", "start", "-W", "-S", "-n", act], timeout=25)
        total_time = None
        for line in out.splitlines():
            if line.startswith("TotalTime:"):
                try:
                    total_time = int(line.split(":")[1].strip())
                except ValueError:
                    pass
        if total_time is not None:
            results[name] = total_time
            logger.info(f"  -> {name:12s}: {total_time} ms")
        time.sleep(2)
        if not is_protected_launcher(pkg, cfg):
            run_adb(cfg, ["shell", "am", "force-stop", pkg])

    default_launcher = cfg.get("default_launcher")
    if default_launcher:
        run_adb(cfg, ["shell", "am", "start", "-n", default_launcher])
    return results


def trim_memory_and_caches(cfg: dict, live_mode=False):
    """Trims app cache buffers and invokes storage fstrim, protecting the active foreground app."""
    fg_pkg = get_foreground_app(cfg) if live_mode else ""
    logger.info(f"Running cache trimming and filesystem fstrim (live_mode={live_mode}, foreground={fg_pkg or 'none'})...")
    run_adb(cfg, ["shell", "pm", "trim-caches", "4096M"], timeout=15)
    run_adb(cfg, ["shell", "sm", "fstrim"], timeout=30)

    if not live_mode:
        # Full maintenance: selectively stop background streaming apps
        core_apps = cfg.get("core_apps", [])
        for app in core_apps:
            pkg = app["pkg"]
            if is_protected_launcher(pkg, cfg) or (fg_pkg and pkg == fg_pkg):
                continue
            run_adb(cfg, ["shell", "am", "force-stop", pkg], timeout=5)
        logger.info("Selective background app force-stop completed.")


def get_telemetry(cfg: dict) -> dict:
    """Reads available RAM and internal storage metrics."""
    meminfo = run_adb(cfg, ["shell", "cat", "/proc/meminfo"], timeout=5)
    avail_mb = 0
    m = re.search(r"MemAvailable:\s+(\d+)\s+kB", meminfo)
    if m:
        avail_mb = int(int(m.group(1)) / 1024)
    else:
        free_out = run_adb(cfg, ["shell", "free", "-m"], timeout=5)
        for line in free_out.splitlines():
            if line.lower().startswith("mem:"):
                parts = line.split()
                if len(parts) >= 7 and parts[6].isdigit():
                    avail_mb = int(parts[6])
                elif len(parts) >= 4 and parts[3].isdigit():
                    avail_mb = int(parts[3])

    df = run_adb(cfg, ["shell", "df", "-h", "/data"], timeout=5)
    free_data = "Unknown"
    data_pct = 0
    for line in df.splitlines():
        if "/data" in line:
            parts = line.split()
            if len(parts) >= 4:
                free_data = parts[3]
            if len(parts) >= 5 and "%" in parts[4]:
                pct_str = parts[4].replace("%", "")
                if pct_str.isdigit():
                    data_pct = int(pct_str)

    return {
        "timestamp": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "available_ram_mb": avail_mb,
        "free_data_storage": free_data,
        "data_used_pct": data_pct
    }


def write_ha_status(cfg: dict, telemetry: dict, status_text: str):
    out_path = cfg.get("ha_status_output_path")
    if not out_path:
        return
    try:
        os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
        existing = {}
        if os.path.exists(out_path):
            try:
                with open(out_path, "r", encoding="utf-8") as f:
                    existing = json.load(f)
            except Exception:
                existing = {}

        if not isinstance(existing, dict):
            existing = {}

        existing["updated_at"] = telemetry["timestamp"]
        existing["tv_maintenance_daemon"] = {
            "name": "TV Maintenance Daemon",
            "status": status_text,
            "last_run": telemetry["timestamp"],
            "details": (
                f"Available RAM: {telemetry['available_ram_mb']} MB | "
                f"Internal Storage Free: {telemetry['free_data_storage']} | "
                f"Fresh ANRs: {telemetry.get('recent_anrs', 0)}"
            ),
            "icon": "mdi:television-shimmer"
        }
        tmp_path = out_path + ".tmp"
        with open(tmp_path, "w", encoding="utf-8") as f:
            json.dump(existing, f, indent=2, ensure_ascii=False)
        os.replace(tmp_path, out_path)
    except Exception as e:
        logger.debug(f"Failed to write HA status: {e}")


def run_maintenance(cfg: dict, wake=False, live_mode=False):
    lock = acquire_lock()
    try:
        logger.info(f"Starting TV maintenance run (wake={wake}, live={live_mode})...")
        if wake and cfg.get("allow_wol_wake", True):
            send_wol(cfg)
            time.sleep(3)

        retries = 12 if wake else 3
        delay = 2.5 if wake else 1.5
        if not connect_adb(cfg, max_retries=retries, retry_delay=delay, resend_wol=wake):
            logger.warning("Could not connect to TV via ADB. Exiting.")
            return

        screen = get_screen_state(cfg)
        logger.info(f"TV State: Screen={'ON' if screen['screen_on'] else 'OFF'}, Wakefulness={screen['wakefulness']}")

        if not live_mode and screen["screen_on"]:
            logger.warning("Viewer is currently watching TV. Aborting full night maintenance to prevent interruption.")
            return

        # 1. ANR & System Health Analysis
        recent_anrs, _ = check_anr_and_health(cfg)

        # 2. Policies & Bloatware Shield
        apply_system_policies(cfg)

        # 3. AOT Compilations (Skipped in live mode to preserve CPU)
        if not live_mode:
            optimize_aot(cfg)

        # 4. Cache & Memory trim
        trim_memory_and_caches(cfg, live_mode=live_mode)

        # 5. Telemetry
        telemetry = get_telemetry(cfg)

        # 6. Opt-in Cold-Start Benchmarking & Weekly Reboot (Unattended only)
        if not live_mode:
            if cfg.get("measure_cold_starts_unattended", False):
                measure_cold_starts(cfg)

            if datetime.now().weekday() == 0 and cfg.get("weekly_reboot", False):
                logger.info("Monday & weekly_reboot=True: Rebooting TV cleanly...")
                run_adb(cfg, ["shell", "reboot"])

        telemetry["recent_anrs"] = recent_anrs
        status_label = "Warning" if recent_anrs > 0 else "Success"
        logger.info(
            f"Maintenance complete. Available RAM: {telemetry['available_ram_mb']} MB | "
            f"Internal Free: {telemetry['free_data_storage']} | Fresh ANRs: {recent_anrs}"
        )
        write_ha_status(cfg, telemetry, status_label)

    finally:
        # Failsafe Standby Enforcer: If screen was accidentally woken during unattended run, turn it off
        if not live_mode and cfg.get("enforce_sleep_after_maintenance", True):
            try:
                screen = get_screen_state(cfg)
                if screen["screen_on"]:
                    logger.info("Enforcing display standby (sending KEYCODE_SLEEP)...")
                    run_adb(cfg, ["shell", "input", "keyevent", "223"], timeout=5)
            except Exception:
                pass
        release_lock(lock)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Headless TV Optimizer Engine")
    parser.add_argument("--config", help="Path to config.json")
    parser.add_argument("--wake", action="store_true", help="Send Wake-on-LAN and perform headless night maintenance")
    parser.add_argument("--live", action="store_true", help="Perform gentle live trimming during active streaming")
    parser.add_argument("--status", action="store_true", help="Show current TV status and telemetry")
    args = parser.parse_args()

    cfg = load_config(args.config)
    if args.status:
        if connect_adb(cfg):
            screen = get_screen_state(cfg)
            telem = get_telemetry(cfg)
            print(json.dumps({"screen": screen, "telemetry": telem}, indent=2))
        else:
            print("TV unreachable")
    else:
        run_maintenance(cfg, wake=args.wake, live_mode=args.live)
