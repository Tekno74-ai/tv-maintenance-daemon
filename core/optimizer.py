#!/usr/bin/env python3
"""
Headless TV Optimizer Engine
Autonomous maintenance, AOT speed-compilation, and memory management for Android TV.
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
LOCK_FILE = os.path.join("/tmp" if os.name != "nt" else os.environ.get("TEMP", "C:\\Temp"), "headless_tv_optimizer.lock")
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
    """Ensures core apps reside on fast internal eMMC storage and compiles AOT speed profiles."""
    core_apps = cfg.get("core_apps", [])
    if not core_apps:
        return

    logger.info("Checking storage placement and AOT compilation status for core apps...")
    for app in core_apps:
        pkg = app["pkg"]
        name = app.get("name", pkg)
        path_out = run_adb(cfg, ["shell", "pm", "path", pkg])
        if not path_out:
            continue

        # Migrate back to internal eMMC if placed on slower USB adoptable storage
        if "/mnt/expand/" in path_out:
            logger.info(f"  -> {name}: Located on external USB storage. Moving to internal eMMC...")
            run_adb(cfg, ["shell", "pm", "move-package", pkg, "internal"], timeout=120)

        dump = run_adb(cfg, ["shell", "dumpsys", "package", pkg])
        if not dump or "Unable to find package" in dump:
            continue

        if "[status=speed]" in dump:
            logger.info(f"  -> {name}: Already compiled with speed profile.")
        else:
            logger.info(f"  -> {name}: Compiling AOT speed profile (dex2oat)...")
            res = run_adb(cfg, ["shell", "cmd", "package", "compile", "-m", "speed", "-f", pkg], timeout=90)
            logger.info(f"     Result: {res}")


def trim_memory_and_caches(cfg: dict, live_mode=False):
    """Trims app cache buffers and invokes storage fstrim."""
    logger.info(f"Running cache trimming and filesystem fstrim (live_mode={live_mode})...")
    run_adb(cfg, ["shell", "pm", "trim-caches", "4096M"], timeout=15)
    run_adb(cfg, ["shell", "sm", "fstrim"], timeout=30)

    if not live_mode:
        # Full maintenance: selectively stop background streaming apps
        core_apps = cfg.get("core_apps", [])
        for app in core_apps:
            pkg = app["pkg"]
            if is_protected_launcher(pkg, cfg):
                continue
            run_adb(cfg, ["shell", "am", "force-stop", pkg], timeout=5)
        logger.info("Selective background app force-stop completed.")


def get_telemetry(cfg: dict) -> dict:
    """Reads available RAM, storage metrics, and ANRs."""
    meminfo = run_adb(cfg, ["shell", "cat", "/proc/meminfo"], timeout=5)
    avail_mb = 0
    m = re.search(r"MemAvailable:\s+(\d+)\s+kB", meminfo)
    if m:
        avail_mb = int(int(m.group(1)) / 1024)

    df = run_adb(cfg, ["shell", "df", "-h", "/data"], timeout=5)
    free_data = "Unknown"
    for line in df.splitlines():
        if "/data" in line:
            parts = line.split()
            if len(parts) >= 4:
                free_data = parts[3]

    return {
        "timestamp": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "available_ram_mb": avail_mb,
        "free_data_storage": free_data
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
        existing["headless_tv_optimizer"] = {
            "name": "TV Optimizer & Health",
            "status": status_text,
            "last_run": telemetry["timestamp"],
            "details": f"Available RAM: {telemetry['available_ram_mb']} MB | Storage Free: {telemetry['free_data_storage']}",
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

        # 1. Policies & Bloatware Shield
        apply_system_policies(cfg)

        # 2. AOT Compilations (Skipped in live mode to preserve CPU)
        if not live_mode:
            optimize_aot(cfg)

        # 3. Cache & Memory trim
        trim_memory_and_caches(cfg, live_mode=live_mode)

        # 4. Telemetry
        telemetry = get_telemetry(cfg)
        logger.info(f"Maintenance complete. Available RAM: {telemetry['available_ram_mb']} MB | Free Storage: {telemetry['free_data_storage']}")
        write_ha_status(cfg, telemetry, "Success")

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
