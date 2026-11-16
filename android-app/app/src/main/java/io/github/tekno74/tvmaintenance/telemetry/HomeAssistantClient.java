package io.github.tekno74.tvmaintenance.telemetry;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import io.github.tekno74.tvmaintenance.config.DaemonConfig;

/**
 * Module 3b (telemetry): Persists telemetry snapshots locally (for parallel verification and UI)
 * and publishes parallel Phase 1 state directly to Home Assistant REST API
 * (binary_sensor.tv_spelar_app & sensor.tv_maintenance_app) plus optional webhook.
 */
public final class HomeAssistantClient {
    private static final String TAG = "TvMaintTelemetry";
    private static final String PREFS_TELEMETRY = "tv_maintenance_telemetry";

    public static void publishStatus(
            Context context,
            DaemonConfig config,
            String eventType,
            String statusMsg,
            long memBeforeMb,
            long memAfterMb,
            int appsStopped,
            int appsCompiled
    ) {
        publishStatus(context, config, eventType, statusMsg, memBeforeMb, memAfterMb, appsStopped, appsCompiled, 0);
    }

    public static void publishStatus(
            Context context,
            DaemonConfig config,
            String eventType,
            String statusMsg,
            long memBeforeMb,
            long memAfterMb,
            int appsStopped,
            int appsCompiled,
            int freshAnrs
    ) {
        Context appCtx = context.getApplicationContext();
        MetricsCollector.SystemSnapshot snap = MetricsCollector.collect(appCtx);
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());

        int adbEnabled = -1;
        try {
            adbEnabled = Settings.Global.getInt(appCtx.getContentResolver(), Settings.Global.ADB_ENABLED, -1);
        } catch (Exception ignored) {
        }

        boolean screenIsOn = "SCREEN_ON".equals(eventType) || "LIVE_TRIM".equals(eventType)
                || (snap.interactive && !"SCREEN_OFF".equals(eventType) && !"SCREEN_OFF_PENDING".equals(eventType));

        try {
            JSONObject payload = new JSONObject();
            payload.put("timestamp", timestamp);
            payload.put("mode", config.getMode());
            payload.put("event", eventType);
            payload.put("status", statusMsg);
            payload.put("screen_state", screenIsOn ? "ON" : "OFF");
            payload.put("mem_available_mb", snap.memAvailableMb);
            payload.put("mem_total_mb", snap.memTotalMb);
            payload.put("mem_before_mb", memBeforeMb);
            payload.put("mem_after_mb", memAfterMb);
            payload.put("mem_freed_mb", Math.max(0L, memAfterMb - memBeforeMb));
            payload.put("data_free_gb", snap.dataFreeGb);
            payload.put("data_total_gb", snap.dataTotalGb);
            payload.put("apps_stopped", appsStopped);
            payload.put("apps_compiled", appsCompiled);
            payload.put("fresh_anrs", freshAnrs);
            payload.put("adb_enabled_after", adbEnabled);

            String jsonStr = payload.toString(2);

            // 1. Save in SharedPreferences for UI (keep maintenance timestamps separate from quick SCREEN_ON events)
            boolean isMaintenanceRun = "SCREEN_OFF".equals(eventType)
                    || "MANUAL_RUN".equals(eventType)
                    || "LIVE_TRIM".equals(eventType)
                    || "BOOT_COMPLETED".equals(eventType);

            SharedPreferences prefs = appCtx.getSharedPreferences(PREFS_TELEMETRY, Context.MODE_PRIVATE);
            SharedPreferences.Editor ed = prefs.edit()
                    .putString("last_json", jsonStr)
                    .putString("last_timestamp", timestamp)
                    .putString("last_event", eventType)
                    .putString("last_status", statusMsg)
                    .putLong("last_mem_avail_mb", snap.memAvailableMb);
            if ("SCREEN_OFF".equals(eventType) || "MANUAL_RUN".equals(eventType)) {
                ed.putLong("last_run_epoch_ms", System.currentTimeMillis());
            } else if ("LIVE_TRIM".equals(eventType)) {
                ed.putLong("last_live_trim_epoch_ms", System.currentTimeMillis());
            }
            ed.apply();

            // 2. Write atomic status JSON to external app files dir so ADB/NAS can inspect parallel runs
            File extDir = appCtx.getExternalFilesDir(null);
            if (extDir != null) {
                File tmpFile = new File(extDir, "ondevice_status.json.tmp");
                File outFile = new File(extDir, "ondevice_status.json");
                try (FileOutputStream fos = new FileOutputStream(tmpFile, false)) {
                    fos.write(jsonStr.getBytes(StandardCharsets.UTF_8));
                }
                //noinspection ResultOfMethodCallIgnored
                tmpFile.renameTo(outFile);
            }

            // 3. Push directly to Home Assistant REST API (Phase 1 parallel validation entities)
            String haUrl = config.getHaUrl();
            String haToken = config.getHaToken();
            if (haUrl != null && haUrl.startsWith("http") && haToken != null && !haToken.isEmpty()) {
                String base = haUrl.replaceAll("/+$", "");
                publishPlayStateToHa(base, haToken, screenIsOn, config.getMode(), timestamp, eventType);
                if (isMaintenanceRun) {
                    publishMaintenanceStateToHa(base, haToken, config, timestamp, eventType,
                            statusMsg, snap, appsStopped, appsCompiled, freshAnrs, adbEnabled);
                }
            }

            // 4. Optional Home Assistant webhook
            String webhookUrl = config.getHaWebhookUrl();
            if (webhookUrl != null && webhookUrl.startsWith("http")) {
                postJson(webhookUrl, null, jsonStr);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to publish status telemetry: " + e.getMessage());
        }
    }

