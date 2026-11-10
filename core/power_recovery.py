#!/usr/bin/env python3
"""
TV Maintenance Daemon - Cold-Boot & Power Recovery
Recovers smart TVs from hardware clock reset (lack of RTC battery) and prevents Private DNS deadlock.
"""

from __future__ import annotations

import argparse
import logging
import os
import subprocess
import sys
import time
from datetime import datetime

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(BASE_DIR, "core"))
from optimizer import load_config, run_adb, connect_adb

logger = logging.getLogger("tv_recovery")
logger.setLevel(logging.INFO)
ch = logging.StreamHandler(sys.stdout)
ch.setFormatter(logging.Formatter("[%(asctime)s] [%(levelname)s] %(message)s", datefmt="%Y-%m-%d %H:%M:%S"))
logger.addHandler(ch)


def heal_tv_if_needed(cfg: dict) -> bool:
    if not connect_adb(cfg):
        return False

    tv_year_str = run_adb(cfg, ["shell", "date", "+%Y"])
    try:
        tv_year = int(tv_year_str)
    except ValueError:
        return False

    current_year = datetime.now().year
    if 0 < tv_year < current_year:
        logger.warning(f"TV hardware clock is desynchronized (reported year: {tv_year_str}) after cold boot! Healing...")

        now_ms = int(time.time() * 1000)
        # 1. Synchronize real-time clock
        run_adb(cfg, ["shell", "cmd", "alarm", "set-time", str(now_ms)])
        run_adb(cfg, ["shell", "settings", "put", "global", "auto_time", "1"])
        run_adb(cfg, ["shell", "settings", "put", "global", "auto_time_zone", "1"])
        run_adb(cfg, ["shell", "settings", "put", "global", "ntp_server", "time.google.com"])
        run_adb(cfg, ["shell", "settings", "put", "global", "private_dns_mode", "off"])
        run_adb(cfg, ["shell", "settings", "put", "global", "stay_on_while_plugged_in", "0"])
        if cfg.get("disable_accessibility_services", True):
            run_adb(cfg, ["shell", "settings", "put", "secure", "enabled_accessibility_services", "null"])
            run_adb(cfg, ["shell", "settings", "put", "secure", "accessibility_enabled", "0"])

        anim_scale = str(cfg.get("animation_scale", 0.5))
        for scale_key in ["window_animation_scale", "transition_animation_scale", "animator_duration_scale"]:
            run_adb(cfg, ["shell", "settings", "put", "global", scale_key, anim_scale])

        # 2. Re-enforce disabled bloatware packages
        raw_disabled = run_adb(cfg, ["shell", "pm", "list", "packages", "-d", "-u"])
        disabled_set = {
            line.replace("package:", "").strip()
            for line in raw_disabled.splitlines()
            if line.strip().startswith("package:")
        }
        for pkg in cfg.get("disabled_packages", []):
            if pkg not in disabled_set:
                run_adb(cfg, ["shell", "pm", "disable-user", "--user", "0", pkg])

        # 3. Default Launcher
        if cfg.get("default_launcher"):
            run_adb(cfg, ["shell", "cmd", "package", "set-home-activity", cfg["default_launcher"]])

        new_date = run_adb(cfg, ["shell", "date"])
        logger.info(f"Recovery complete. Clock synchronized to '{new_date}'.")
        return True

    return False


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="TV Maintenance Daemon - Cold-Boot & Clock Recovery")
    parser.add_argument("--config", help="Path to config.json")
    args = parser.parse_args()
    cfg = load_config(args.config)
    heal_tv_if_needed(cfg)
