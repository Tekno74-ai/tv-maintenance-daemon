package io.github.tekno74.tvmaintenance.config;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Module 1 (config): Single-responsibility configuration manager.
 * Supports two operating modes:
 *  - MODE_PARALLEL:   Connects to 127.0.0.1:5555 without setting adb_enabled=0 on exit (Phase 1 NAS validation).
 *  - MODE_STANDALONE: Opens adb_enabled=1 only during maintenance and guarantees adb_enabled=0 on close (Phase 2).
 */
public final class DaemonConfig {
    private static final String TAG = "TvMaintConfig";
    private static final String PREFS_NAME = "tv_maintenance_config";

    public static final String MODE_PARALLEL = "parallel";
    public static final String MODE_STANDALONE = "standalone";

    private static final List<String> DEFAULT_CORE_APPS = Arrays.asList(
            "com.google.android.youtube.tv",
            "com.netflix.ninja",
            "com.disney.disneyplus",
            "com.amazon.amazonvideo.livingroom",
            "se.svt.android.svtplay",
            "se.tv4.tv4playtab",
            "com.hbo.hbonow",
            "com.wbd.stream",
            "com.apple.atve.androidtv.appletv",
            "com.plexapp.android",
            "com.spotify.tv.android",
            "com.viaplay.android"
    );

    private final SharedPreferences prefs;
    private final File externalConfigFile;

    public DaemonConfig(Context context) {
        Context appCtx = context.getApplicationContext();
        this.prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.externalConfigFile = new File(appCtx.getExternalFilesDir(null), "tv_config.json");
        syncFromExternalJsonIfPresent();
    }

    /**
     * Imports settings from /sdcard/Android/data/io.github.tekno74.tvmaintenance/files/tv_config.json
     * if pushed by the deployment script.
     */
    public synchronized void syncFromExternalJsonIfPresent() {
        if (externalConfigFile == null || !externalConfigFile.exists()) {
            return;
        }
        long lastModified = externalConfigFile.lastModified();
        if (lastModified <= prefs.getLong("external_json_mtime", 0L)) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(externalConfigFile), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            JSONObject json = new JSONObject(sb.toString());
            SharedPreferences.Editor ed = prefs.edit();

            if (json.has("mode")) {
                String m = json.optString("mode", MODE_PARALLEL).trim().toLowerCase();
                ed.putString("mode", MODE_STANDALONE.equals(m) ? MODE_STANDALONE : MODE_PARALLEL);
            }
            if (json.has("tv_port")) {
                ed.putInt("adb_port", json.optInt("tv_port", 5555));
            }
            if (json.has("default_launcher")) {
                ed.putString("default_launcher", json.optString("default_launcher", ""));
            }
            if (json.has("disable_accessibility_services")) {
                ed.putBoolean("disable_accessibility_services", json.optBoolean("disable_accessibility_services", false));
            }
            if (json.has("force_stop_background_apps")) {
                ed.putBoolean("force_stop_background_apps", json.optBoolean("force_stop_background_apps", true));
            }
            if (json.has("animation_scale")) {
                ed.putFloat("animation_scale", (float) json.optDouble("animation_scale", 0.5));
            }
            if (json.has("screen_off_debounce_seconds")) {
                ed.putInt("screen_off_debounce_seconds", json.optInt("screen_off_debounce_seconds", 15));
            }
            if (json.has("ha_webhook_url")) {
                ed.putString("ha_webhook_url", json.optString("ha_webhook_url", ""));
            }
            if (json.has("core_apps")) {
                JSONArray arr = json.getJSONArray("core_apps");
                Set<String> set = new LinkedHashSet<>();
                for (int i = 0; i < arr.length(); i++) {
                    String pkg = arr.optString(i, "").trim();
                    if (!pkg.isEmpty()) set.add(pkg);
                }
                ed.putStringSet("core_apps", set);
            }
            if (json.has("disabled_packages")) {
                JSONArray arr = json.getJSONArray("disabled_packages");
                Set<String> set = new LinkedHashSet<>();
                for (int i = 0; i < arr.length(); i++) {
                    String pkg = arr.optString(i, "").trim();
                    if (!pkg.isEmpty()) set.add(pkg);
                }
                ed.putStringSet("disabled_packages", set);
            }
            ed.putLong("external_json_mtime", lastModified);
            ed.apply();
            Log.i(TAG, "Synchronized configuration from " + externalConfigFile.getAbsolutePath());
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse external tv_config.json: " + e.getMessage());
        }
    }

    public String getMode() {
        return prefs.getString("mode", MODE_PARALLEL);
    }

    public boolean isStandaloneMode() {
        return MODE_STANDALONE.equalsIgnoreCase(getMode());
    }

    public void setMode(String mode) {
        String normalized = MODE_STANDALONE.equalsIgnoreCase(mode) ? MODE_STANDALONE : MODE_PARALLEL;
        prefs.edit().putString("mode", normalized).apply();
    }

    public int getAdbPort() {
        return prefs.getInt("adb_port", 5555);
    }

    public int getScreenOffDebounceSeconds() {
        return prefs.getInt("screen_off_debounce_seconds", 15);
    }

    public int getMinMinutesBetweenStandbyRuns() {
        return prefs.getInt("min_minutes_between_standby_runs", 30);
    }

    public String getDefaultLauncher() {
        return prefs.getString("default_launcher", "com.spocky.projengmenu/.ui.home.MainActivity");
    }

    public boolean isDisableAccessibilityServicesOptIn() {
        return prefs.getBoolean("disable_accessibility_services", false);
    }

    public boolean isForceStopBackgroundApps() {
        return prefs.getBoolean("force_stop_background_apps", true);
    }

    public float getAnimationScale() {
        return prefs.getFloat("animation_scale", 0.5f);
    }

    public String getHaWebhookUrl() {
        return prefs.getString("ha_webhook_url", "");
    }

    public List<String> getCoreApps() {
        Set<String> stored = prefs.getStringSet("core_apps", null);
        if (stored == null || stored.isEmpty()) {
            return Collections.unmodifiableList(DEFAULT_CORE_APPS);
        }
        return new ArrayList<>(stored);
    }

    public List<String> getDisabledPackages() {
        Set<String> stored = prefs.getStringSet("disabled_packages", null);
        if (stored == null || stored.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(stored);
    }

    public File getExternalConfigFile() {
        return externalConfigFile;
    }
}
