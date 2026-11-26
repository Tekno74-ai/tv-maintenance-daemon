package io.github.tekno74.tvoptimizer.config;

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
 * Module 1 (config): Single-responsibility configuration manager for the standalone
 * On-Device TV Optimizer app (app lists, auto-discovery, ADB policy, and Home Assistant telemetry).
 */
public final class DaemonConfig {
    private static final String TAG = "TvMaintConfig";
    private static final String PREFS_NAME = "tv_maintenance_config";

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
     * Imports settings from /sdcard/Android/data/io.github.tekno74.tvoptimizer/files/tv_config.json
     * if pushed by the deployment script.
     */
    public synchronized void syncFromExternalJsonIfPresent() {
        if (externalConfigFile == null || !externalConfigFile.exists()) {
            return;
        }
        long lastModified = externalConfigFile.lastModified();
        boolean hasCorruptCoreApps = false;
        Set<String> existingCore = prefs.getStringSet("core_apps", null);
        if (existingCore != null) {
            for (String item : existingCore) {
                if (item != null && item.trim().startsWith("{")) {
                    hasCorruptCoreApps = true;
                    break;
                }
            }
        }
        if (!hasCorruptCoreApps && lastModified <= prefs.getLong("external_json_mtime", 0L)) {
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
                ed.putString("mode", MODE_STANDALONE);
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
            if (json.has("auto_discover_user_apps")) {
                ed.putBoolean("auto_discover_user_apps", json.optBoolean("auto_discover_user_apps", true));
            }
            if (json.has("keep_adb_open")) {
                ed.putBoolean("keep_adb_open", json.optBoolean("keep_adb_open", true));
            }
            if (json.has("animation_scale")) {
                ed.putFloat("animation_scale", (float) json.optDouble("animation_scale", 0.5));
            }
            if (json.has("screen_off_debounce_seconds")) {
                ed.putInt("screen_off_debounce_seconds", json.optInt("screen_off_debounce_seconds", 15));
            }
            if (json.has("live_trim_interval_minutes")) {
                ed.putInt("live_trim_interval_minutes", json.optInt("live_trim_interval_minutes", 60));
            }
            if (json.has("ha_webhook_url")) {
                ed.putString("ha_webhook_url", json.optString("ha_webhook_url", ""));
            }
            if (json.has("ha_url")) {
                ed.putString("ha_url", json.optString("ha_url", ""));
            }
            if (json.has("ha_token")) {
                ed.putString("ha_token", json.optString("ha_token", ""));
            }
            if (json.has("protected_packages")) {
                JSONArray arr = json.getJSONArray("protected_packages");
                Set<String> set = new LinkedHashSet<>();
                for (int i = 0; i < arr.length(); i++) {
                    String pkg = arr.optString(i, "").trim();
                    if (!pkg.isEmpty()) set.add(pkg);
                }
                ed.putStringSet("protected_packages", set);
            }
            if (json.has("core_apps")) {
                JSONArray arr = json.getJSONArray("core_apps");
                Set<String> set = new LinkedHashSet<>();
                for (int i = 0; i < arr.length(); i++) {
                    String pkg = extractPackageName(arr, i);
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
        return MODE_STANDALONE;
    }

    public boolean isStandaloneMode() {
        return true;
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

    public int getLiveTrimIntervalMinutes() {
        return prefs.getInt("live_trim_interval_minutes", 60);
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

    public boolean isAutoDiscoverUserApps() {
        return prefs.getBoolean("auto_discover_user_apps", true);
    }

    public boolean isKeepAdbOpen() {
        return prefs.getBoolean("keep_adb_open", false);
    }

    public void setKeepAdbOpen(boolean keepOpen) {
        prefs.edit().putBoolean("keep_adb_open", keepOpen).apply();
    }

    public boolean shouldCloseAdbAfterRun() {
        return !isKeepAdbOpen();
    }

    public float getAnimationScale() {
        return prefs.getFloat("animation_scale", 0.5f);
    }

    public String getHaWebhookUrl() {
        return prefs.getString("ha_webhook_url", "");
    }

    public String getHaUrl() {
        return prefs.getString("ha_url", "");
    }

    public String getHaToken() {
        return prefs.getString("ha_token", "");
    }

    public Set<String> getProtectedPackages() {
        Set<String> stored = prefs.getStringSet("protected_packages", null);
        if (stored == null || stored.isEmpty()) {
            return Collections.emptySet();
        }
        return new LinkedHashSet<>(stored);
    }

    public List<String> getCoreApps() {
        Set<String> stored = prefs.getStringSet("core_apps", null);
        if (stored == null || stored.isEmpty()) {
            return Collections.unmodifiableList(DEFAULT_CORE_APPS);
        }
        List<String> sanitized = new ArrayList<>();
        for (String item : stored) {
            String pkg = sanitizePackageEntry(item);
            if (!pkg.isEmpty() && !sanitized.contains(pkg)) {
                sanitized.add(pkg);
            }
        }
        return sanitized.isEmpty() ? Collections.unmodifiableList(DEFAULT_CORE_APPS) : sanitized;
    }

    private static String extractPackageName(JSONArray arr, int index) {
        JSONObject obj = arr.optJSONObject(index);
        if (obj != null) {
            return obj.optString("pkg", "").trim();
        }
        return sanitizePackageEntry(arr.optString(index, ""));
    }

    private static String sanitizePackageEntry(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        if (trimmed.startsWith("{")) {
            try {
                return new JSONObject(trimmed).optString("pkg", "").trim();
            } catch (Exception ignored) {
                return "";
            }
        }
        return trimmed;
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
