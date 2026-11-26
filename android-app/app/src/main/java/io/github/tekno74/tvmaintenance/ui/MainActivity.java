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

import java.util.Set;

import io.github.tekno74.tvmaintenance.adb.AdbSessionGuard;
import io.github.tekno74.tvmaintenance.config.DaemonConfig;
import io.github.tekno74.tvmaintenance.events.DirtyPackageQueue;
import io.github.tekno74.tvmaintenance.events.WatcherService;
import io.github.tekno74.tvmaintenance.telemetry.HomeAssistantClient;
import io.github.tekno74.tvmaintenance.telemetry.MetricsCollector;

/**
 * Module 6 (ui): Clean, generic Android TV status dashboard for TV Optimizer v3.0 (Community Edition).
 */
public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private DaemonConfig config;
    private DirtyPackageQueue dirtyQueue;

    private TextView statusText;
    private Button btnRunNow;
    private Button btnToggleAdb;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        config = new DaemonConfig(this);
        dirtyQueue = new DirtyPackageQueue(this);

        startForegroundService(new Intent(this, WatcherService.class));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0F172A"));
        root.setPadding(64, 48, 64, 48);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("TV Optimizer v3.0");
        title.setTextColor(Color.parseColor("#38BDF8"));
        title.setTextSize(28f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Event-Driven On-Device Android TV Maintenance (Auto-Discovery + Dark Standby AOT)");
        subtitle.setTextColor(Color.parseColor("#94A3B8"));
        subtitle.setTextSize(14f);
        subtitle.setPadding(0, 6, 0, 28);
        root.addView(subtitle);

        statusText = new TextView(this);
        statusText.setTextColor(Color.parseColor("#E2E8F0"));
        statusText.setTextSize(15f);
        statusText.setTypeface(Typeface.MONOSPACE);
        statusText.setPadding(36, 28, 36, 28);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.parseColor("#1E293B"));
        cardBg.setCornerRadius(16f);
        cardBg.setStroke(2, Color.parseColor("#334155"));
        statusText.setBackground(cardBg);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardLp.setMargins(0, 0, 0, 28);
        root.addView(statusText, cardLp);

        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER);

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

        btnToggleAdb = createTvButton("ADB Port: CLOSED");
        btnToggleAdb.setOnClickListener(v -> {
            boolean nextKeepOpen = !config.isKeepAdbOpen();
            config.setKeepAdbOpen(nextKeepOpen);
            AdbSessionGuard.setAdbEnabledDirect(this, nextKeepOpen);
            refreshStatus();
        });

        Button btnRefresh = createTvButton("Refresh");
        btnRefresh.setOnClickListener(v -> refreshStatus());

        buttonRow.addView(btnRunNow);
        buttonRow.addView(btnToggleAdb);
        buttonRow.addView(btnRefresh);
        root.addView(buttonRow);

        setContentView(root);
        btnRunNow.requestFocus();
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
        b.setTextSize(15f);
        b.setPadding(32, 18, 32, 18);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(14, 0, 14, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void refreshStatus() {
        config.syncFromExternalJsonIfPresent();
        MetricsCollector.SystemSnapshot snap = MetricsCollector.collect(this);
        boolean hasPerm = AdbSessionGuard.hasWriteSecureSettings(this);
        Set<String> dirty = dirtyQueue.getDirtyPackages();
        String webhook = config.getHaWebhookUrl();

        StringBuilder sb = new StringBuilder();
        sb.append("ADB Port Policy      : ")
                .append(config.isKeepAdbOpen() ? "KEEP OPEN (port " + config.getAdbPort() + ")" : "AUTO-CLOSE AFTER RUN (Zero Open Ports)")
                .append("\n");
        sb.append("Auto-Discovery (3rd) : ").append(config.isAutoDiscoverUserApps() ? "ENABLED" : "DISABLED").append("\n");
        sb.append("WRITE_SECURE_SETTINGS: ").append(hasPerm ? "GRANTED (Ready)" : "MISSING").append("\n");
        sb.append("System Health        : ").append(MetricsCollector.formatSummary(snap)).append("\n");
        sb.append("Pending AOT Queue    : ").append(dirty.size()).append(" app(s)\n");
        sb.append("HA Webhook           : ").append(webhook != null && !webhook.isEmpty() ? "CONFIGURED" : "NOT SET").append("\n\n");
        sb.append(HomeAssistantClient.getLastTelemetrySummary(this));

        statusText.setText(sb.toString());
        btnToggleAdb.setText(config.isKeepAdbOpen() ? "ADB Port: OPEN" : "ADB Port: CLOSED");
    }
}
