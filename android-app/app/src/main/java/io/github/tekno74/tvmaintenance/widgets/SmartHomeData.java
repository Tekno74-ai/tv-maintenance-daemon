package io.github.tekno74.tvmaintenance.widgets;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.TrafficStats;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import io.github.tekno74.tvmaintenance.config.DaemonConfig;

/**
 * Module 7a (widgets): Fetches and caches live smart-home states from Home Assistant REST API
 * (including live Sure Petcare history for Smilla's exits/entries) and real-time WAN throughput
 * & Wi-Fi client count directly from the UniFi Cloud Gateway Max (https://192.168.0.1).
 */
public final class SmartHomeData {
    private static final String TAG = "TvMaintWidgets";
    private static final String PREFS_CACHE = "tv_maintenance_widget_cache";

    private static long lastRxBytes = -1;
    private static long lastTxBytes = -1;
    private static long lastTrafficTimeMs = -1;
    private static long lastUnifiPollMs = 0L;
    private static String unifiCookie = null;

    public static final class Snapshot {
        // 1. Smilla
        public String smillaStatus = "Hemma (Inomhus)";
        public String smillaHoursOut = "1.5 h";
        public String smillaPassages = "3 ut / 3 in";
        public int smillaOutCount = 3;
        public int smillaInCount = 3;
        public String flapBattery = "36 %";

        // 2. Electricity (Tibber Liljegränd 12)
        public int priceOre = 60;
        public int priceMinOre = 30;
        public int priceAvgOre = 150;
        public int priceMaxOre = 190;
        public int currentPowerW = 1500;
        public String dailyCostSek = "100.4 kr";

        // 3. Temperatures (IVT + Verisure)
        public String indoorTempC = "21.7";
        public String outdoorTempC = "3.6";
        public String upperHallTempC = "21.4";

        // 4. Water Heater (IVT Varmvatten)
        public String waterTempC = "56.4";
        public String waterTargetC = "48.0";
        public String waterVolume40cL = "244";
        public String waterStatus = "Aktiv (48°C)";

        // 5. Internet & UniFi (WAN Throughput + Wi-Fi Clients + UCG Max)
        public double tvDownMbps = 3.2;
        public double tvUpMbps = 1.4;
        public int wanLatencyMs = 1;
        public String ucgState = "Online";
        public String ucgCpuPct = "12 %";
        public int wifiClients = 21;

        // 6. Knitting Light Mode (Lisa)
        public boolean knittingLightOn = false;

        public long updatedEpochMs = 0L;

        public boolean isSmillaOut() {
            String s = smillaStatus.toLowerCase(Locale.ROOT);
            return s.contains("ute") || s.contains("out") || "off".equals(s);
        }
    }

