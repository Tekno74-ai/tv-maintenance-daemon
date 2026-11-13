package io.github.tekno74.tvmaintenance.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;
import java.util.Set;

import io.github.tekno74.tvmaintenance.adb.AdbSessionGuard;
import io.github.tekno74.tvmaintenance.config.DaemonConfig;
import io.github.tekno74.tvmaintenance.events.DirtyPackageQueue;
import io.github.tekno74.tvmaintenance.events.WatcherService;
import io.github.tekno74.tvmaintenance.telemetry.HomeAssistantClient;
import io.github.tekno74.tvmaintenance.telemetry.MetricsCollector;
import io.github.tekno74.tvmaintenance.widgets.SmartHomeData;

/**
 * Module 6 (ui): Full-screen Android TV Smart Home & Maintenance Dashboard.
 * Displays live Home Assistant metrics (Smilla, Electricity, Climate, Water Heater, Internet)
 * alongside On-Device TV Maintenance controls.
 */
public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private DaemonConfig config;
    private DirtyPackageQueue dirtyQueue;

    private TextView smartHomeText;
    private TextView statusText;
    private Button btnKnitting;
    private Button btnRunNow;
    private Button btnToggleMode;
    private Button btnToggleHud;
    private Button btnToggleCards;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        config = new DaemonConfig(this);
        dirtyQueue = new DirtyPackageQueue(this);

        // Ensure WatcherService is running
        startForegroundService(new Intent(this, WatcherService.class));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0F172A"));
        root.setPadding(64, 40, 64, 40);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("Villa Wahren Live & TV Optimizer v3.0");
        title.setTextColor(Color.parseColor("#38BDF8"));
        title.setTextSize(24f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Smart Home Live Widgets + Event-Driven Standby Maintenance (Zero Network Ports)");
        subtitle.setTextColor(Color.parseColor("#94A3B8"));
        subtitle.setTextSize(13.5f);
        subtitle.setPadding(0, 4, 0, 20);
        root.addView(subtitle);

        // Smart Home Live Card
        smartHomeText = new TextView(this);
        smartHomeText.setTextColor(Color.parseColor("#F8FAFC"));
        smartHomeText.setTextSize(15f);
        smartHomeText.setTypeface(Typeface.MONOSPACE);
        smartHomeText.setPadding(32, 22, 32, 22);
        GradientDrawable homeBg = new GradientDrawable();
        homeBg.setColor(Color.parseColor("#1E293B"));
        homeBg.setCornerRadius(14f);
        homeBg.setStroke(2, Color.parseColor("#38BDF8"));
        smartHomeText.setBackground(homeBg);
        LinearLayout.LayoutParams homeParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        homeParams.setMargins(0, 0, 0, 18);
        root.addView(smartHomeText, homeParams);

        // TV Maintenance Card
        statusText = new TextView(this);
        statusText.setTextColor(Color.parseColor("#E2E8F0"));
        statusText.setTextSize(14f);
        statusText.setTypeface(Typeface.MONOSPACE);
        statusText.setPadding(32, 20, 32, 20);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.parseColor("#1E293B"));
        cardBg.setCornerRadius(14f);
        cardBg.setStroke(2, Color.parseColor("#334155"));
        statusText.setBackground(cardBg);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardParams.setMargins(0, 0, 0, 24);
        root.addView(statusText, cardParams);

        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER);

        btnKnitting = createTvButton("🧶 Stickbelysning");
        btnKnitting.setOnClickListener(v -> {
            btnKnitting.setText("🧶 Växlar...");
            new Thread(() -> {
                SmartHomeData.toggleKnittingLight(getApplicationContext(), config);
                handler.post(this::refreshStatus);
            }).start();
        });

        btnRunNow = createTvButton("Run Maintenance Now");
        btnRunNow.setOnClickListener(v -> {
            btnRunNow.setText("Running...");
            Intent runIntent = new Intent(this, WatcherService.class);
            runIntent.setAction(WatcherService.ACTION_MANUAL_RUN);
            startForegroundService(runIntent);
            handler.postDelayed(() -> {
                btnRunNow.setText("Run Maintenance Now");
                refreshStatus();
            }, 4000);
        });

        btnToggleHud = createTvButton("Top HUD Bar");
        btnToggleHud.setOnClickListener(v -> {
            config.setHudOverlayEnabled(!config.isHudOverlayEnabled());
            refreshStatus();
        });

        btnToggleCards = createTvButton("Bottom Cards");
        btnToggleCards.setOnClickListener(v -> {
            config.setHomeChannelEnabled(!config.isHomeChannelEnabled());
            refreshStatus();
        });

        btnToggleMode = createTvButton("Switch Mode");
        btnToggleMode.setOnClickListener(v -> {
            String nextMode = config.isStandaloneMode()
                    ? DaemonConfig.MODE_PARALLEL
                    : DaemonConfig.MODE_STANDALONE;
            config.setMode(nextMode);
            refreshStatus();
        });

        Button btnRefresh = createTvButton("Refresh");
        btnRefresh.setOnClickListener(v -> refreshStatus());

        buttonRow.addView(btnKnitting);
        buttonRow.addView(btnRunNow);
        buttonRow.addView(btnToggleHud);
        buttonRow.addView(btnToggleCards);
        buttonRow.addView(btnToggleMode);
        buttonRow.addView(btnRefresh);
        root.addView(buttonRow);

        setContentView(root);
        btnKnitting.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private Button createTvButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(14f);
        b.setPadding(28, 16, 28, 16);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(12, 0, 12, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void refreshStatus() {
        config.syncFromExternalJsonIfPresent();
        SmartHomeData.Snapshot home = SmartHomeData.loadCached(this);
        MetricsCollector.SystemSnapshot snap = MetricsCollector.collect(this);
        boolean hasPerm = AdbSessionGuard.hasWriteSecureSettings(this);
        Set<String> dirty = dirtyQueue.getDirtyPackages();

        StringBuilder hb = new StringBuilder();
        hb.append("🧶 Stickning   : ")
                .append(home.knittingLightOn ? "PÅ 💡 (Full stickbelysning aktiv)" : "AV 🌙 (Normal belysning)")
                .append("  |  Entitet: ").append(config.getKnittingEntityId()).append("\n");
        hb.append("🐱 Smilla      : ").append(home.smillaStatus)
                .append("  |  Ute idag: ").append(home.smillaHoursOut)
                .append("  |  Passager: ").append(home.smillaPassages)
                .append("  |  Lucka: ").append(home.flapBattery).append("\n");
        hb.append(String.format(Locale.US,
                "⚡ El & Effekt : %d öre/kWh (Snitt %d, Min %d, Max %d)  |  Nu: %.2f kW (%d W)  |  Idag: %s\n",
                home.priceOre, home.priceAvgOre, home.priceMinOre, home.priceMaxOre,
                home.currentPowerW / 1000.0, home.currentPowerW, home.dailyCostSek));
        hb.append("🌡️ Klimat      : Inne ").append(home.indoorTempC).append(" °C  |  Ute ")
                .append(home.outdoorTempC).append(" °C  |  Övre hall ").append(home.upperHallTempC).append(" °C\n");
        hb.append("🚿 Varmvatten  : ").append(home.waterTempC).append(" °C (Mål ").append(home.waterTargetC)
                .append(" °C)  |  40°C-volym: ").append(home.waterVolume40cL).append(" L  |  ").append(home.waterStatus).append("\n");
        hb.append(String.format(Locale.US,
                "🌐 Internet    : TV ↓ %.1f Mbit/s ↑ %.1f Mbit/s  |  UCG Max: %s (CPU %s)  |  Wi-Fi: %d enheter  |  NAS: %s",
                home.tvDownMbps, home.tvUpMbps, home.ucgState, home.ucgCpuPct, home.wifiClients, home.nasDownKbps));
        smartHomeText.setText(hb.toString());

        String modeDesc = config.isStandaloneMode()
                ? "STANDALONE (Phase 2: closes ADB port 5555 after each run)"
                : "PARALLEL (Phase 1: runs alongside Synology NAS daemon)";

        StringBuilder sb = new StringBuilder();
        sb.append("Operating Mode       : ").append(modeDesc).append("\n");
        sb.append("Home Screen Widgets  : Bottom 5-Card Row = ")
                .append(config.isHomeChannelEnabled() ? "ON" : "OFF")
                .append("  |  Top HUD Bar = ")
                .append(config.isHudOverlayEnabled() ? "ON" : "OFF").append("\n");
        sb.append("WRITE_SECURE_SETTINGS: ").append(hasPerm ? "GRANTED (Ready)" : "MISSING").append("  |  ")
                .append(MetricsCollector.formatSummary(snap)).append("\n");
        sb.append("Dirty AOT Queue      : ").append(dirty.size()).append(" app(s) pending  |  ")
                .append(HomeAssistantClient.getLastTelemetrySummary(this).replace("\n", "  |  "));

        statusText.setText(sb.toString());
        btnKnitting.setText(home.knittingLightOn ? "🧶 Stickning: PÅ 💡" : "🧶 Stickning: AV 🌙");
        btnToggleHud.setText(config.isHudOverlayEnabled() ? "Top HUD: ON" : "Top HUD: OFF");
        btnToggleCards.setText(config.isHomeChannelEnabled() ? "Bottom Cards: ON" : "Bottom Cards: OFF");
        btnToggleMode.setText(config.isStandaloneMode() ? "Switch to PARALLEL" : "Switch to STANDALONE");
    }
}
