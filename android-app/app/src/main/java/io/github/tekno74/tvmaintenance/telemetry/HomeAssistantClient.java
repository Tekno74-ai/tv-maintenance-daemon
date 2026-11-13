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
 * and optionally posts them to a Home Assistant webhook.
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
        Context appCtx = context.getApplicationContext();
        MetricsCollector.SystemSnapshot snap = MetricsCollector.collect(appCtx);
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());

        int adbEnabled = -1;
        try {
            adbEnabled = Settings.Global.getInt(appCtx.getContentResolver(), Settings.Global.ADB_ENABLED, -1);
        } catch (Exception ignored) {
        }

        try {
            JSONObject payload = new JSONObject();
            payload.put("timestamp", timestamp);
            payload.put("mode", config.getMode());
            payload.put("event", eventType);
            payload.put("status", statusMsg);
            payload.put("screen_state", snap.interactive ? "ON" : "OFF");
            payload.put("mem_available_mb", snap.memAvailableMb);
            payload.put("mem_total_mb", snap.memTotalMb);
            payload.put("mem_before_mb", memBeforeMb);
            payload.put("mem_after_mb", memAfterMb);
            payload.put("mem_freed_mb", Math.max(0L, memAfterMb - memBeforeMb));
            payload.put("data_free_gb", snap.dataFreeGb);
            payload.put("data_total_gb", snap.dataTotalGb);
            payload.put("apps_stopped", appsStopped);
            payload.put("apps_compiled", appsCompiled);
            payload.put("adb_enabled_after", adbEnabled);

            String jsonStr = payload.toString(2);

            // 1. Save in SharedPreferences for UI
            SharedPreferences prefs = appCtx.getSharedPreferences(PREFS_TELEMETRY, Context.MODE_PRIVATE);
            prefs.edit()
                    .putString("last_json", jsonStr)
                    .putString("last_timestamp", timestamp)
                    .putString("last_event", eventType)
                    .putString("last_status", statusMsg)
                    .putLong("last_mem_avail_mb", snap.memAvailableMb)
                    .putLong("last_run_epoch_ms", System.currentTimeMillis())
                    .apply();

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

            // 3. Push to Home Assistant Webhook if configured
            String webhookUrl = config.getHaWebhookUrl();
            if (webhookUrl != null && webhookUrl.startsWith("http")) {
                postWebhook(webhookUrl, jsonStr);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to publish status telemetry: " + e.getMessage());
        }
    }

    private static void postWebhook(String webhookUrl, String jsonBody) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(webhookUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bytes);
            }
            int code = conn.getResponseCode();
            Log.i(TAG, "Home Assistant webhook response: HTTP " + code);
        } catch (Exception e) {
            Log.w(TAG, "Home Assistant webhook failed: " + e.getMessage());
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
}
