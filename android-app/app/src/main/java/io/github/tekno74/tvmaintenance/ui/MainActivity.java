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
import android.view.View;
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
 * Module 6 (ui): Minimalist, D-pad friendly Android TV status & control dashboard.
 * Also serves as the CleanYourTV Dock Card entry point.
 */
public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private DaemonConfig config;
    private DirtyPackageQueue dirtyQueue;

    private TextView statusText;
    private Button btnRunNow;
    private Button btnToggleMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        config = new DaemonConfig(this);
        dirtyQueue = new DirtyPackageQueue(this);

        // Ensure WatcherService is running
        startService(new Intent(this, WatcherService.class));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0F172A"));
        root.setPadding(80, 60, 80, 60);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("TV Maintenance Daemon v3.0 (On-Device)");
        title.setTextColor(Color.parseColor("#38BDF8"));
        title.setTextSize(26f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Event-Driven Standby Optimizer, AOT Speed-Compiler & RAM Guard");
        subtitle.setTextColor(Color.parseColor("#94A3B8"));
        subtitle.setTextSize(15f);
        subtitle.setPadding(0, 8, 0, 32);
        root.addView(subtitle);

        statusText = new TextView(this);
        statusText.setTextColor(Color.parseColor("#F8FAFC"));
        statusText.setTextSize(16f);
        statusText.setTypeface(Typeface.MONOSPACE);
        statusText.setPadding(40, 32, 40, 32);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.parseColor("#1E293B"));
        cardBg.setCornerRadius(16f);
        cardBg.setStroke(2, Color.parseColor("#334155"));
        statusText.setBackground(cardBg);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardParams.setMargins(0, 0, 0, 36);
        root.addView(statusText, cardParams);

        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER);

        btnRunNow = createTvButton("Run Maintenance Now");
        btnRunNow.setOnClickListener(v -> {
            btnRunNow.setText("Running...");
            Intent runIntent = new Intent(this, WatcherService.class);
            runIntent.setAction(WatcherService.ACTION_MANUAL_RUN);
            startService(runIntent);
            handler.postDelayed(() -> {
                btnRunNow.setText("Run Maintenance Now");
                refreshStatus();
            }, 4000);
        });

        btnToggleMode = createTvButton("Switch Mode");
        btnToggleMode.setOnClickListener(v -> {
            String nextMode = config.isStandaloneMode()
                    ? DaemonConfig.MODE_PARALLEL
                    : DaemonConfig.MODE_STANDALONE;
            config.setMode(nextMode);
            refreshStatus();
        });

        Button btnRefresh = createTvButton("Refresh Status");
        btnRefresh.setOnClickListener(v -> refreshStatus());

        buttonRow.addView(btnRunNow);
        buttonRow.addView(btnToggleMode);
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
        b.setPadding(36, 20, 36, 20);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(16, 0, 16, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void refreshStatus() {
        config.syncFromExternalJsonIfPresent();
        MetricsCollector.SystemSnapshot snap = MetricsCollector.collect(this);
        boolean hasPerm = AdbSessionGuard.hasWriteSecureSettings(this);
        Set<String> dirty = dirtyQueue.getDirtyPackages();
        String modeDesc = config.isStandaloneMode()
                ? "STANDALONE (Phase 2: closes ADB port 5555 after each run)"
                : "PARALLEL (Phase 1: runs alongside Synology NAS daemon)";

        StringBuilder sb = new StringBuilder();
        sb.append("Operating Mode       : ").append(modeDesc).append("\n");
        sb.append("WRITE_SECURE_SETTINGS: ").append(hasPerm ? "GRANTED (Ready)" : "MISSING (Run pm grant)").append("\n");
        sb.append("System Metrics       : ").append(MetricsCollector.formatSummary(snap)).append("\n");
        sb.append("Screen-Off Debounce  : ").append(config.getScreenOffDebounceSeconds()).append(" seconds\n");
        sb.append("Dirty AOT Queue      : ").append(dirty.size()).append(" app(s) pending compile\n");
        sb.append("Monitored Core Apps  : ").append(config.getCoreApps().size()).append(" streaming packages\n\n");
        sb.append(HomeAssistantClient.getLastTelemetrySummary(this));

        statusText.setText(sb.toString());
        btnToggleMode.setText(config.isStandaloneMode() ? "Switch to PARALLEL" : "Switch to STANDALONE");
    }
}
