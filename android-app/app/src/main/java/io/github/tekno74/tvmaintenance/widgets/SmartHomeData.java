package io.github.tekno74.tvmaintenance.widgets;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.TrafficStats;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import io.github.tekno74.tvmaintenance.config.DaemonConfig;

/**
 * Module 7a (widgets): Fetches and caches live smart-home states from Home Assistant REST API
 * plus real-time TV network throughput via Android TrafficStats.
 */
public final class SmartHomeData {
    private static final String TAG = "TvMaintWidgets";
    private static final String PREFS_CACHE = "tv_maintenance_widget_cache";

    private static long lastRxBytes = -1;
    private static long lastTxBytes = -1;
    private static long lastTrafficTimeMs = -1;

    public static final class Snapshot {
        // 1. Smilla
        public String smillaStatus = "Hemma (Inomhus)";
        public String smillaHoursOut = "2.4 h";
        public String smillaPassages = "6 st";
        public String flapBattery = "37 %";

        // 2. Electricity (Tibber Liljegränd 12)
        public int priceOre = 150;
        public int priceMinOre = 94;
        public int priceAvgOre = 145;
        public int priceMaxOre = 216;
        public int currentPowerW = 1500;
        public String dailyCostSek = "64.9 kr";

        // 3. Temperatures (IVT + Verisure)
        public String indoorTempC = "22.2";
        public String outdoorTempC = "0.8";
        public String upperHallTempC = "21.7";

        // 4. Water Heater (IVT Varmvatten)
        public String waterTempC = "47.4";
        public String waterTargetC = "52.5";
        public String waterVolume40cL = "199";
        public String waterStatus = "Aktiv (52°C)";

        // 5. Internet & Network (TV NIC + UniFi UCG Max + NAS)
        public double tvDownMbps = 0.0;
        public double tvUpMbps = 0.0;
        public String ucgState = "Online";
        public String ucgCpuPct = "30 %";
        public String nasDownKbps = "180 kB/s";
        public int wifiClients = 13;

        // 6. Knitting Light Mode (Lisa)
        public boolean knittingLightOn = false;

        public long updatedEpochMs = 0L;

        public boolean isSmillaOut() {
            String s = smillaStatus.toLowerCase(Locale.ROOT);
            return s.contains("ute") || s.contains("out");
        }
    }

