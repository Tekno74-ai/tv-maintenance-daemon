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
        public String smillaLogSummary = "Inga passager registrerade ännu idag";
        public String smillaLockState = "Olåst (Fri passage)";
        public String smillaHubState = "Online";
        public String smillaOverrideMode = "Automatisk (Kattlucka + Sensorer)";

        // 2. Electricity (Tibber Liljegränd 12)
        public int priceOre = 60;
        public int priceMinOre = 30;
        public int priceAvgOre = 150;
        public int priceMaxOre = 190;
        public int currentPowerW = 1500;
        public String dailyCostSek = "100.4 kr";
        public double powerAvgKw = 6.4;
        public double powerMaxKw = 12.8;
        public String phaseCurrents = "L1: 0.8 A (230V) • L2: 2.0 A (231V) • L3: 2.4 A (232V)";
        public String dailyEnergyKwh = "73.2 kWh";
        public String monthlyEnergyKwh = "722.6 kWh";
        public String monthlyCostSek = "1530.5 kr";
        public String ivtSavingsToday = "4.00 kr (0.64 kWh / 14.4 %)";

        // 3. Temperatures (IVT + Verisure)
        public String indoorTempC = "21.7";
        public String outdoorTempC = "3.6";
        public String upperHallTempC = "21.4";
        public String indoorTargetC = "21.5";
        public String entranceHallTempC = "22.4";
        public String downstairsTempC = "21.3";
        public String supplyTempC = "28.9";
        public String supplyTargetC = "33.0";
        public String returnTempC = "27.5";
        public String degreeMinutes = "-600";
        public String compressorState = "På";

        // 4. Water Heater (IVT Varmvatten)
        public String waterTempC = "56.4";
        public String waterTargetC = "48.0";
        public String waterVolume40cL = "244";
        public String waterStatus = "Aktiv (48°C)";
        public String waterSpaStatus = "Normal (Dusch/Småbad)";
        public String valveMode = "Radiatorer";
        public String compressorHours = "32 812 h (10 302 starter)";
        public String ivtAiBriefing = "Balanserad drift med selektiva spärrar vid pristoppar.";

        // 5. Internet & UniFi (WAN Throughput + Wi-Fi Clients + UCG Max)
        public double tvDownMbps = 3.2;
        public double tvUpMbps = 1.4;
        public double tvLocalDownMbps = 0.0;
        public double tvLocalUpMbps = 0.0;
        public int wanLatencyMs = 1;
        public String ucgState = "Online";
        public String ucgCpuPct = "12 %";
        public String ucgMemPct = "45 %";
        public String ucgUptime = "—";
        public String wanIp = "—";
        public String ispName = "Fiber WAN";
        public int wifiClients = 21;
        public int lanClients = 12;

        // 6. Knitting Light Mode (Lisa)
        public boolean knittingLightOn = false;

        // 7. Cleaning Light Mode
        public boolean cleaningLightOn = false;

        public long updatedEpochMs = 0L;

        public boolean isSmillaOut() {
            String s = smillaStatus.toLowerCase(Locale.ROOT);
            return s.contains("ute") || s.contains("out") || "off".equals(s);
        }
    }

    public static synchronized Snapshot refresh(Context context, DaemonConfig config) {
        Snapshot snap = loadCached(context);

        // 1. Measure local TV NIC throughput via TrafficStats (avoids SELinux denial on /proc/net/dev)
        double localRxMbps = 0.0;
        double localTxMbps = 0.0;
        long nowMs = SystemClock.elapsedRealtime();
        long rx = Math.max(0L, TrafficStats.getTotalRxBytes());
        long tx = Math.max(0L, TrafficStats.getTotalTxBytes());
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
        snap.tvLocalDownMbps = localRxMbps;
        snap.tvLocalUpMbps = localTxMbps;
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

            JSONObject flapLock = fetchEntity(haUrl, haToken, "lock.sin_kattlucka_locked_all");
            if (flapLock != null) {
                String ls = optValidState(flapLock, "unlocked");
                snap.smillaLockState = "unlocked".equalsIgnoreCase(ls) ? "Olåst (Fri passage)" : "Låst (" + ls + ")";
            }

            JSONObject flapConn = fetchEntity(haUrl, haToken, "binary_sensor.sin_kattlucka_connectivity");
            if (flapConn != null) {
                String cs = optValidState(flapConn, "on");
                snap.smillaHubState = "on".equalsIgnoreCase(cs) ? "Online" : "Offline";
            }

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

            JSONObject avgPowerObj = fetchEntity(haUrl, haToken, "sensor.tibber_pulse_liljegrand_12_average_power");
            if (avgPowerObj != null) {
                double w = parseDoubleSafe(avgPowerObj.optString("state", ""), -1);
                if (w >= 0) snap.powerAvgKw = Math.round((w / 1000.0) * 100.0) / 100.0;
            }

            JSONObject maxPowerObj = fetchEntity(haUrl, haToken, "sensor.tibber_pulse_liljegrand_12_max_power");
            if (maxPowerObj != null) {
                double w = parseDoubleSafe(maxPowerObj.optString("state", ""), -1);
                if (w >= 0) snap.powerMaxKw = Math.round((w / 1000.0) * 100.0) / 100.0;
            }

            JSONObject l1Obj = fetchEntity(haUrl, haToken, "sensor.tibber_pulse_liljegrand_12_current_l1");
            JSONObject l2Obj = fetchEntity(haUrl, haToken, "sensor.tibber_pulse_liljegrand_12_current_l2");
            JSONObject l3Obj = fetchEntity(haUrl, haToken, "sensor.tibber_pulse_liljegrand_12_current_l3");
            JSONObject v1Obj = fetchEntity(haUrl, haToken, "sensor.tibber_pulse_liljegrand_12_voltage_phase1");
            JSONObject v2Obj = fetchEntity(haUrl, haToken, "sensor.tibber_pulse_liljegrand_12_voltage_phase2");
            JSONObject v3Obj = fetchEntity(haUrl, haToken, "sensor.tibber_pulse_liljegrand_12_voltage_phase3");
            if (l1Obj != null && l2Obj != null && l3Obj != null) {
                String i1 = optValidState(l1Obj, "0.0");
                String i2 = optValidState(l2Obj, "0.0");
                String i3 = optValidState(l3Obj, "0.0");
                String u1 = v1Obj != null ? optValidState(v1Obj, "230") : "230";
                String u2 = v2Obj != null ? optValidState(v2Obj, "230") : "230";
                String u3 = v3Obj != null ? optValidState(v3Obj, "230") : "230";
                snap.phaseCurrents = "L1: " + i1 + " A (" + u1 + " V)  •  L2: " + i2 + " A (" + u2 + " V)  •  L3: " + i3 + " A (" + u3 + " V)";
            }

            JSONObject costObj = fetchEntity(haUrl, haToken, "sensor.tibber_pulse_liljegrand_12_accumulated_cost");
            if (costObj != null) {
                double c = parseDoubleSafe(costObj.optString("state", ""), -1);
                if (c >= 0) snap.dailyCostSek = String.format(Locale.US, "%.1f kr", c);
            }

            JSONObject consObj = fetchEntity(haUrl, haToken, "sensor.tibber_pulse_liljegrand_12_accumulated_consumption");
            if (consObj != null) {
                double k = parseDoubleSafe(consObj.optString("state", ""), -1);
                if (k >= 0) snap.dailyEnergyKwh = String.format(Locale.US, "%.1f kWh", k);
            }

            JSONObject mConsObj = fetchEntity(haUrl, haToken, "sensor.liljegrand_12_monthly_net_consumption");
            if (mConsObj != null) {
                double k = parseDoubleSafe(mConsObj.optString("state", ""), -1);
                if (k >= 0) snap.monthlyEnergyKwh = String.format(Locale.US, "%.1f kWh", k);
            }

            JSONObject mCostObj = fetchEntity(haUrl, haToken, "sensor.liljegrand_12_monthly_cost");
            if (mCostObj != null) {
                double c = parseDoubleSafe(mCostObj.optString("state", ""), -1);
                if (c >= 0) snap.monthlyCostSek = String.format(Locale.US, "%.0f kr", c);
            }

            JSONObject savSekObj = fetchEntity(haUrl, haToken, "sensor.ivt_daily_savings_sek");
            JSONObject savKwhObj = fetchEntity(haUrl, haToken, "sensor.ivt_daily_savings_kwh");
            JSONObject savPctObj = fetchEntity(haUrl, haToken, "sensor.ivt_daily_savings_pct");
            if (savSekObj != null) {
                String sSek = optValidState(savSekObj, "0.0");
                String sKwh = savKwhObj != null ? optValidState(savKwhObj, "0.0") : "0.0";
                String sPct = savPctObj != null ? optValidState(savPctObj, "0.0") : "0.0";
                snap.ivtSavingsToday = sSek + " kr (" + sKwh + " kWh / " + sPct + " %)";
            }

            // Temperatures
            JSONObject inTemp = fetchEntity(haUrl, haToken, "sensor.ivt_representativ_innetemperatur");
            if (inTemp != null) {
                double t = parseDoubleSafe(inTemp.optString("state", ""), -999);
                if (t > -100) snap.indoorTempC = String.format(Locale.US, "%.1f", t);
            }

            JSONObject inTarget = fetchEntity(haUrl, haToken, "sensor.ivt_rumstemperatur_borvarde");
            if (inTarget != null) {
                double t = parseDoubleSafe(inTarget.optString("state", ""), -999);
                if (t > -100) snap.indoorTargetC = String.format(Locale.US, "%.1f", t);
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

            JSONObject entreTemp = fetchEntity(haUrl, haToken, "sensor.entrehall_temperature");
            if (entreTemp != null) {
                double t = parseDoubleSafe(entreTemp.optString("state", ""), -999);
                if (t > -100) snap.entranceHallTempC = String.format(Locale.US, "%.1f", t);
            }

            JSONObject downTemp = fetchEntity(haUrl, haToken, "sensor.hall_timmerflotte_temp_underva_temperature");
            if (downTemp != null) {
                double t = parseDoubleSafe(downTemp.optString("state", ""), -999);
                if (t > -100) snap.downstairsTempC = String.format(Locale.US, "%.1f", t);
            }

            JSONObject supTemp = fetchEntity(haUrl, haToken, "sensor.ivt_framledningstemperatur");
            if (supTemp != null) {
                double t = parseDoubleSafe(supTemp.optString("state", ""), -999);
                if (t > -100) snap.supplyTempC = String.format(Locale.US, "%.1f", t);
            }

            JSONObject supTarget = fetchEntity(haUrl, haToken, "sensor.ivt_hc1_framledning_borvarde");
            if (supTarget != null) {
                double t = parseDoubleSafe(supTarget.optString("state", ""), -999);
                if (t > -100) snap.supplyTargetC = String.format(Locale.US, "%.1f", t);
            }

            JSONObject retTemp = fetchEntity(haUrl, haToken, "sensor.ivt_returtemperatur");
            if (retTemp != null) {
                double t = parseDoubleSafe(retTemp.optString("state", ""), -999);
                if (t > -100) snap.returnTempC = String.format(Locale.US, "%.1f", t);
            }

            JSONObject degMin = fetchEntity(haUrl, haToken, "sensor.ivt_virtuella_gradminuter");
            if (degMin != null) {
                snap.degreeMinutes = optValidState(degMin, snap.degreeMinutes);
            }

            JSONObject compDrift = fetchEntity(haUrl, haToken, "sensor.ivt_kompressor_drift");
            if (compDrift != null) {
                snap.compressorState = optValidState(compDrift, snap.compressorState);
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

            JSONObject spaStatus = fetchEntity(haUrl, haToken, "sensor.ivt_spa_bad_status");
            if (spaStatus != null) {
                snap.waterSpaStatus = optValidState(spaStatus, snap.waterSpaStatus);
            }

            JSONObject valveObj = fetchEntity(haUrl, haToken, "sensor.ivt_vaxelventil_lage");
            if (valveObj != null) {
                snap.valveMode = optValidState(valveObj, snap.valveMode);
            }

            JSONObject compHoursObj = fetchEntity(haUrl, haToken, "sensor.ivt_total_drifttid_timmar");
            JSONObject compStartsObj = fetchEntity(haUrl, haToken, "sensor.ivt_kompressor_antal_starter");
            if (compHoursObj != null) {
                String ch = optValidState(compHoursObj, "32812");
                String cs = compStartsObj != null ? optValidState(compStartsObj, "10302") : "10302";
                snap.compressorHours = ch + " h (" + cs + " starter)";
            }

            JSONObject aiBriefObj = fetchEntity(haUrl, haToken, "sensor.ivt_ai_daily_briefing");
            if (aiBriefObj != null) {
                snap.ivtAiBriefing = optValidState(aiBriefObj, snap.ivtAiBriefing);
            }

            // Knitting Light Mode (Lisa)
            String knitEntity = config.getKnittingEntityId();
            JSONObject knitObj = fetchEntity(haUrl, haToken, knitEntity);
            if (knitObj != null) {
                String kState = optValidState(knitObj, "off");
                snap.knittingLightOn = "on".equalsIgnoreCase(kState);
            }

            // Cleaning Light Mode
            String cleanEntity = config.getCleaningEntityId();
            JSONObject cleanObj = fetchEntity(haUrl, haToken, cleanEntity);
            if (cleanObj != null) {
                String cState = optValidState(cleanObj, "off");
                snap.cleaningLightOn = "on".equalsIgnoreCase(cState);
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
            if (!sel.isEmpty()) {
                snap.smillaOverrideMode = sel;
            }
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
            SimpleDateFormat hmFormat = new SimpleDateFormat("HH:mm", Locale.US);
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
                    StringBuilder logSb = new StringBuilder();

                    for (int i = 0; i < events.length(); i++) {
                        JSONObject ev = events.getJSONObject(i);
                        String st = ev.optString("state", "");
                        if (!"on".equals(st) && !"off".equals(st)) continue;

                        long tsMs = parseIsoTimestampMs(ev.optString("last_changed", ""), midnightMs);
                        long clampedTs = Math.max(midnightMs, tsMs);

                        if (prevState == null) {
                            // Initial state at midnight (or first record)
                            if (tsMs > midnightMs) {
                                String tStr = hmFormat.format(new Date(tsMs));
                                if ("off".equals(st)) {
                                    outCount++;
                                    outStartMs = clampedTs;
                                    if (logSb.length() > 0) logSb.append("  •  ");
                                    logSb.append(tStr).append(" Gick ut 🐾");
                                } else {
                                    inCount++;
                                    if (logSb.length() > 0) logSb.append("  •  ");
                                    logSb.append(tStr).append(" Kom in 🏠");
                                }
                            } else if ("off".equals(st)) {
                                outStartMs = midnightMs;
                                logSb.append("00:00 Ute sedan midnatt 🐾");
                            }
                        } else if (!st.equals(prevState)) {
                            String tStr = hmFormat.format(new Date(clampedTs));
                            if ("off".equals(st)) {
                                outCount++;
                                outStartMs = clampedTs;
                                if (logSb.length() > 0) logSb.append("  •  ");
                                logSb.append(tStr).append(" Gick ut 🐾");
                            } else {
                                inCount++;
                                long durMin = 0L;
                                if (outStartMs > 0 && clampedTs > outStartMs) {
                                    long diff = clampedTs - outStartMs;
                                    totalOutMs += diff;
                                    durMin = Math.round(diff / 60_000.0);
                                }
                                outStartMs = -1L;
                                if (logSb.length() > 0) logSb.append(" → ");
                                logSb.append(tStr).append(" Kom in 🏠");
                                if (durMin > 0) {
                                    logSb.append(" (").append(durMin).append(" min)");
                                }
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
                    snap.smillaLogSummary = logSb.length() > 0
                            ? logSb.toString()
                            : "Inga registrerade passager sedan midnatt (Smilla har varit " + (isInside ? "inne" : "ute") + ")";
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
                } else if ("lan".equals(sys)) {
                    int lanUsers = sub.optInt("num_user", 0) + sub.optInt("num_guest", 0);
                    if (lanUsers > 0) snap.lanClients = lanUsers;
                } else if ("wan".equals(sys)) {
                    String st = sub.optString("status", "ok");
                    snap.ucgState = "ok".equalsIgnoreCase(st) ? "Online" : st;
                    String wip = sub.optString("wan_ip", "");
                    if (!wip.isEmpty()) snap.wanIp = wip;
                    String isp = sub.optString("isp_name", "");
                    if (!isp.isEmpty()) snap.ispName = isp;
                    double rxBps = sub.optDouble("rx_bytes-r", 0.0);
                    double txBps = sub.optDouble("tx_bytes-r", 0.0);
                    snap.tvDownMbps = Math.round(((rxBps * 8.0) / 1_000_000.0) * 10.0) / 10.0;
                    snap.tvUpMbps = Math.round(((txBps * 8.0) / 1_000_000.0) * 10.0) / 10.0;
                    JSONObject gwStats = sub.optJSONObject("gw_system-stats");
                    if (gwStats != null) {
                        double cpu = parseDoubleSafe(gwStats.optString("cpu", ""), -1);
                        if (cpu >= 0) snap.ucgCpuPct = String.format(Locale.US, "%.0f %%", cpu);
                        double mem = parseDoubleSafe(gwStats.optString("mem", ""), -1);
                        if (mem >= 0) snap.ucgMemPct = String.format(Locale.US, "%.0f %%", mem);
                        long upSec = (long) parseDoubleSafe(gwStats.optString("uptime", ""), -1);
                        if (upSec > 0) {
                            long days = upSec / 86400L;
                            long hours = (upSec % 86400L) / 3600L;
                            snap.ucgUptime = days + " d " + hours + " h";
                        }
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
        s.smillaLogSummary = p.getString("smillaLogSummary", s.smillaLogSummary);
        s.smillaLockState = p.getString("smillaLockState", s.smillaLockState);
        s.smillaHubState = p.getString("smillaHubState", s.smillaHubState);
        s.smillaOverrideMode = p.getString("smillaOverrideMode", s.smillaOverrideMode);
        s.priceOre = p.getInt("priceOre", s.priceOre);
        s.priceMinOre = p.getInt("priceMinOre", s.priceMinOre);
        s.priceAvgOre = p.getInt("priceAvgOre", s.priceAvgOre);
        s.priceMaxOre = p.getInt("priceMaxOre", s.priceMaxOre);
        s.currentPowerW = p.getInt("currentPowerW", s.currentPowerW);
        s.dailyCostSek = p.getString("dailyCostSek", s.dailyCostSek);
        s.powerAvgKw = Double.longBitsToDouble(p.getLong("powerAvgKw", Double.doubleToLongBits(s.powerAvgKw)));
        s.powerMaxKw = Double.longBitsToDouble(p.getLong("powerMaxKw", Double.doubleToLongBits(s.powerMaxKw)));
        s.phaseCurrents = p.getString("phaseCurrents", s.phaseCurrents);
        s.dailyEnergyKwh = p.getString("dailyEnergyKwh", s.dailyEnergyKwh);
        s.monthlyEnergyKwh = p.getString("monthlyEnergyKwh", s.monthlyEnergyKwh);
        s.monthlyCostSek = p.getString("monthlyCostSek", s.monthlyCostSek);
        s.ivtSavingsToday = p.getString("ivtSavingsToday", s.ivtSavingsToday);
        s.indoorTempC = p.getString("indoorTempC", s.indoorTempC);
        s.outdoorTempC = p.getString("outdoorTempC", s.outdoorTempC);
        s.upperHallTempC = p.getString("upperHallTempC", s.upperHallTempC);
        s.indoorTargetC = p.getString("indoorTargetC", s.indoorTargetC);
        s.entranceHallTempC = p.getString("entranceHallTempC", s.entranceHallTempC);
        s.downstairsTempC = p.getString("downstairsTempC", s.downstairsTempC);
        s.supplyTempC = p.getString("supplyTempC", s.supplyTempC);
        s.supplyTargetC = p.getString("supplyTargetC", s.supplyTargetC);
        s.returnTempC = p.getString("returnTempC", s.returnTempC);
        s.degreeMinutes = p.getString("degreeMinutes", s.degreeMinutes);
        s.compressorState = p.getString("compressorState", s.compressorState);
        s.waterTempC = p.getString("waterTempC", s.waterTempC);
        s.waterTargetC = p.getString("waterTargetC", s.waterTargetC);
        s.waterVolume40cL = p.getString("waterVolume40cL", s.waterVolume40cL);
        s.waterStatus = p.getString("waterStatus", s.waterStatus);
        s.waterSpaStatus = p.getString("waterSpaStatus", s.waterSpaStatus);
        s.valveMode = p.getString("valveMode", s.valveMode);
        s.compressorHours = p.getString("compressorHours", s.compressorHours);
        s.ivtAiBriefing = p.getString("ivtAiBriefing", s.ivtAiBriefing);
        s.tvDownMbps = Double.longBitsToDouble(p.getLong("tvDownMbps", Double.doubleToLongBits(s.tvDownMbps)));
        s.tvUpMbps = Double.longBitsToDouble(p.getLong("tvUpMbps", Double.doubleToLongBits(s.tvUpMbps)));
        s.tvLocalDownMbps = Double.longBitsToDouble(p.getLong("tvLocalDownMbps", Double.doubleToLongBits(s.tvLocalDownMbps)));
        s.tvLocalUpMbps = Double.longBitsToDouble(p.getLong("tvLocalUpMbps", Double.doubleToLongBits(s.tvLocalUpMbps)));
        s.wanLatencyMs = p.getInt("wanLatencyMs", s.wanLatencyMs);
        s.ucgState = p.getString("ucgState", s.ucgState);
        s.ucgCpuPct = p.getString("ucgCpuPct", s.ucgCpuPct);
        s.ucgMemPct = p.getString("ucgMemPct", s.ucgMemPct);
        s.ucgUptime = p.getString("ucgUptime", s.ucgUptime);
        s.wanIp = p.getString("wanIp", s.wanIp);
        s.ispName = p.getString("ispName", s.ispName);
        s.wifiClients = p.getInt("wifiClients", s.wifiClients);
        s.lanClients = p.getInt("lanClients", s.lanClients);
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
                .putString("smillaLogSummary", s.smillaLogSummary)
                .putString("smillaLockState", s.smillaLockState)
                .putString("smillaHubState", s.smillaHubState)
                .putString("smillaOverrideMode", s.smillaOverrideMode)
                .putInt("priceOre", s.priceOre)
                .putInt("priceMinOre", s.priceMinOre)
                .putInt("priceAvgOre", s.priceAvgOre)
                .putInt("priceMaxOre", s.priceMaxOre)
                .putInt("currentPowerW", s.currentPowerW)
                .putString("dailyCostSek", s.dailyCostSek)
                .putLong("powerAvgKw", Double.doubleToLongBits(s.powerAvgKw))
                .putLong("powerMaxKw", Double.doubleToLongBits(s.powerMaxKw))
                .putString("phaseCurrents", s.phaseCurrents)
                .putString("dailyEnergyKwh", s.dailyEnergyKwh)
                .putString("monthlyEnergyKwh", s.monthlyEnergyKwh)
                .putString("monthlyCostSek", s.monthlyCostSek)
                .putString("ivtSavingsToday", s.ivtSavingsToday)
                .putString("indoorTempC", s.indoorTempC)
                .putString("outdoorTempC", s.outdoorTempC)
                .putString("upperHallTempC", s.upperHallTempC)
                .putString("indoorTargetC", s.indoorTargetC)
                .putString("entranceHallTempC", s.entranceHallTempC)
                .putString("downstairsTempC", s.downstairsTempC)
                .putString("supplyTempC", s.supplyTempC)
                .putString("supplyTargetC", s.supplyTargetC)
                .putString("returnTempC", s.returnTempC)
                .putString("degreeMinutes", s.degreeMinutes)
                .putString("compressorState", s.compressorState)
                .putString("waterTempC", s.waterTempC)
                .putString("waterTargetC", s.waterTargetC)
                .putString("waterVolume40cL", s.waterVolume40cL)
                .putString("waterStatus", s.waterStatus)
                .putString("waterSpaStatus", s.waterSpaStatus)
                .putString("valveMode", s.valveMode)
                .putString("compressorHours", s.compressorHours)
                .putString("ivtAiBriefing", s.ivtAiBriefing)
                .putLong("tvDownMbps", Double.doubleToLongBits(s.tvDownMbps))
                .putLong("tvUpMbps", Double.doubleToLongBits(s.tvUpMbps))
                .putLong("tvLocalDownMbps", Double.doubleToLongBits(s.tvLocalDownMbps))
                .putLong("tvLocalUpMbps", Double.doubleToLongBits(s.tvLocalUpMbps))
                .putInt("wanLatencyMs", s.wanLatencyMs)
                .putString("ucgState", s.ucgState)
                .putString("ucgCpuPct", s.ucgCpuPct)
                .putString("ucgMemPct", s.ucgMemPct)
                .putString("ucgUptime", s.ucgUptime)
                .putString("wanIp", s.wanIp)
                .putString("ispName", s.ispName)
                .putInt("wifiClients", s.wifiClients)
                .putInt("lanClients", s.lanClients)
                .putBoolean("knittingLightOn", s.knittingLightOn)
                .putBoolean("cleaningLightOn", s.cleaningLightOn)
                .putLong("updatedEpochMs", s.updatedEpochMs)
                .apply();
    }

    /**
     * Sets Smilla's manual override selector in Home Assistant (input_select.smilla_korrigerad_plats).
     */
    public static synchronized void setSmillaOverrideMode(Context context, DaemonConfig config, String option) {
        String haUrl = config.getHaUrl();
        String haToken = config.getHaToken();
        if (haUrl == null || haUrl.isEmpty() || haToken == null || haToken.isEmpty()) return;
        try {
            JSONObject body = new JSONObject();
            body.put("entity_id", "input_select.smilla_korrigerad_plats");
            body.put("option", option);
            postHaJson(haUrl.replaceAll("/+$", "") + "/api/services/input_select/select_option", haToken, body.toString());

            Snapshot snap = loadCached(context);
            snap.smillaOverrideMode = option;
            if (option.toLowerCase(Locale.ROOT).contains("tvinga inne")) {
                snap.smillaStatus = "Hemma (Inomhus)";
            } else if (option.toLowerCase(Locale.ROOT).contains("tvinga ute")) {
                snap.smillaStatus = "Ute";
            }
            snap.updatedEpochMs = 0L; // Force next refresh
            saveCached(context, snap);
        } catch (Exception e) {
            Log.w(TAG, "Failed to set Smilla override mode: " + e.getMessage());
        }
    }

    /**
     * Toggles the "Städning" lighting mode in Home Assistant.
     */
    public static synchronized boolean toggleCleaningLight(Context context, DaemonConfig config) {
        Snapshot snap = loadCached(context);
        boolean nextState = !snap.cleaningLightOn;
        snap.cleaningLightOn = nextState;
        saveCached(context, snap);

        String haUrl = config.getHaUrl();
        String haToken = config.getHaToken();
        String entityId = config.getCleaningEntityId();
        if (haUrl == null || haUrl.isEmpty() || haToken == null || haToken.isEmpty()) {
            return nextState;
        }

        String stateStr = nextState ? "on" : "off";
        String baseUrl = haUrl.replaceAll("/+$", "");

        try {
            String service = nextState ? "turn_on" : "turn_off";
            JSONObject svcBody = new JSONObject();
            svcBody.put("entity_id", entityId);
            postHaJson(baseUrl + "/api/services/input_boolean/" + service, haToken, svcBody.toString());

            Log.i(TAG, "Toggled Städning (" + entityId + ") -> " + stateStr.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            Log.w(TAG, "Failed to toggle Städning in HA: " + e.getMessage());
        }
        return nextState;
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
            String service = nextState ? "turn_on" : "turn_off";
            JSONObject svcBody = new JSONObject();
            svcBody.put("entity_id", entityId);
            postHaJson(baseUrl + "/api/services/input_boolean/" + service, haToken, svcBody.toString());

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