    private static void publishPlayStateToHa(
            String baseUrl,
            String token,
            boolean screenIsOn,
            String mode,
            String timestamp,
            String eventType
    ) {
        try {
            JSONObject body = new JSONObject();
            body.put("state", screenIsOn ? "on" : "off");
            JSONObject attrs = new JSONObject();
            attrs.put("friendly_name", "TV Spelar (On-Device App)");
            attrs.put("device_class", "power");
            attrs.put("icon", screenIsOn ? "mdi:television-play" : "mdi:television-off");
            attrs.put("mode", mode);
            attrs.put("last_event", eventType);
            attrs.put("updated_at", timestamp);
            body.put("attributes", attrs);
            postJson(baseUrl + "/api/states/binary_sensor.tv_spelar_app", token, body.toString());
        } catch (Exception e) {
            Log.w(TAG, "Failed to publish binary_sensor.tv_spelar_app: " + e.getMessage());
        }
    }

    private static void publishMaintenanceStateToHa(
            String baseUrl,
            String token,
            DaemonConfig config,
            String timestamp,
            String eventType,
            String statusMsg,
            MetricsCollector.SystemSnapshot snap,
            int appsStopped,
            int appsCompiled,
            int freshAnrs,
            int adbEnabled
    ) {
        try {
            String haState = statusMsg != null && statusMsg.startsWith("Error")
                    ? "Fel"
                    : (freshAnrs > 0 ? "Varning" : "Lyckades");
            JSONObject body = new JSONObject();
            body.put("state", haState);
            JSONObject attrs = new JSONObject();
            attrs.put("friendly_name", "Philips TV Optimizer (On-Device)");
            attrs.put("icon", "mdi:television-shimmer");
            attrs.put("mode", config.getMode());
            attrs.put("last_run", timestamp);
            attrs.put("event", eventType);
            attrs.put("details", statusMsg);
            attrs.put("mem_available_mb", snap.memAvailableMb);
            attrs.put("data_free_gb", snap.dataFreeGb);
            attrs.put("apps_stopped", appsStopped);
            attrs.put("apps_compiled", appsCompiled);
            attrs.put("fresh_anrs", freshAnrs);
            attrs.put("adb_enabled_after", adbEnabled);
            body.put("attributes", attrs);
            postJson(baseUrl + "/api/states/sensor.tv_maintenance_app", token, body.toString());
        } catch (Exception e) {
            Log.w(TAG, "Failed to publish sensor.tv_maintenance_app: " + e.getMessage());
        }
    }

    private static void postJson(String targetUrl, String bearerToken, String jsonBody) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(targetUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (bearerToken != null && !bearerToken.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
            }
            byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bytes);
            }
            int code = conn.getResponseCode();
            if (code >= 400) {
                Log.w(TAG, "HA telemetry HTTP " + code + " for " + targetUrl);
            }
        } catch (Exception e) {
            Log.w(TAG, "Home Assistant telemetry failed (" + targetUrl + "): " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    public static String getLastTelemetrySummary(Context context) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_TELEMETRY, Context.MODE_PRIVATE);
        String ts = prefs.getString("last_timestamp", "Never");
        String ev = prefs.getString("last_event", "-");
        String st = prefs.getString("last_status", "Ready");
        return "Last Run: " + ts + " [" + ev + "]\nResult: " + st;
    }

    public static long getLastRunEpochMs(Context context) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_TELEMETRY, Context.MODE_PRIVATE);
        return prefs.getLong("last_run_epoch_ms", 0L);
    }

    public static long getLastLiveTrimEpochMs(Context context) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_TELEMETRY, Context.MODE_PRIVATE);
        return prefs.getLong("last_live_trim_epoch_ms", 0L);
    }
}