    public static synchronized Snapshot refresh(Context context, DaemonConfig config) {
        Snapshot snap = loadCached(context);

        // 1. Measure real-time TV NIC throughput via TrafficStats
        long nowMs = SystemClock.elapsedRealtime();
        long rx = TrafficStats.getTotalRxBytes();
        long tx = TrafficStats.getTotalTxBytes();
        if (lastTrafficTimeMs > 0 && nowMs > lastTrafficTimeMs && rx >= lastRxBytes && tx >= lastTxBytes) {
            double dtSec = (nowMs - lastTrafficTimeMs) / 1000.0;
            if (dtSec >= 0.5) {
                snap.tvDownMbps = Math.round((((rx - lastRxBytes) * 8.0) / (dtSec * 1_000_000.0)) * 10.0) / 10.0;
                snap.tvUpMbps = Math.round((((tx - lastTxBytes) * 8.0) / (dtSec * 1_000_000.0)) * 10.0) / 10.0;
            }
        }
        lastRxBytes = rx;
        lastTxBytes = tx;
        lastTrafficTimeMs = nowMs;

        String haUrl = config.getHaUrl();
        String haToken = config.getHaToken();
        if (haUrl == null || haUrl.isEmpty() || haToken == null || haToken.isEmpty()) {
            saveCached(context, snap);
            return snap;
        }

        // Only poll HA REST API at most every 20 seconds
        long wallNow = System.currentTimeMillis();
        if ((wallNow - snap.updatedEpochMs) < 20_000L && snap.updatedEpochMs > 0L) {
            saveCached(context, snap);
            return snap;
        }

        try {
            // Smilla
            JSONObject smilla = fetchEntity(haUrl, haToken, "sensor.smilla_fuserad_status");
            if (smilla != null) snap.smillaStatus = optValidState(smilla, snap.smillaStatus);

            JSONObject smillaHrs = fetchEntity(haUrl, haToken, "sensor.smilla_timmar_ute_idag");
            if (smillaHrs != null) snap.smillaHoursOut = optValidState(smillaHrs, snap.smillaHoursOut) + " h";

            JSONObject smillaPass = fetchEntity(haUrl, haToken, "sensor.smilla_passager_idag");
            if (smillaPass != null) snap.smillaPassages = optValidState(smillaPass, snap.smillaPassages) + " st";

            JSONObject flapBat = fetchEntity(haUrl, haToken, "sensor.sin_kattlucka_battery_level");
            if (flapBat != null) snap.flapBattery = optValidState(flapBat, snap.flapBattery) + " %";

            // Electricity Price & Power
            JSONObject priceObj = fetchEntity(haUrl, haToken, "sensor.liljegrand_12_electricity_price");
            if (priceObj != null) {
                double sek = parseDoubleSafe(priceObj.optString("state", ""), -1);
                if (sek >= 0) snap.priceOre = (int) Math.round(sek * 100.0);
                JSONObject attrs = priceObj.optJSONObject("attributes");
                if (attrs != null) {
                    if (attrs.has("min_price")) snap.priceMinOre = (int) Math.round(attrs.optDouble("min_price", 0.94) * 100.0);
                    if (attrs.has("avg_price")) snap.priceAvgOre = (int) Math.round(attrs.optDouble("avg_price", 1.45) * 100.0);
                    if (attrs.has("max_price")) snap.priceMaxOre = (int) Math.round(attrs.optDouble("max_price", 2.16) * 100.0);
                }
            }

            JSONObject powerObj = fetchEntity(haUrl, haToken, "sensor.tibber_pulse_liljegrand_12_power");
            if (powerObj != null) {
                double w = parseDoubleSafe(powerObj.optString("state", ""), -1);
                if (w >= 0) snap.currentPowerW = (int) Math.round(w);
            }

            JSONObject costObj = fetchEntity(haUrl, haToken, "sensor.tibber_pulse_liljegrand_12_accumulated_cost");
            if (costObj != null) {
                double c = parseDoubleSafe(costObj.optString("state", ""), -1);
                if (c >= 0) snap.dailyCostSek = String.format(Locale.US, "%.1f kr", c);
            }

            // Temperatures
            JSONObject inTemp = fetchEntity(haUrl, haToken, "sensor.ivt_representativ_innetemperatur");
            if (inTemp != null) {
                double t = parseDoubleSafe(inTemp.optString("state", ""), -999);
                if (t > -100) snap.indoorTempC = String.format(Locale.US, "%.1f", t);
            }

            JSONObject outTemp = fetchEntity(haUrl, haToken, "sensor.ivt_verklig_utetemperatur");
            if (outTemp != null) {
                double t = parseDoubleSafe(outTemp.optString("state", ""), -999);
                if (t > -100) snap.outdoorTempC = String.format(Locale.US, "%.1f", t);
            }

            JSONObject hallTemp = fetchEntity(haUrl, haToken, "sensor.hall_ov_temperature");
            if (hallTemp != null) {
                double t = parseDoubleSafe(hallTemp.optString("state", ""), -999);
                if (t > -100) snap.upperHallTempC = String.format(Locale.US, "%.1f", t);
            }

            // Water Heater (IVT)
            JSONObject vvTemp = fetchEntity(haUrl, haToken, "sensor.ivt_varmvattentemperatur");
            if (vvTemp != null) {
                double t = parseDoubleSafe(vvTemp.optString("state", ""), -999);
                if (t > -100) snap.waterTempC = String.format(Locale.US, "%.1f", t);
            }

            JSONObject vvTarget = fetchEntity(haUrl, haToken, "sensor.ivt_target_temperature");
            if (vvTarget != null) {
                double t = parseDoubleSafe(vvTarget.optString("state", ""), -999);
                if (t > -100) snap.waterTargetC = String.format(Locale.US, "%.1f", t);
            }

            JSONObject vvVol = fetchEntity(haUrl, haToken, "sensor.ivt_spa_bad_volym_40c");
            if (vvVol != null) {
                snap.waterVolume40cL = optValidState(vvVol, snap.waterVolume40cL);
            }

            JSONObject vvStatus = fetchEntity(haUrl, haToken, "sensor.ivt_smart_control_status");
            if (vvStatus != null) {
                snap.waterStatus = optValidState(vvStatus, snap.waterStatus);
            }

            // Internet / UniFi UCG Max & NAS
            JSONObject ucgState = fetchEntity(haUrl, haToken, "sensor.cloud_gateway_max_state");
            if (ucgState != null) {
                String st = optValidState(ucgState, "connected");
                snap.ucgState = "connected".equalsIgnoreCase(st) ? "Online" : st;
            }

            JSONObject ucgCpu = fetchEntity(haUrl, haToken, "sensor.cloud_gateway_max_cpu_utilization");
            if (ucgCpu != null) {
                double cpu = parseDoubleSafe(ucgCpu.optString("state", ""), -1);
                if (cpu >= 0) snap.ucgCpuPct = String.format(Locale.US, "%.0f %%", cpu);
            }

            JSONObject nasDown = fetchEntity(haUrl, haToken, "sensor.diskstation2_download_throughput");
            if (nasDown != null) {
                double kb = parseDoubleSafe(nasDown.optString("state", ""), -1);
                if (kb >= 0) {
                    if (kb >= 1000) {
                        snap.nasDownKbps = String.format(Locale.US, "%.1f MB/s", kb / 1024.0);
                    } else {
                        snap.nasDownKbps = String.format(Locale.US, "%.0f kB/s", kb);
                    }
                }
            }

            JSONObject c5g = fetchEntity(haUrl, haToken, "sensor.skynet_clients");
            JSONObject c24g = fetchEntity(haUrl, haToken, "sensor.skynet_24g_clients");
            int clients = 0;
            if (c5g != null) clients += (int) Math.max(0, parseDoubleSafe(c5g.optString("state", "0"), 0));
            if (c24g != null) clients += (int) Math.max(0, parseDoubleSafe(c24g.optString("state", "0"), 0));
            if (clients > 0) snap.wifiClients = clients;

            // Knitting Light Mode (Lisa)
            String knitEntity = config.getKnittingEntityId();
            JSONObject knitObj = fetchEntity(haUrl, haToken, knitEntity);
            if (knitObj != null) {
                String kState = optValidState(knitObj, "off");
                snap.knittingLightOn = "on".equalsIgnoreCase(kState);
            }

            snap.updatedEpochMs = wallNow;
            saveCached(context, snap);
        } catch (Exception e) {
            Log.w(TAG, "Error refreshing Home Assistant state: " + e.getMessage());
        }
        return snap;
    }

