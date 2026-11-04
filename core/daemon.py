#!/usr/bin/env python3
"""
TV Maintenance Daemon
24/7 background monitor for smart TV power states, active streaming playback, and live-trimming.
"""

import argparse
import json
import logging
import os
import re
import signal
import socket
import subprocess
import sys
import time
from datetime import datetime
from logging.handlers import RotatingFileHandler

CORE_DIR = os.path.dirname(os.path.abspath(__file__))
BASE_DIR = os.path.dirname(CORE_DIR)
if CORE_DIR not in sys.path:
    sys.path.insert(0, CORE_DIR)

from optimizer import load_config, get_screen_state, connect_adb, is_protected_launcher, get_foreground_app
from power_recovery import heal_tv_if_needed

LOG_DIR = os.path.join(BASE_DIR, "logs") if os.path.isdir(os.path.join(BASE_DIR, "logs")) else BASE_DIR
LOG_FILE = os.path.join(LOG_DIR, "tv_daemon.log")
METRICS_FILE = os.path.join(BASE_DIR, "tv_standby_metrics.json")

logger = logging.getLogger("tv_daemon")
logger.setLevel(logging.INFO)

handler = RotatingFileHandler(LOG_FILE, maxBytes=2 * 1024 * 1024, backupCount=2, encoding="utf-8")
formatter = logging.Formatter("[%(asctime)s] [%(levelname)s] %(message)s", datefmt="%Y-%m-%d %H:%M:%S")
handler.setFormatter(formatter)
logger.addHandler(handler)

console_handler = logging.StreamHandler(sys.stdout)
console_handler.setFormatter(formatter)
logger.addHandler(console_handler)


def is_port_open(host, port, timeout=1.5) -> bool:
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(timeout)
    try:
        res = s.connect_ex((host, port))
        return res == 0
    except Exception:
        return False
    finally:
        s.close()


