package io.github.tekno74.tvmaintenance.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;
import java.util.Set;

import io.github.tekno74.tvmaintenance.R;
import io.github.tekno74.tvmaintenance.adb.AdbSessionGuard;
import io.github.tekno74.tvmaintenance.config.DaemonConfig;
import io.github.tekno74.tvmaintenance.events.DirtyPackageQueue;
import io.github.tekno74.tvmaintenance.events.WatcherService;
import io.github.tekno74.tvmaintenance.telemetry.HomeAssistantClient;
import io.github.tekno74.tvmaintenance.telemetry.MetricsCollector;
import io.github.tekno74.tvmaintenance.widgets.SmartHomeData;

/**
 * Module 6 (ui): Interactive Android TV Smart Home Widget Explorer & TV Optimizer v3.0 Dashboard.
 * Provides 5 D-pad focusable & clickable widget cards (Smilla, Elpris, Temperaturer, Varmvatten, Internet)
 * with live drill-down detail views and interactive Home Assistant controls.
 */
public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private DaemonConfig config;
    private DirtyPackageQueue dirtyQueue;

    private WidgetCardHolder cardSmilla;
    private WidgetCardHolder cardElpris;
    private WidgetCardHolder cardKlimat;
    private WidgetCardHolder cardVarmvatten;
    private WidgetCardHolder cardInternet;

    private LinearLayout detailContainer;
    private View detailAccentBar;
    private TextView detailTitle;
    private TextView detailSubtitle;
    private TextView detailBody;
    private TextView detailHint;

    private TextView statusText;
    private Button btnKnitting;
    private Button btnRunNow;
    private Button btnToggleMode;
    private Button btnToggleHud;
    private Button btnToggleCards;

    private String selectedWidgetKey = "smilla";
    private SmartHomeData.Snapshot latestHomeSnap;

    private static final class WidgetCardHolder {
        final String key;
        final int accentColor;
        final LinearLayout container;
        final View accentLine;
        final TextView badge;
        final TextView value;
        final TextView sub1;
        final TextView sub2;
        final TextView clickHint;

        WidgetCardHolder(
                String key,
                int accentColor,
                LinearLayout container,
                View accentLine,
                TextView badge,
                TextView value,
                TextView sub1,
                TextView sub2,
                TextView clickHint
        ) {
            this.key = key;
            this.accentColor = accentColor;
            this.container = container;
            this.accentLine = accentLine;
            this.badge = badge;
            this.value = value;
            this.sub1 = sub1;
            this.sub2 = sub2;
            this.clickHint = clickHint;
        }
    }

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
        root.setPadding(48, 28, 48, 28);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        // 1. Header Row with Wahren Coat of Arms
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setPadding(0, 0, 0, 14);
        LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        headerRow.setLayoutParams(headerLp);

        ImageView crestView = new ImageView(this);
        crestView.setImageResource(R.drawable.wahren_crest);
        crestView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams crestLp = new LinearLayout.LayoutParams(50, 66);
        crestLp.setMargins(0, 0, 18, 0);
        crestView.setLayoutParams(crestLp);
        headerRow.addView(crestView);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText("TV Optimizer v3.0 — Villa Wahren Live Widgets");
        title.setTextColor(Color.parseColor("#38BDF8"));
        title.setTextSize(21f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleCol.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Navigera med fjärrkontrollen (◀ ▶) mellan widgetarna och tryck OK för fullständig detaljvy & styrning");
        subtitle.setTextColor(Color.parseColor("#94A3B8"));
        subtitle.setTextSize(12.5f);
        subtitle.setPadding(0, 2, 0, 0);
        titleCol.addView(subtitle);

        headerRow.addView(titleCol);
        root.addView(headerRow);

        // 2. Interactive 5-Widget Row (Focusable + Clickable with D-Pad)
        LinearLayout widgetsRow = new LinearLayout(this);
        widgetsRow.setOrientation(LinearLayout.HORIZONTAL);
        widgetsRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        rowLp.setMargins(0, 0, 0, 16);
        widgetsRow.setLayoutParams(rowLp);

        cardSmilla = createInteractiveWidgetCard("smilla", Color.parseColor("#22C55E"));
        cardElpris = createInteractiveWidgetCard("elpris", Color.parseColor("#10B981"));
        cardKlimat = createInteractiveWidgetCard("klimat", Color.parseColor("#38BDF8"));
        cardVarmvatten = createInteractiveWidgetCard("varmvatten", Color.parseColor("#FB923C"));
        cardInternet = createInteractiveWidgetCard("internet", Color.parseColor("#C084FC"));

        widgetsRow.addView(cardSmilla.container);
        widgetsRow.addView(cardElpris.container);
        widgetsRow.addView(cardKlimat.container);
        widgetsRow.addView(cardVarmvatten.container);
        widgetsRow.addView(cardInternet.container);
        root.addView(widgetsRow);

        // 3. Large Live Drill-Down Detail Panel (updates automatically as you focus/click any widget)
        detailContainer = new LinearLayout(this);
        detailContainer.setOrientation(LinearLayout.VERTICAL);
        detailContainer.setPadding(28, 18, 28, 18);
        GradientDrawable detailBg = new GradientDrawable();
        detailBg.setColor(Color.parseColor("#1E293B"));
        detailBg.setCornerRadius(14f);
        detailBg.setStroke(2, Color.parseColor("#38BDF8"));
        detailContainer.setBackground(detailBg);
        LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        detailLp.setMargins(0, 0, 0, 14);
        detailContainer.setLayoutParams(detailLp);

        detailAccentBar = new View(this);
        LinearLayout.LayoutParams accentLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 4);
        accentLp.setMargins(0, 0, 0, 10);
        detailAccentBar.setLayoutParams(accentLp);
        detailAccentBar.setBackgroundColor(Color.parseColor("#22C55E"));
        detailContainer.addView(detailAccentBar);

        LinearLayout detailHeaderRow = new LinearLayout(this);
        detailHeaderRow.setOrientation(LinearLayout.HORIZONTAL);
        detailHeaderRow.setGravity(Gravity.CENTER_VERTICAL);

        detailTitle = new TextView(this);
        detailTitle.setTextColor(Color.parseColor("#F8FAFC"));
        detailTitle.setTextSize(17.5f);
        detailTitle.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams dtLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        detailTitle.setLayoutParams(dtLp);
        detailHeaderRow.addView(detailTitle);

        detailHint = new TextView(this);
        detailHint.setText("🔘 Tryck OK på kortet för popupruta & åtgärder");
        detailHint.setTextColor(Color.parseColor("#38BDF8"));
        detailHint.setTextSize(12f);
        detailHint.setTypeface(Typeface.DEFAULT_BOLD);
        detailHeaderRow.addView(detailHint);

        detailContainer.addView(detailHeaderRow);

        detailSubtitle = new TextView(this);
        detailSubtitle.setTextColor(Color.parseColor("#94A3B8"));
        detailSubtitle.setTextSize(12.5f);
        detailSubtitle.setPadding(0, 2, 0, 8);
        detailContainer.addView(detailSubtitle);

        detailBody = new TextView(this);
        detailBody.setTextColor(Color.parseColor("#E2E8F0"));
        detailBody.setTextSize(13.5f);
        detailBody.setTypeface(Typeface.MONOSPACE);
        detailBody.setLineSpacing(4f, 1.05f);
        detailContainer.addView(detailBody);

        root.addView(detailContainer);

        // 4. Compact TV Optimizer Status Card
        statusText = new TextView(this);
        statusText.setTextColor(Color.parseColor("#CBD5E1"));
        statusText.setTextSize(12f);
        statusText.setTypeface(Typeface.MONOSPACE);
        statusText.setPadding(24, 12, 24, 12);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.parseColor("#172033"));
        cardBg.setCornerRadius(12f);
        cardBg.setStroke(1, Color.parseColor("#334155"));
        statusText.setBackground(cardBg);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardParams.setMargins(0, 0, 0, 14);
        root.addView(statusText, cardParams);

        // 5. Bottom Control Buttons Row
        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER);

        btnKnitting = createTvButton("🧶 Stickbelysning");
        btnKnitting.setOnClickListener(v -> {
            btnKnitting.setText("🧶 Växlar...");
            new Thread(() -> {
                SmartHomeData.toggleKnittingLight(getApplicationContext(), config);
                SmartHomeData.Snapshot fresh = SmartHomeData.refresh(getApplicationContext(), config);
                handler.post(() -> {
                    latestHomeSnap = fresh;
                    refreshStatus();
                });
            }).start();
        });

        btnRunNow = createTvButton("⚡ Optimera TV Nu");
        btnRunNow.setOnClickListener(v -> {
            btnRunNow.setText("Optimerar...");
            Intent runIntent = new Intent(this, WatcherService.class);
            runIntent.setAction(WatcherService.ACTION_MANUAL_RUN);
            startForegroundService(runIntent);
            handler.postDelayed(() -> {
                btnRunNow.setText("⚡ Optimera TV Nu");
                refreshStatus();
            }, 4000);
        });

        btnToggleCards = createTvButton("Widget-rad: PÅ");
        btnToggleCards.setOnClickListener(v -> {
            config.setHomeChannelEnabled(!config.isHomeChannelEnabled());
            refreshStatus();
        });

        btnToggleHud = createTvButton("Kompakt Rad: AV");
        btnToggleHud.setOnClickListener(v -> {
            config.setHudOverlayEnabled(!config.isHudOverlayEnabled());
            refreshStatus();
        });

        btnToggleMode = createTvButton("Läge: PARALLEL");
        btnToggleMode.setOnClickListener(v -> {
            String nextMode = config.isStandaloneMode()
                    ? DaemonConfig.MODE_PARALLEL
                    : DaemonConfig.MODE_STANDALONE;
            config.setMode(nextMode);
            refreshStatus();
        });

        Button btnRefresh = createTvButton("🔄 Uppdatera");
        btnRefresh.setOnClickListener(v -> triggerLiveRefresh());

        buttonRow.addView(btnKnitting);
        buttonRow.addView(btnRunNow);
        buttonRow.addView(btnToggleCards);
        buttonRow.addView(btnToggleHud);
        buttonRow.addView(btnToggleMode);
        buttonRow.addView(btnRefresh);
        root.addView(buttonRow);

        setContentView(root);
        handleIncomingIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        String key = intent != null ? intent.getStringExtra("widget_key") : null;
        if (key != null && !key.isEmpty()) {
            selectedWidgetKey = key;
        }
        focusSelectedWidgetCard();
        if (key != null && !key.isEmpty()) {
            handler.postDelayed(() -> showWidgetDetailDialog(selectedWidgetKey), 250);
        }
    }

    private void focusSelectedWidgetCard() {
        if ("elpris".equals(selectedWidgetKey)) {
            cardElpris.container.requestFocus();
        } else if ("klimat".equals(selectedWidgetKey)) {
            cardKlimat.container.requestFocus();
        } else if ("varmvatten".equals(selectedWidgetKey)) {
            cardVarmvatten.container.requestFocus();
        } else if ("internet".equals(selectedWidgetKey)) {
            cardInternet.container.requestFocus();
        } else {
            cardSmilla.container.requestFocus();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        triggerLiveRefresh();
    }

    private void triggerLiveRefresh() {
        new Thread(() -> {
            SmartHomeData.Snapshot fresh = SmartHomeData.refresh(getApplicationContext(), config);
            handler.post(() -> {
                latestHomeSnap = fresh;
                refreshStatus();
            });
        }).start();
    }

    private WidgetCardHolder createInteractiveWidgetCard(String key, int defaultAccentColor) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(16, 12, 16, 12);
        card.setFocusable(true);
        card.setFocusableInTouchMode(true);
        card.setClickable(true);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        cardLp.setMargins(6, 4, 6, 4);
        card.setLayoutParams(cardLp);

        View accent = new View(this);
        LinearLayout.LayoutParams accentLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 4);
        accentLp.setMargins(0, 0, 0, 6);
        accent.setLayoutParams(accentLp);
        accent.setBackgroundColor(defaultAccentColor);
        card.addView(accent);

        TextView badge = new TextView(this);
        badge.setTextSize(10f);
        badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        badge.setSingleLine(true);
        badge.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(badge);

        TextView value = new TextView(this);
        value.setTextColor(Color.parseColor("#F8FAFC"));
        value.setTextSize(17f);
        value.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        value.setPadding(0, 2, 0, 2);
        value.setSingleLine(true);
        value.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(value);

        TextView sub1 = new TextView(this);
        sub1.setTextColor(Color.parseColor("#CBD5E1"));
        sub1.setTextSize(11f);
        sub1.setSingleLine(true);
        sub1.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(sub1);

        TextView sub2 = new TextView(this);
        sub2.setTextColor(Color.parseColor("#94A3B8"));
        sub2.setTextSize(10.5f);
        sub2.setPadding(0, 1, 0, 4);
        sub2.setSingleLine(true);
        sub2.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(sub2);

        TextView clickHint = new TextView(this);
        clickHint.setText("▶ Tryck OK för detaljer");
        clickHint.setTextColor(Color.parseColor("#64748B"));
        clickHint.setTextSize(9.5f);
        clickHint.setTypeface(Typeface.DEFAULT_BOLD);
        clickHint.setSingleLine(true);
        card.addView(clickHint);

        WidgetCardHolder holder = new WidgetCardHolder(
                key, defaultAccentColor, card, accent, badge, value, sub1, sub2, clickHint);

        styleCardFocus(holder, false);

        card.setOnFocusChangeListener((v, hasFocus) -> {
            styleCardFocus(holder, hasFocus);
            if (hasFocus) {
                selectedWidgetKey = key;
                if (latestHomeSnap != null) {
                    bindDetailPanel(selectedWidgetKey, latestHomeSnap);
                }
            }
        });

        card.setOnClickListener(v -> {
            selectedWidgetKey = key;
            if (latestHomeSnap != null) {
                bindDetailPanel(selectedWidgetKey, latestHomeSnap);
            }
            showWidgetDetailDialog(key);
        });

        return holder;
    }

    private void styleCardFocus(WidgetCardHolder holder, boolean focused) {
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                focused
                        ? new int[]{Color.parseColor("#1E293B"), Color.parseColor("#334155")}
                        : new int[]{Color.parseColor("#151F32"), Color.parseColor("#1E293B")}
        );
        bg.setCornerRadius(14f);
        if (focused) {
            bg.setStroke(3, Color.parseColor("#38BDF8"));
            holder.container.setScaleX(1.03f);
            holder.container.setScaleY(1.03f);
            holder.clickHint.setText("🔘 Tryck OK för mer info");
            holder.clickHint.setTextColor(Color.parseColor("#38BDF8"));
        } else {
            bg.setStroke(2, Color.parseColor("#334155"));
            holder.container.setScaleX(1.0f);
            holder.container.setScaleY(1.0f);
            holder.clickHint.setText("▶ Välj för detaljer");
            holder.clickHint.setTextColor(Color.parseColor("#64748B"));
        }
        holder.container.setBackground(bg);
    }

    private Button createTvButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(13f);
        b.setPadding(22, 12, 22, 12);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(8, 0, 8, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void refreshStatus() {
        config.syncFromExternalJsonIfPresent();
        SmartHomeData.Snapshot home = (latestHomeSnap != null) ? latestHomeSnap : SmartHomeData.loadCached(this);
        latestHomeSnap = home;
        MetricsCollector.SystemSnapshot snap = MetricsCollector.collect(this);
        boolean hasPerm = AdbSessionGuard.hasWriteSecureSettings(this);
        Set<String> dirty = dirtyQueue.getDirtyPackages();

        bindWidgetCards(home);
        bindDetailPanel(selectedWidgetKey, home);

        String modeDesc = config.isStandaloneMode()
                ? "STANDALONE (Fas 2: stänger port 5555 efter körning)"
                : "PARALLEL (Fas 1: körs parallellt med Synology NAS)";

        StringBuilder sb = new StringBuilder();
        sb.append("Driftläge: ").append(modeDesc)
                .append("  |  WRITE_SECURE_SETTINGS: ").append(hasPerm ? "REDO" : "SAKNAS")
                .append("  |  ").append(MetricsCollector.formatSummary(snap)).append("\n");
        sb.append("Köade app-optimeringar (AOT): ").append(dirty.size()).append(" st  |  ")
                .append(HomeAssistantClient.getLastTelemetrySummary(this).replace("\n", "  |  "));

        statusText.setText(sb.toString());
        btnKnitting.setText(home.knittingLightOn ? "🧶 Stickning: PÅ 💡" : "🧶 Stickning: AV 🌙");
        btnToggleCards.setText(config.isHomeChannelEnabled() ? "Widget-rad: PÅ" : "Widget-rad: AV");
        btnToggleHud.setText(config.isHudOverlayEnabled() ? "Kompakt Rad: PÅ" : "Kompakt Rad: AV");
        btnToggleMode.setText(config.isStandaloneMode() ? "Läge: STANDALONE" : "Läge: PARALLEL");
    }

    private void bindWidgetCards(SmartHomeData.Snapshot s) {
        // 1. Smilla
        boolean out = s.isSmillaOut();
        int smillaColor = s.knittingLightOn ? Color.parseColor("#FDE047")
                : (out ? Color.parseColor("#F59E0B") : Color.parseColor("#22C55E"));
        cardSmilla.accentLine.setBackgroundColor(smillaColor);
        cardSmilla.badge.setText(s.knittingLightOn ? "🧶 STICKNING • 🐱 SMILLA" : "🐱 SMILLA STATUS");
        cardSmilla.badge.setTextColor(smillaColor);
        cardSmilla.value.setText(out ? "UTE NU" : "INNE HEMMA");
        cardSmilla.sub1.setText("Ute: " + s.smillaHoursOut + " • " + s.smillaPassages);
        cardSmilla.sub2.setText("Kattlucka batteri: " + s.flapBattery);

        // 2. Elpris & Effekt
        int elColor = s.priceOre >= 180 ? Color.parseColor("#F43F5E")
                : (s.priceOre >= 120 ? Color.parseColor("#F59E0B") : Color.parseColor("#10B981"));
        cardElpris.accentLine.setBackgroundColor(elColor);
        cardElpris.badge.setText("⚡ ELPRIS & EFFEKT");
        cardElpris.badge.setTextColor(elColor);
        cardElpris.value.setText(s.priceOre + " öre/kWh");
        cardElpris.sub1.setText(String.format(Locale.US, "Nu: %.2f kW • Idag: %s",
                s.currentPowerW / 1000.0, s.dailyCostSek));
        cardElpris.sub2.setText("Min " + s.priceMinOre + " • Snitt " + s.priceAvgOre + " • Max " + s.priceMaxOre);

        // 3. Temperaturer
        int klimColor = Color.parseColor("#38BDF8");
        cardKlimat.accentLine.setBackgroundColor(klimColor);
        cardKlimat.badge.setText("🌡️ TEMPERATURER");
        cardKlimat.badge.setTextColor(klimColor);
        cardKlimat.value.setText(s.indoorTempC + " °C inne");
        cardKlimat.sub1.setText("Utomhus: " + s.outdoorTempC + " °C");
        cardKlimat.sub2.setText("Övre hall: " + s.upperHallTempC + " °C");

        // 4. Varmvatten
        int vvColor = Color.parseColor("#FB923C");
        cardVarmvatten.accentLine.setBackgroundColor(vvColor);
        cardVarmvatten.badge.setText("🚿 VARMVATTEN (IVT)");
        cardVarmvatten.badge.setTextColor(vvColor);
        cardVarmvatten.value.setText(s.waterTempC + " °C");
        cardVarmvatten.sub1.setText("40°C-volym: " + s.waterVolume40cL + " liter");
        cardVarmvatten.sub2.setText("Mål " + s.waterTargetC + " °C • " + s.waterStatus);

        // 5. Internet & Wi-Fi
        int netColor = Color.parseColor("#C084FC");
        cardInternet.accentLine.setBackgroundColor(netColor);
        cardInternet.badge.setText("🌐 INTERNET & WIFI");
        cardInternet.badge.setTextColor(netColor);
        cardInternet.value.setText(String.format(Locale.US, "↓ %.1f Mbit/s", s.tvDownMbps));
        cardInternet.sub1.setText(String.format(Locale.US, "↑ %.1f Mbit/s • Ping %d ms", s.tvUpMbps, s.wanLatencyMs));
        cardInternet.sub2.setText("Wi-Fi: " + s.wifiClients + " klienter • CPU " + s.ucgCpuPct.replace(" ", ""));
    }

    private void bindDetailPanel(String key, SmartHomeData.Snapshot s) {
        if ("elpris".equals(key)) {
            detailAccentBar.setBackgroundColor(Color.parseColor("#10B981"));
            detailTitle.setText("⚡ Fördjupad Information: Elpris, Effekt & Fasbelastning (Tibber Liljegränd 12)");
            detailSubtitle.setText("Realtidsmätning från Tibber Pulse HAN-port & IVT Smart Control");
            StringBuilder b = new StringBuilder();
            b.append(String.format(Locale.US,
                    "• Spotpris just nu   : %d öre/kWh   (Dagens Min: %d öre  •  Snitt: %d öre  •  Max: %d öre)\n",
                    s.priceOre, s.priceMinOre, s.priceAvgOre, s.priceMaxOre));
            b.append(String.format(Locale.US,
                    "• Effektuttag i huset: %.2f kW (%d W)   (Dagens medel: %.2f kW  •  Dagens topp: %.2f kW)\n",
                    s.currentPowerW / 1000.0, s.currentPowerW, s.powerAvgKw, s.powerMaxKw));
            b.append("• Fasströmmar (L1-L3): ").append(s.phaseCurrents).append("\n");
            b.append("• Dagens förbrukning : ").append(s.dailyEnergyKwh)
                    .append("   •   Kostnad idag: ").append(s.dailyCostSek).append("\n");
            b.append("• Månaden hittills   : ").append(s.monthlyEnergyKwh)
                    .append("   •   Månadskostnad: ").append(s.monthlyCostSek)
                    .append("   •   IVT-besparing idag: ").append(s.ivtSavingsToday);
            detailBody.setText(b.toString());
        } else if ("klimat".equals(key)) {
            detailAccentBar.setBackgroundColor(Color.parseColor("#38BDF8"));
            detailTitle.setText("🌡️ Fördjupad Information: Husets Temperaturer & Värmekrets (IVT + Verisure)");
            detailSubtitle.setText("Samtliga temperaturgivare inomhus, utomhus samt radiatorernas fram-/returledning");
            StringBuilder b = new StringBuilder();
            b.append("• Inomhus (IVT ref)  : ").append(s.indoorTempC).append(" °C   (Börvärde: ").append(s.indoorTargetC).append(" °C)\n");
            b.append("• Rumstemperaturer   : Entréhall ").append(s.entranceHallTempC)
                    .append(" °C   •   Övre hall ").append(s.upperHallTempC)
                    .append(" °C   •   Undervåning ").append(s.downstairsTempC).append(" °C\n");
            b.append("• Utomhustemperatur  : ").append(s.outdoorTempC).append(" °C\n");
            b.append("• Radiatorer (HC1)   : Framledning ").append(s.supplyTempC)
                    .append(" °C (Börvärde ").append(s.supplyTargetC)
                    .append(" °C)   •   Returledning ").append(s.returnTempC).append(" °C\n");
            b.append("• Värmepump status   : Kompressor ").append(s.compressorState)
                    .append("   •   Virtuella gradminuter: ").append(s.degreeMinutes);
            detailBody.setText(b.toString());
        } else if ("varmvatten".equals(key)) {
            detailAccentBar.setBackgroundColor(Color.parseColor("#FB923C"));
            detailTitle.setText("🚿 Fördjupad Information: Varmvattenberedare & IVT Smart Control");
            detailSubtitle.setText("Varmvattentemperatur, dusch-/badvolym (40 °C) och AI-optimerad värmestrategi");
            StringBuilder b = new StringBuilder();
            b.append("• Varmvattentank     : ").append(s.waterTempC)
                    .append(" °C   (Måltemperatur: ").append(s.waterTargetC)
                    .append(" °C   •   Styrning: ").append(s.waterStatus).append(")\n");
            b.append("• Tillgänglig 40°C   : ").append(s.waterVolume40cL)
                    .append(" liter   —   ").append(s.waterSpaStatus).append("\n");
            b.append("• Värmepump & Ventil : Växelventil = ").append(s.valveMode)
                    .append("   •   Kompressor = ").append(s.compressorState)
                    .append("   •   Drifttid: ").append(s.compressorHours).append("\n");
            b.append("• IVT Dagsstrategi   : ").append(s.ivtAiBriefing);
            detailBody.setText(b.toString());
        } else if ("internet".equals(key)) {
            detailAccentBar.setBackgroundColor(Color.parseColor("#C084FC"));
            detailTitle.setText("🌐 Fördjupad Information: Internet, Wi-Fi & UniFi Cloud Gateway Max");
            detailSubtitle.setText("Realtidstrafik över WAN-fiber, anslutna klienter och routerhälsa (192.168.0.1)");
            StringBuilder b = new StringBuilder();
            b.append(String.format(Locale.US,
                    "• WAN Fibertrafik    : Nedström ↓ %.1f Mbit/s   •   Uppström ↑ %.1f Mbit/s   •   Ping: %d ms\n",
                    s.tvDownMbps, s.tvUpMbps, s.wanLatencyMs));
            b.append("• Anslutna klienter  : ").append(s.wifiClients).append(" trådlösa (Wi-Fi)   •   ")
                    .append(s.lanClients).append(" trådade (LAN)   •   Totalt: ")
                    .append(s.wifiClients + s.lanClients).append(" enheter\n");
            b.append("• UniFi UCG Max      : Status ").append(s.ucgState)
                    .append("   •   CPU: ").append(s.ucgCpuPct)
                    .append("   •   RAM: ").append(s.ucgMemPct)
                    .append("   •   Upptid: ").append(s.ucgUptime).append("\n");
            b.append("• Extern WAN & TV    : WAN IP ").append(s.wanIp)
                    .append(" (").append(s.ispName).append(")   •   TV Ethernet lokal: ")
                    .append(String.format(Locale.US, "↓ %.1f / ↑ %.1f Mbit/s", s.tvLocalDownMbps, s.tvLocalUpMbps));
            detailBody.setText(b.toString());
        } else {
            // Default: Smilla (+ Stickbelysning)
            detailAccentBar.setBackgroundColor(s.isSmillaOut() ? Color.parseColor("#F59E0B") : Color.parseColor("#22C55E"));
            detailTitle.setText("🐱 Fördjupad Information: Smilla & Sure Petcare Kattlucka");
            detailSubtitle.setText("Realtidsstatus, dagens in-/utgångar med klockslag samt batteri & låsstatus");
            StringBuilder b = new StringBuilder();
            b.append("• Aktuell status     : ").append(s.isSmillaOut() ? "UTE NU 🐾" : "INNE HEMMA 🏠")
                    .append("   •   Styrningsläge: ").append(s.smillaOverrideMode).append("\n");
            b.append("• Utetid & Passager  : Ute ").append(s.smillaHoursOut).append(" sedan midnatt   •   ")
                    .append(s.smillaOutCount).append(" utgångar / ").append(s.smillaInCount).append(" ingångar idag\n");
            b.append("• Dagens passagelogg : ").append(s.smillaLogSummary).append("\n");
            b.append("• Sure Petcare Lucka : Batteri ").append(s.flapBattery)
                    .append("   •   Lås: ").append(s.smillaLockState)
                    .append("   •   Hubb: ").append(s.smillaHubState)
                    .append("   •   🧶 Stickbelysning: ").append(s.knittingLightOn ? "PÅ 💡" : "AV 🌙");
            detailBody.setText(b.toString());
        }
    }

    private void showWidgetDetailDialog(String key) {
        SmartHomeData.Snapshot s = (latestHomeSnap != null) ? latestHomeSnap : SmartHomeData.loadCached(this);
        AlertDialog.Builder builder = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);

        if ("smilla".equals(key)) {
            builder.setTitle("🐱 Smilla & Kattlucka — Detaljvy & Styrning");
            String msg = "Aktuell plats: " + (s.isSmillaOut() ? "UTE NU 🐾" : "INNE HEMMA 🏠") + "\n"
                    + "Styrningsläge: " + s.smillaOverrideMode + "\n"
                    + "Utetid idag: " + s.smillaHoursOut + " (" + s.smillaOutCount + " ut / " + s.smillaInCount + " in)\n\n"
                    + "Dagens passager (Sure Petcare):\n" + s.smillaLogSummary + "\n\n"
                    + "Kattlucka batteri: " + s.flapBattery + "  |  Lås: " + s.smillaLockState + "  |  Hubb: " + s.smillaHubState;
            builder.setMessage(msg);
            builder.setPositiveButton("🏠 Tvinga Inne", (d, w) -> setSmillaModeAsync("Tvinga Inne"));
            builder.setNeutralButton("🐾 Tvinga Ute", (d, w) -> setSmillaModeAsync("Tvinga Ute"));
            builder.setNegativeButton("🤖 Automatisk (Kattlucka)", (d, w) -> setSmillaModeAsync("Automatisk (Kattlucka + Sensorer)"));
        } else if ("elpris".equals(key)) {
            builder.setTitle("⚡ Elpris & Effekt — Detaljvy (Tibber Liljegränd 12)");
            String msg = String.format(Locale.US,
                    "Spotpris just nu: %d öre/kWh\n"
                            + "Dagens prisintervall: Min %d öre  •  Snitt %d öre  •  Max %d öre\n\n"
                            + "Aktuell effekt: %.2f kW (%d W)\n"
                            + "Dagens medeleffekt: %.2f kW  •  Dagens topplast: %.2f kW\n"
                            + "Fasströmmar: %s\n\n"
                            + "Förbrukning idag: %s  (Kostnad idag: %s)\n"
                            + "Förbrukning denna månad: %s  (Månadskostnad: %s)\n"
                            + "IVT Smart-styrning besparing idag: %s",
                    s.priceOre, s.priceMinOre, s.priceAvgOre, s.priceMaxOre,
                    s.currentPowerW / 1000.0, s.currentPowerW,
                    s.powerAvgKw, s.powerMaxKw, s.phaseCurrents,
                    s.dailyEnergyKwh, s.dailyCostSek,
                    s.monthlyEnergyKwh, s.monthlyCostSek, s.ivtSavingsToday);
            builder.setMessage(msg);
            builder.setPositiveButton("🔄 Uppdatera nu", (d, w) -> triggerLiveRefresh());
            builder.setNegativeButton("Stäng", null);
        } else if ("klimat".equals(key)) {
            builder.setTitle("🌡️ Temperaturer & Värmekrets — Detaljvy");
            String msg = "Representativ innetemperatur (IVT): " + s.indoorTempC + " °C (Börvärde: " + s.indoorTargetC + " °C)\n"
                    + "Entréhall: " + s.entranceHallTempC + " °C\n"
                    + "Övre hall: " + s.upperHallTempC + " °C\n"
                    + "Undervåning / Timmerflotte: " + s.downstairsTempC + " °C\n"
                    + "Utomhustemperatur: " + s.outdoorTempC + " °C\n\n"
                    + "Radiatorkrets (HC1):\n"
                    + "Framledning: " + s.supplyTempC + " °C (Börvärde: " + s.supplyTargetC + " °C)\n"
                    + "Returledning: " + s.returnTempC + " °C\n"
                    + "Kompressor: " + s.compressorState + "  |  Gradminuter: " + s.degreeMinutes;
            builder.setMessage(msg);
            builder.setPositiveButton("🔄 Uppdatera nu", (d, w) -> triggerLiveRefresh());
            builder.setNegativeButton("Stäng", null);
        } else if ("varmvatten".equals(key)) {
            builder.setTitle("🚿 Varmvattenberedare (IVT) — Detaljvy");
            String msg = "Varmvattentemperatur: " + s.waterTempC + " °C (Målvärde: " + s.waterTargetC + " °C)\n"
                    + "Smart Control-status: " + s.waterStatus + "\n"
                    + "Tillgänglig 40 °C-volym: " + s.waterVolume40cL + " liter\n"
                    + "Badstatus: " + s.waterSpaStatus + "\n\n"
                    + "Växelventil: " + s.valveMode + "  |  Kompressor: " + s.compressorState + "\n"
                    + "Total drifttid: " + s.compressorHours + "\n\n"
                    + "AI Dagsstrategi:\n" + s.ivtAiBriefing;
            builder.setMessage(msg);
            builder.setPositiveButton("🔄 Uppdatera nu", (d, w) -> triggerLiveRefresh());
            builder.setNegativeButton("Stäng", null);
        } else {
            builder.setTitle("🌐 Internet, Wi-Fi & UniFi UCG Max — Detaljvy");
            String msg = String.format(Locale.US,
                    "WAN Nedström (Download): ↓ %.1f Mbit/s\n"
                            + "WAN Uppström (Upload): ↑ %.1f Mbit/s\n"
                            + "Svarstid (Ping): %d ms\n"
                            + "WAN IP: %s (%s)\n\n"
                            + "Trådlösa Wi-Fi-klienter: %d st\n"
                            + "Trådade LAN-klienter: %d st\n"
                            + "Totalt anslutna enheter: %d st\n\n"
                            + "UniFi Cloud Gateway Max (192.168.0.1):\n"
                            + "Status: %s  |  CPU: %s  |  RAM: %s  |  Upptid: %s\n"
                            + "Philips TV (eth0) lokal trafik: ↓ %.1f Mbit/s / ↑ %.1f Mbit/s",
                    s.tvDownMbps, s.tvUpMbps, s.wanLatencyMs, s.wanIp, s.ispName,
                    s.wifiClients, s.lanClients, s.wifiClients + s.lanClients,
                    s.ucgState, s.ucgCpuPct, s.ucgMemPct, s.ucgUptime,
                    s.tvLocalDownMbps, s.tvLocalUpMbps);
            builder.setMessage(msg);
            builder.setPositiveButton("🔄 Uppdatera nu", (d, w) -> triggerLiveRefresh());
            builder.setNegativeButton("Stäng", null);
        }

        builder.show();
    }

    private void setSmillaModeAsync(String option) {
        new Thread(() -> {
            SmartHomeData.setSmillaOverrideMode(getApplicationContext(), config, option);
            SmartHomeData.Snapshot fresh = SmartHomeData.refresh(getApplicationContext(), config);
            handler.post(() -> {
                latestHomeSnap = fresh;
                refreshStatus();
            });
        }).start();
    }
}