    private static JSONObject fetchEntity(String baseUrl, String token, String entityId) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(baseUrl.replaceAll("/+$", "") + "/api/states/" + entityId);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Accept", "application/json");
            if (conn.getResponseCode() != 200) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
            }
            return new JSONObject(sb.toString());
        } catch (Exception ignored) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String optValidState(JSONObject obj, String fallback) {
        String s = obj.optString("state", "").trim();
        if (s.isEmpty() || "unavailable".equalsIgnoreCase(s) || "unknown".equalsIgnoreCase(s)) {
            return fallback;
        }
        return s;
    }

    private static double parseDoubleSafe(String val, double fallback) {
        try {
            return Double.parseDouble(val.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    public static Snapshot loadCached(Context context) {
        SharedPreferences p = context.getApplicationContext().getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE);
        Snapshot s = new Snapshot();
        s.smillaStatus = p.getString("smillaStatus", s.smillaStatus);
        s.smillaHoursOut = p.getString("smillaHoursOut", s.smillaHoursOut);
        s.smillaPassages = p.getString("smillaPassages", s.smillaPassages);
        s.flapBattery = p.getString("flapBattery", s.flapBattery);
        s.priceOre = p.getInt("priceOre", s.priceOre);
        s.priceMinOre = p.getInt("priceMinOre", s.priceMinOre);
        s.priceAvgOre = p.getInt("priceAvgOre", s.priceAvgOre);
        s.priceMaxOre = p.getInt("priceMaxOre", s.priceMaxOre);
        s.currentPowerW = p.getInt("currentPowerW", s.currentPowerW);
        s.dailyCostSek = p.getString("dailyCostSek", s.dailyCostSek);
        s.indoorTempC = p.getString("indoorTempC", s.indoorTempC);
        s.outdoorTempC = p.getString("outdoorTempC", s.outdoorTempC);
        s.upperHallTempC = p.getString("upperHallTempC", s.upperHallTempC);
        s.waterTempC = p.getString("waterTempC", s.waterTempC);
        s.waterTargetC = p.getString("waterTargetC", s.waterTargetC);
        s.waterVolume40cL = p.getString("waterVolume40cL", s.waterVolume40cL);
        s.waterStatus = p.getString("waterStatus", s.waterStatus);
        s.tvDownMbps = Double.longBitsToDouble(p.getLong("tvDownMbps", Double.doubleToLongBits(0.0)));
        s.tvUpMbps = Double.longBitsToDouble(p.getLong("tvUpMbps", Double.doubleToLongBits(0.0)));
        s.ucgState = p.getString("ucgState", s.ucgState);
        s.ucgCpuPct = p.getString("ucgCpuPct", s.ucgCpuPct);
        s.nasDownKbps = p.getString("nasDownKbps", s.nasDownKbps);
        s.wifiClients = p.getInt("wifiClients", s.wifiClients);
        s.knittingLightOn = p.getBoolean("knittingLightOn", s.knittingLightOn);
        s.updatedEpochMs = p.getLong("updatedEpochMs", 0L);
        return s;
    }

    private static void saveCached(Context context, Snapshot s) {
        SharedPreferences p = context.getApplicationContext().getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE);
        p.edit()
                .putString("smillaStatus", s.smillaStatus)
                .putString("smillaHoursOut", s.smillaHoursOut)
                .putString("smillaPassages", s.smillaPassages)
                .putString("flapBattery", s.flapBattery)
                .putInt("priceOre", s.priceOre)
                .putInt("priceMinOre", s.priceMinOre)
                .putInt("priceAvgOre", s.priceAvgOre)
                .putInt("priceMaxOre", s.priceMaxOre)
                .putInt("currentPowerW", s.currentPowerW)
                .putString("dailyCostSek", s.dailyCostSek)
                .putString("indoorTempC", s.indoorTempC)
                .putString("outdoorTempC", s.outdoorTempC)
                .putString("upperHallTempC", s.upperHallTempC)
                .putString("waterTempC", s.waterTempC)
                .putString("waterTargetC", s.waterTargetC)
                .putString("waterVolume40cL", s.waterVolume40cL)
                .putString("waterStatus", s.waterStatus)
                .putLong("tvDownMbps", Double.doubleToLongBits(s.tvDownMbps))
                .putLong("tvUpMbps", Double.doubleToLongBits(s.tvUpMbps))
                .putString("ucgState", s.ucgState)
                .putString("ucgCpuPct", s.ucgCpuPct)
                .putString("nasDownKbps", s.nasDownKbps)
                .putInt("wifiClients", s.wifiClients)
                .putBoolean("knittingLightOn", s.knittingLightOn)
                .putLong("updatedEpochMs", s.updatedEpochMs)
                .apply();
    }

    /**
     * Toggles the "Stickning" lighting mode in Home Assistant:
     *  1. Calls /api/services/homeassistant/turn_on or turn_off on config.getKnittingEntityId()
     *  2. Ensures input_boolean.stickbelysning state is updated via /api/states/
     *  3. Fires event "tv_stickbelysning_changed" with {"state": "on"|"off"} for easy HA automations.
     */
    public static synchronized boolean toggleKnittingLight(Context context, DaemonConfig config) {
        Snapshot snap = loadCached(context);
        boolean nextState = !snap.knittingLightOn;
        snap.knittingLightOn = nextState;
        saveCached(context, snap);

        String haUrl = config.getHaUrl();
        String haToken = config.getHaToken();
        String entityId = config.getKnittingEntityId();
        if (haUrl == null || haUrl.isEmpty() || haToken == null || haToken.isEmpty()) {
            return nextState;
        }

        String stateStr = nextState ? "on" : "off";
        String baseUrl = haUrl.replaceAll("/+$", "");

        try {
            // 1. Update/create state directly so input_boolean.stickbelysning exists immediately in HA
            JSONObject stateBody = new JSONObject();
            stateBody.put("state", stateStr);
            JSONObject attrs = new JSONObject();
            attrs.put("friendly_name", "Ljusläge: Stickning (Lisa)");
            attrs.put("icon", "mdi:yarn");
            attrs.put("triggered_by", "Philips Android TV Remote");
            stateBody.put("attributes", attrs);
            postHaJson(baseUrl + "/api/states/" + entityId, haToken, stateBody.toString());

            // 2. Also call homeassistant/turn_on or turn_off service (if backed by a helper/light/switch/script)
            String service = nextState ? "turn_on" : "turn_off";
            JSONObject svcBody = new JSONObject();
            svcBody.put("entity_id", entityId);
            postHaJson(baseUrl + "/api/services/homeassistant/" + service, haToken, svcBody.toString());

            // 3. Fire dedicated HA event "tv_stickbelysning_changed"
            JSONObject evBody = new JSONObject();
            evBody.put("entity_id", entityId);
            evBody.put("state", stateStr);
            postHaJson(baseUrl + "/api/events/tv_stickbelysning_changed", haToken, evBody.toString());

            Log.i(TAG, "Toggled Stickbelysning (" + entityId + ") -> " + stateStr.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            Log.w(TAG, "Failed to toggle Stickbelysning in HA: " + e.getMessage());
        }
        return nextState;
    }

    private static void postHaJson(String urlStr, String token, String jsonPayload) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] bytes = jsonPayload.getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bytes);
            }
            conn.getResponseCode();
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