def load_metrics() -> dict:
    if os.path.exists(METRICS_FILE):
        try:
            with open(METRICS_FILE, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            pass
    return {
        "last_screen_off": None,
        "last_deep_sleep": None,
        "measured_x_seconds": 0.0,
        "measured_x_human": "< 15 s (Direct Deep Sleep)",
        "last_live_maintenance": None,
        "history": []
    }


def save_metrics(data: dict):
    try:
        tmp_path = METRICS_FILE + ".tmp"
        with open(tmp_path, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2, ensure_ascii=False)
        os.replace(tmp_path, METRICS_FILE)
    except Exception as e:
        logger.warning(f"Failed to save standby metrics: {e}")


def run_optimizer_live(config_path):
    opt_py = os.path.join(CORE_DIR, "optimizer.py")
    cmd = [sys.executable, opt_py, "--live"]
    if config_path:
        cmd += ["--config", config_path]
    try:
        subprocess.run(cmd, timeout=60)
    except Exception as e:
        logger.error(f"Failed to execute live trimming: {e}")


def should_trigger_live_trim(cfg: dict, metrics: dict) -> bool:
    last_m = metrics.get("last_live_maintenance")
    if not last_m:
        return True
    try:
        last_dt = datetime.strptime(last_m, "%Y-%m-%d %H:%M:%S")
        hours_diff = (datetime.now() - last_dt).total_seconds() / 3600.0
        min_h = cfg.get("min_hours_between_maintenance", 18)
        return hours_diff >= min_h
    except Exception:
        return True


def daemon_loop(config_path):
    cfg = load_config(config_path)
    metrics = load_metrics()

    def handle_exit(sig, frame):
        logger.info("Received termination signal. Stopping TV Maintenance Daemon...")
        sys.exit(0)

    signal.signal(signal.SIGINT, handle_exit)
    signal.signal(signal.SIGTERM, handle_exit)

    logger.info(f"Starting TV Maintenance Daemon for {cfg['tv_ip']}:{cfg['tv_port']}...")

    was_online = False
    was_screen_on = False
    was_awake_session = False
    current_stream_app = None
    stream_start_time = None
    live_trim_done_session = False
    screen_off_time = None

    while True:
        try:
            online = is_port_open(cfg["tv_ip"], cfg["tv_port"])
            now_str = datetime.now().strftime("%Y-%m-%d %H:%M:%S")

            if online:
                if not was_online:
                    if connect_adb(cfg):
                        was_online = True
                    else:
                        online = False

            if online:
                screen = get_screen_state(cfg)
                if not screen.get("reachable", True):
                    # ADB dropped right as command ran (TV entering deep sleep)
                    online = False
                else:
                    is_on = screen["screen_on"]

                    if is_on:
                        if not was_screen_on:
                            logger.info("TV Screen is ON (Active viewer detected).")
                            heal_tv_if_needed(cfg)
                            was_screen_on = True
                            was_awake_session = True
                            screen_off_time = None

                        active_pkg = get_foreground_app(cfg) or "unknown"
                        core_pkgs = {app["pkg"] for app in cfg.get("core_apps", [])}

                        if active_pkg in core_pkgs and not is_protected_launcher(active_pkg, cfg):
                            if current_stream_app != active_pkg:
                                current_stream_app = active_pkg
                                stream_start_time = time.time()
                                logger.info(f"Playback detected in: {active_pkg}")
                            else:
                                duration_min = (time.time() - stream_start_time) / 60.0
                                wait_min = cfg.get("live_playback_wait_minutes", 5)

                                if duration_min >= wait_min and not live_trim_done_session:
                                    live_trim_done_session = True
                                    if should_trigger_live_trim(cfg, metrics):
                                        logger.info(f"Continuous streaming in {active_pkg} >= {wait_min}m. Triggering live-trimming...")
                                        run_optimizer_live(config_path)
                                        metrics["last_live_maintenance"] = now_str
                                        save_metrics(metrics)
                                        logger.info("Live-trimming completed safely in background.")
                                    else:
                                        logger.info("Live-trimming already performed within cooldown window.")
                        else:
                            current_stream_app = None
                            stream_start_time = None

                        time.sleep(10)
                    else:
                        # Screen is OFF (Light Standby or Doze heartbeat)
                        if was_screen_on:
                            logger.info("TV Screen turned OFF (Light Standby initiated).")
                            was_screen_on = False
                            screen_off_time = time.time()
                            current_stream_app = None
                            metrics["last_screen_off"] = now_str
                            save_metrics(metrics)

                        time.sleep(5)

            if not online:
                # Port 5555 closed (TV in deep sleep / WoWLAN)
                if was_online:
                    if was_awake_session:
                        if screen_off_time:
                            standby_sec = round(time.time() - screen_off_time, 1)
                            mins = int(standby_sec // 60)
                            secs = int(standby_sec % 60)
                            x_human = f"{mins}m {secs}s"
                            logger.info(f"TV entered deep sleep! Measured Light Standby duration (X): {x_human} ({standby_sec:.1f}s)")
                        else:
                            standby_sec = 0.0
                            x_human = "< 15s (Direct Deep Sleep)"
                            metrics["last_screen_off"] = now_str
                            logger.info(f"TV turned off and entered deep sleep directly ({x_human}).")

                        metrics["last_deep_sleep"] = now_str
                        metrics["measured_x_seconds"] = standby_sec
                        metrics["measured_x_human"] = x_human
                        hist = metrics.get("history", [])
                        hist.append({
                            "date": now_str,
                            "x_seconds": standby_sec,
                            "x_human": x_human
                        })
                        metrics["history"] = hist[-10:]
                        save_metrics(metrics)

                    was_online = False
                    was_screen_on = False
                    was_awake_session = False
                    screen_off_time = None
                    current_stream_app = None
                    live_trim_done_session = False

                time.sleep(15)

        except KeyboardInterrupt:
            logger.info("Daemon stopped by user.")
            break
        except Exception as e:
            logger.error(f"Daemon loop error: {e}")
            time.sleep(10)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Headless TV 24/7 Monitor Daemon")
    parser.add_argument("--config", help="Path to config.json")
    args = parser.parse_args()
    daemon_loop(args.config)