    public static synchronized Snapshot refresh(Context context, DaemonConfig config) {
        Snapshot snap = loadCached(context);

        // 1. Measure local TV NIC throughput (via /proc/net/dev eth0 + TrafficStats fallback)
        double localRxMbps = 0.0;
        double localTxMbps = 0.0;
        long nowMs = SystemClock.elapsedRealtime();
        long[] nicBytes = readEth0Bytes();
        long rx = nicBytes[0] > 0 ? nicBytes[0] : TrafficStats.getTotalRxBytes();
        long tx = nicBytes[1] > 0 ? nicBytes[1] : TrafficStats.getTotalTxBytes();
        if (lastTrafficTimeMs > 0 && nowMs > lastTrafficTimeMs && rx >= lastRxBytes && tx >= lastTxBytes && lastRxBytes > 0) {
            double dtSec = (nowMs - lastTrafficTimeMs) / 1000.0;
            if (dtSec >= 0.5) {
                localRxMbps = Math.round((((rx - lastRxBytes) * 8.0) / (dtSec * 1_000_000.0)) * 10.0) / 10.0;
                localTxMbps = Math.round((((tx - lastTxBytes) * 8.0) / (dtSec * 1_000_000.0)) * 10.0) / 10.0;
            }
        }
        lastRxBytes = rx;
        lastTxBytes = tx;
        lastTrafficTimeMs = nowMs;

        // 2. Poll UniFi Cloud Gateway Max (/stat/health) every 6 seconds for live WAN throughput & Wi-Fi clients
        long wallNow = System.currentTimeMillis();
        if ((wallNow - lastUnifiPollMs) >= 6_000L) {
            lastUnifiPollMs = wallNow;
            boolean unifiOk = pollUnifiHealth(config, snap);
            if (unifiOk) {
                if (localRxMbps > snap.tvDownMbps) snap.tvDownMbps = localRxMbps;
                if (localTxMbps > snap.tvUpMbps) snap.tvUpMbps = localTxMbps;
            } else {
                snap.tvDownMbps = localRxMbps;
                snap.tvUpMbps = localTxMbps;
            }
        }

        String haUrl = config.getHaUrl();
        String haToken = config.getHaToken();
        if (haUrl == null || haUrl.isEmpty() || haToken == null || haToken.isEmpty()) {
            saveCached(context, snap);
            return snap;
        }

        // 3. Poll Home Assistant REST API at most every 15 seconds
        if ((wallNow - snap.updatedEpochMs) < 15_000L && snap.updatedEpochMs > 0L) {
            saveCached(context, snap);
            return snap;
        }

        try {
            // Smilla live status & history (from binary_sensor.smilla + manual override)
            refreshSmillaFromHa(haUrl, haToken, snap);

            JSONObject flapBat = fetchEntity(haUrl, haToken, "sensor.sin_kattlucka_battery_level");
            if (flapBat != null) snap.flapBattery = optValidState(flapBat, "36") + " %";

            // Electricity Price & Power
            JSONObject priceObj = fetchEntity(haUrl, haToken, "sensor.liljegrand_12_electricity_price");
            if (priceObj != null) {
                double sek = parseDoubleSafe(priceObj.optString("state", ""), -1);
                if (sek >= 0) snap.priceOre = (int) Math.round(sek * 100.0);
                JSONObject attrs = priceObj.optJSONObject("attributes");
                if (attrs != null) {
                    if (attrs.has("min_price")) snap.priceMinOre = (int) Math.round(attrs.optDouble("min_price", 0.30) * 100.0);
                    if (attrs.has("avg_price")) snap.priceAvgOre = (int) Math.round(attrs.optDouble("avg_price", 1.50) * 100.0);
                    if (attrs.has("max_price")) snap.priceMaxOre = (int) Math.round(attrs.optDouble("max_price", 1.90) * 100.0);
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

    private static void refreshSmillaFromHa(String haUrl, String haToken, Snapshot snap) {
        // 1. Check live Sure Petcare binary_sensor.smilla ("on" = inside, "off" = outside)
        boolean isInside = true;
        JSONObject binSmilla = fetchEntity(haUrl, haToken, "binary_sensor.smilla");
        if (binSmilla != null) {
            String st = optValidState(binSmilla, "on");
            isInside = "on".equalsIgnoreCase(st);
        }

        // Check manual override selector if used
        JSONObject overrideSel = fetchEntity(haUrl, haToken, "input_select.smilla_korrigerad_plats");
        if (overrideSel != null) {
            String sel = optValidState(overrideSel, "");
            if (sel.toLowerCase(Locale.ROOT).contains("tvinga inne")) {
                isInside = true;
            } else if (sel.toLowerCase(Locale.ROOT).contains("tvinga ute")) {
                isInside = false;
            }
        }
        snap.smillaStatus = isInside ? "Hemma (Inomhus)" : "Ute";

        // 2. Compute today's exact exits (ut), entries (in), and hours outdoors from /api/history/period
        try {
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            long midnightMs = cal.getTimeInMillis();

            SimpleDateFormat sdfLocal = new SimpleDateFormat("yyyy-MM-dd'T'00:00:00", Locale.US);
            String startIso = sdfLocal.format(new Date(midnightMs));
            String histUrl = haUrl.replaceAll("/+$", "")
                    + "/api/history/period/" + startIso
                    + "?filter_entity_id=binary_sensor.smilla&minimal_response";

            String rawHist = httpGet(histUrl, haToken);
            if (rawHist != null && rawHist.startsWith("[")) {
                JSONArray outer = new JSONArray(rawHist);
                if (outer.length() > 0) {
                    JSONArray events = outer.getJSONArray(0);
                    int outCount = 0;
                    int inCount = 0;
                    long totalOutMs = 0L;
                    long outStartMs = -1L;
                    String prevState = null;

                    for (int i = 0; i < events.length(); i++) {
                        JSONObject ev = events.getJSONObject(i);
                        String st = ev.optString("state", "");
                        if (!"on".equals(st) && !"off".equals(st)) continue;

                        long tsMs = parseIsoTimestampMs(ev.optString("last_changed", ""), midnightMs);
                        long clampedTs = Math.max(midnightMs, tsMs);

                        if (prevState == null) {
                            // Initial state at midnight (or first record)
                            if (tsMs > midnightMs) {
                                // Transition occurred after midnight
                                if ("off".equals(st)) {
                                    outCount++;
                                    outStartMs = clampedTs;
                                } else {
                                    inCount++;
                                }
                            } else if ("off".equals(st)) {
                                // Was already outside at midnight
                                outStartMs = midnightMs;
                            }
                        } else if (!st.equals(prevState)) {
                            if ("off".equals(st)) {
                                outCount++;
                                outStartMs = clampedTs;
                            } else {
                                inCount++;
                                if (outStartMs > 0 && clampedTs > outStartMs) {
                                    totalOutMs += (clampedTs - outStartMs);
                                }
                                outStartMs = -1L;
                            }
                        }
                        prevState = st;
                    }

                    long nowMs = System.currentTimeMillis();
                    if (outStartMs > 0 && nowMs > outStartMs) {
                        totalOutMs += (nowMs - outStartMs);
                    }

                    double hoursOut = Math.round((totalOutMs / 3_600_000.0) * 10.0) / 10.0;
                    snap.smillaHoursOut = String.format(Locale.US, "%.1f h", hoursOut);
                    snap.smillaOutCount = outCount;
                    snap.smillaInCount = inCount;
                    snap.smillaPassages = outCount + " ut / " + inCount + " in";
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not parse Smilla history: " + e.getMessage());
        }
    }

    private static long parseIsoTimestampMs(String iso, long fallbackMs) {
        if (iso == null || iso.isEmpty()) return fallbackMs;
        try {
            // Normalize fractional seconds and +00:00
            String clean = iso.trim();
            if (clean.endsWith("+00:00")) {
                clean = clean.substring(0, clean.length() - 6) + "Z";
            }
            int dot = clean.indexOf('.');
            int z = clean.indexOf('Z');
            if (dot > 0 && z > dot) {
                String frac = clean.substring(dot + 1, z);
                if (frac.length() > 3) frac = frac.substring(0, 3);
                while (frac.length() < 3) frac += "0";
                clean = clean.substring(0, dot + 1) + frac + "Z";
                SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
                df.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date d = df.parse(clean);
                if (d != null) return d.getTime();
            } else if (z > 0) {
                SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
                df.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date d = df.parse(clean);
                if (d != null) return d.getTime();
            }
        } catch (Exception ignored) {
        }
        return fallbackMs;
    }

    private static boolean pollUnifiHealth(DaemonConfig config, Snapshot snap) {
        String host = config.getUnifiHost();
        String user = config.getUnifiUser();
        String pass = config.getUnifiPass();
        if (host == null || host.isEmpty() || user == null || user.isEmpty() || pass == null || pass.isEmpty()) {
            return false;
        }
        try {
            if (unifiCookie == null) {
                unifiCookie = loginUnifi(host, user, pass);
            }
            if (unifiCookie == null) return false;

            String healthJson = fetchUnifiHealthJson(host, unifiCookie);
            if (healthJson == null) {
                // Cookie expired; re-login once
                unifiCookie = loginUnifi(host, user, pass);
                if (unifiCookie == null) return false;
                healthJson = fetchUnifiHealthJson(host, unifiCookie);
            }
            if (healthJson == null) return false;

            JSONObject root = new JSONObject(healthJson);
            JSONArray data = root.optJSONArray("data");
            if (data == null) return false;

            for (int i = 0; i < data.length(); i++) {
                JSONObject sub = data.getJSONObject(i);
                String sys = sub.optString("subsystem", "");
                if ("wlan".equals(sys)) {
                    int users = sub.optInt("num_user", 0) + sub.optInt("num_guest", 0);
                    if (users > 0) snap.wifiClients = users;
                } else if ("wan".equals(sys)) {
                    String st = sub.optString("status", "ok");
                    snap.ucgState = "ok".equalsIgnoreCase(st) ? "Online" : st;
                    double rxBps = sub.optDouble("rx_bytes-r", 0.0);
                    double txBps = sub.optDouble("tx_bytes-r", 0.0);
                    snap.tvDownMbps = Math.round(((rxBps * 8.0) / 1_000_000.0) * 10.0) / 10.0;
                    snap.tvUpMbps = Math.round(((txBps * 8.0) / 1_000_000.0) * 10.0) / 10.0;
                    JSONObject gwStats = sub.optJSONObject("gw_system-stats");
                    if (gwStats != null) {
                        double cpu = parseDoubleSafe(gwStats.optString("cpu", ""), -1);
                        if (cpu >= 0) snap.ucgCpuPct = String.format(Locale.US, "%.0f %%", cpu);
                    }
                } else if ("www".equals(sys)) {
                    int lat = sub.optInt("latency", -1);
                    if (lat >= 0) snap.wanLatencyMs = lat;
                }
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "UniFi health poll error: " + e.getMessage());
            return false;
        }
    }

    private static SSLContext createTrustAllSslContext() throws Exception {
        TrustManager[] trustAll = new TrustManager[]{
                new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                    @Override
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }
        };
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(null, trustAll, new SecureRandom());
        return sc;
    }

    private static String loginUnifi(String host, String user, String pass) {
        HttpsURLConnection conn = null;
        try {
            URL url = new URL("https://" + host + "/api/auth/login");
            conn = (HttpsURLConnection) url.openConnection();
            conn.setSSLSocketFactory(createTrustAllSslContext().getSocketFactory());
            conn.setHostnameVerifier((h, s) -> true);
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");

            JSONObject body = new JSONObject();
            body.put("username", user);
            body.put("password", pass);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bytes);
            }
            if (conn.getResponseCode() != 200) return null;

            Map<String, List<String>> headers = conn.getHeaderFields();
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey() != null && "set-cookie".equalsIgnoreCase(entry.getKey())) {
                    StringBuilder cookieSb = new StringBuilder();
                    for (String c : entry.getValue()) {
                        String tokenPart = c.split(";")[0].trim();
                        if (!tokenPart.isEmpty()) {
                            if (cookieSb.length() > 0) cookieSb.append("; ");
                            cookieSb.append(tokenPart);
                        }
                    }
                    if (cookieSb.length() > 0) return cookieSb.toString();
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    private static String fetchUnifiHealthJson(String host, String cookie) {
        HttpsURLConnection conn = null;
        try {
            URL url = new URL("https://" + host + "/proxy/network/api/s/default/stat/health");
            conn = (HttpsURLConnection) url.openConnection();
            conn.setSSLSocketFactory(createTrustAllSslContext().getSocketFactory());
            conn.setHostnameVerifier((h, s) -> true);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(2500);
            conn.setRequestProperty("Cookie", cookie);
            conn.setRequestProperty("Accept", "application/json");
            if (conn.getResponseCode() != 200) return null;

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
            }
            return sb.toString();
        } catch (Exception ignored) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static long[] readEth0Bytes() {
        long rx = 0L;
        long tx = 0L;
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/net/dev"))) {
            String line;
            while ((line = br.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("eth0:") || trimmed.startsWith("wlan0:")) {
                    String[] parts = trimmed.substring(trimmed.indexOf(':') + 1).trim().split("\\s+");
                    if (parts.length >= 9) {
                        rx += Long.parseLong(parts[0]);
                        tx += Long.parseLong(parts[8]);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return new long[]{rx, tx};
    }

    private static String httpGet(String urlStr, String token) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Accept", "application/json");
            if (conn.getResponseCode() != 200) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
            }
            return sb.toString();
        } catch (Exception ignored) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static JSONObject fetchEntity(String baseUrl, String token, String entityId) {
        String raw = httpGet(baseUrl.replaceAll("/+$", "") + "/api/states/" + entityId, token);
        if (raw == null) return null;
        try {
            return new JSONObject(raw);
        } catch (Exception e) {
            return null;
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
        s.smillaOutCount = p.getInt("smillaOutCount", s.smillaOutCount);
        s.smillaInCount = p.getInt("smillaInCount", s.smillaInCount);
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
        s.tvDownMbps = Double.longBitsToDouble(p.getLong("tvDownMbps", Double.doubleToLongBits(s.tvDownMbps)));
        s.tvUpMbps = Double.longBitsToDouble(p.getLong("tvUpMbps", Double.doubleToLongBits(s.tvUpMbps)));
        s.wanLatencyMs = p.getInt("wanLatencyMs", s.wanLatencyMs);
        s.ucgState = p.getString("ucgState", s.ucgState);
        s.ucgCpuPct = p.getString("ucgCpuPct", s.ucgCpuPct);
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
                .putInt("smillaOutCount", s.smillaOutCount)
                .putInt("smillaInCount", s.smillaInCount)
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
                .putInt("wanLatencyMs", s.wanLatencyMs)
                .putString("ucgState", s.ucgState)
                .putString("ucgCpuPct", s.ucgCpuPct)
                .putInt("wifiClients", s.wifiClients)
                .putBoolean("knittingLightOn", s.knittingLightOn)
                .putLong("updatedEpochMs", s.updatedEpochMs)
                .apply();
    }

    /**
     * Toggles the "Stickning" lighting mode in Home Assistant.
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
            JSONObject stateBody = new JSONObject();
            stateBody.put("state", stateStr);
            JSONObject attrs = new JSONObject();
            attrs.put("friendly_name", "Ljusläge: Stickning (Lisa)");
            attrs.put("icon", "mdi:yarn");
            attrs.put("triggered_by", "Philips Android TV Remote");
            stateBody.put("attributes", attrs);
            postHaJson(baseUrl + "/api/states/" + entityId, haToken, stateBody.toString());

            String service = nextState ? "turn_on" : "turn_off";
            JSONObject svcBody = new JSONObject();
            svcBody.put("entity_id", entityId);
            postHaJson(baseUrl + "/api/services/homeassistant/" + service, haToken, svcBody.toString());

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
