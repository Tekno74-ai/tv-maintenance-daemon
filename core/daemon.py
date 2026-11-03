#!/usr/bin/env python3
"""
Headless TV Daemon
24/7 background monitor for smart TV power states, active streaming playback, and live-trimming.
"""

import argparse
import logging
import os
import re
import socket
import subprocess
import sys
import time
from datetime import date
from logging.handlers import RotatingFileHandler

CORE_DIR = os.path.dirname(os.path.abspath(__file__))
BASE_DIR = os.path.dirname(CORE_DIR)
if CORE_DIR not in sys.path:
    sys.path.insert(0, CORE_DIR)

from optimizer import load_config, get_screen_state, connect_adb, is_protected_launcher
from power_recovery import heal_tv_if_needed

LOG_FILE = os.path.join(BASE_DIR, "tv_daemon.log")

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


def run_optimizer_live(config_path):
    opt_py = os.path.join(CORE_DIR, "optimizer.py")
    cmd = [sys.executable, opt_py, "--live"]
    if config_path:
        cmd += ["--config", config_path]
    try:
        subprocess.run(cmd, timeout=60)
    except Exception as e:
        logger.error(f"Failed to execute live trimming: {e}")


def get_current_app(cfg):
    target = f"{cfg['tv_ip']}:{cfg['tv_port']}"
    cmd = [cfg["adb_bin"], "-s", target, "shell", "dumpsys", "window"]
    try:
        res = subprocess.run(cmd, capture_output=True, text=True, errors="replace", timeout=5)
        for line in res.stdout.splitlines():
            if "mCurrentFocus" in line or "mFocusedApp" in line:
                m = re.search(r"([a-zA-Z0-9_\.]+)/", line)
                if m:
                    return m.group(1)
    except Exception:
        pass
    return "unknown"


def daemon_loop(config_path):
    cfg = load_config(config_path)

    logger.info(f"Starting Headless TV Daemon for {cfg['tv_ip']}:{cfg['tv_port']}...")

    was_online = False
    was_screen_on = False
    was_awake_session = False
    current_stream_app = None
    stream_start_time = None
    last_live_trim_date = None
    screen_off_time = None

    while True:
        try:
            online = is_port_open(cfg["tv_ip"], cfg["tv_port"])

            if online:
                if not was_online:
                    connect_adb(cfg)
                    heal_tv_if_needed(cfg)
                    was_online = True

                screen = get_screen_state(cfg)
                is_on = screen["screen_on"]

                if is_on:
                    if not was_screen_on:
                        logger.info("TV Screen is ON (Active viewer detected).")
                        was_screen_on = True
                        was_awake_session = True
                        screen_off_time = None

                    active_pkg = get_current_app(cfg)
                    core_pkgs = {app["pkg"] for app in cfg.get("core_apps", [])}

                    if active_pkg in core_pkgs and not is_protected_launcher(active_pkg, cfg):
                        if current_stream_app != active_pkg:
                            current_stream_app = active_pkg
                            stream_start_time = time.time()
                            logger.info(f"Playback detected in: {active_pkg}")
                        else:
                            duration_min = (time.time() - stream_start_time) / 60.0
                            wait_min = cfg.get("live_playback_wait_minutes", 5)
                            today = date.today()

                            if duration_min >= wait_min and last_live_trim_date != today:
                                logger.info(f"Continuous streaming in {active_pkg} >= {wait_min}m. Triggering live-trimming...")
                                run_optimizer_live(config_path)
                                last_live_trim_date = today
                                logger.info("Live-trimming completed safely in background.")
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

                    time.sleep(5)
            else:
                # Port 5555 closed (TV in deep sleep / WoWLAN)
                if was_online:
                    if was_awake_session:
                        if screen_off_time:
                            standby_sec = time.time() - screen_off_time
                            logger.info(f"TV entered deep sleep! Measured Light Standby duration (X): {standby_sec:.1f}s")
                        else:
                            logger.info("TV turned off and entered deep sleep directly (<10s).")
                    was_online = False
                    was_screen_on = False
                    was_awake_session = False
                    screen_off_time = None
                    current_stream_app = None

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
