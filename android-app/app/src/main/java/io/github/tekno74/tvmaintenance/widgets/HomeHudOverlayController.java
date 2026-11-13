package io.github.tekno74.tvmaintenance.widgets;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Locale;

import io.github.tekno74.tvmaintenance.config.DaemonConfig;
import io.github.tekno74.tvmaintenance.engine.SafetyInvariants;

/**
 * Module 7d (widgets): Renders BOTH widget styles directly on the Projectivy Home screen
 * (and hides both immediately when any streaming app like Netflix/SVT Play/YouTube is active):
 *  1. Top HUD Pill Bar (compact single-line status bar at the top of the screen)
 *  2. Bottom 5-Card Dashboard Row (5 rich graphical widget cards on the lower Home screen)
 */
public final class HomeHudOverlayController {
    private static final String TAG = "TvMaintHudOverlay";

    private final Context context;
    private final DaemonConfig config;
    private final WindowManager windowManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Style B: Top compact HUD Pill Bar
    private LinearLayout topBar;
    private TextView pillSmilla;
    private TextView pillElpris;
    private TextView pillKlimat;
    private TextView pillVarmvatten;
    private TextView pillInternet;
    private boolean topBarAdded = false;

    // Style A: Bottom 5-Card Graphical Widget Row
    private LinearLayout bottomCardsRow;
    private CardViews cardSmilla;
    private CardViews cardElpris;
    private CardViews cardKlimat;
    private CardViews cardVarmvatten;
    private CardViews cardInternet;
    private boolean bottomCardsAdded = false;

    private String lastKnownForegroundPkg = "";

    private static final class CardViews {
        final LinearLayout container;
        final View accentLine;
        final TextView badge;
        final TextView value;
        final TextView sub1;
        final TextView sub2;

        CardViews(LinearLayout container, View accentLine, TextView badge, TextView value, TextView sub1, TextView sub2) {
            this.container = container;
            this.accentLine = accentLine;
            this.badge = badge;
            this.value = value;
            this.sub1 = sub1;
            this.sub2 = sub2;
        }
    }

    public HomeHudOverlayController(Context context, DaemonConfig config) {
        this.context = context.getApplicationContext();
        this.config = config;
        this.windowManager = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
    }

    public void updateOnMainThread(SmartHomeData.Snapshot snap) {
        mainHandler.post(() -> {
            try {
                if (!Settings.canDrawOverlays(context)) {
                    hideOverlay();
                    return;
                }
                PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                if (pm != null && !pm.isInteractive()) {
                    hideOverlay();
                    return;
                }

                String fgPkg = detectForegroundPackage();
                String launcherPkg = SafetyInvariants.extractLauncherPackage(config.getDefaultLauncher());
                boolean onHomeScreen = fgPkg.equals(launcherPkg)
                        || "com.spocky.projengmenu".equals(fgPkg)
                        || "com.gathin.tvhome".equals(fgPkg);

                if (!onHomeScreen) {
                    hideOverlay();
                    return;
                }

                // 1. Top HUD Bar
                if (config.isHudOverlayEnabled()) {
                    ensureTopBarCreated();
                    bindTopBar(snap);
                    if (topBar.getVisibility() != View.VISIBLE) {
                        topBar.setVisibility(View.VISIBLE);
                    }
                } else if (topBarAdded && topBar != null) {
                    topBar.setVisibility(View.GONE);
                }

                // 2. Bottom 5-Card Widget Row
                if (config.isHomeChannelEnabled()) {
                    ensureBottomCardsCreated();
                    bindBottomCards(snap);
                    if (bottomCardsRow.getVisibility() != View.VISIBLE) {
                        bottomCardsRow.setVisibility(View.VISIBLE);
                    }
                } else if (bottomCardsAdded && bottomCardsRow != null) {
                    bottomCardsRow.setVisibility(View.GONE);
                }
            } catch (Exception e) {
                Log.w(TAG, "Widget overlay update error: " + e.getMessage());
            }
        });
    }

    private void ensureTopBarCreated() {
        if (topBarAdded && topBar != null) return;

        topBar = new LinearLayout(context);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(24, 10, 24, 10);

        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(Color.argb(220, 15, 23, 42));
        barBg.setCornerRadius(28f);
        barBg.setStroke(2, Color.argb(140, 56, 189, 248));
        topBar.setBackground(barBg);

        pillSmilla = createPillView();
        pillElpris = createPillView();
        pillKlimat = createPillView();
        pillVarmvatten = createPillView();
        pillInternet = createPillView();

        topBar.addView(pillSmilla);
        topBar.addView(createDivider());
        topBar.addView(pillElpris);
        topBar.addView(createDivider());
        topBar.addView(pillKlimat);
        topBar.addView(createDivider());
        topBar.addView(pillVarmvatten);
        topBar.addView(createDivider());
        topBar.addView(pillInternet);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = 20;

        windowManager.addView(topBar, lp);
        topBarAdded = true;
    }

    private void ensureBottomCardsCreated() {
        if (bottomCardsAdded && bottomCardsRow != null) return;

        bottomCardsRow = new LinearLayout(context);
        bottomCardsRow.setOrientation(LinearLayout.HORIZONTAL);
        bottomCardsRow.setGravity(Gravity.CENTER);
        bottomCardsRow.setPadding(24, 12, 24, 12);

        cardSmilla = createCardView();
        cardElpris = createCardView();
        cardKlimat = createCardView();
        cardVarmvatten = createCardView();
        cardInternet = createCardView();

        bottomCardsRow.addView(cardSmilla.container);
        bottomCardsRow.addView(cardElpris.container);
        bottomCardsRow.addView(cardKlimat.container);
        bottomCardsRow.addView(cardVarmvatten.container);
        bottomCardsRow.addView(cardInternet.container);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.y = 34;

        windowManager.addView(bottomCardsRow, lp);
        bottomCardsAdded = true;
    }

    private CardViews createCardView() {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(22, 16, 22, 18);

        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.argb(230, 15, 23, 42), Color.argb(235, 30, 41, 59)}
        );
        bg.setCornerRadius(18f);
        bg.setStroke(2, Color.argb(150, 51, 65, 85));
        card.setBackground(bg);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(320, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(10, 0, 10, 0);
        card.setLayoutParams(cardLp);

        View accent = new View(context);
        LinearLayout.LayoutParams accentLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 5);
        accentLp.setMargins(0, 0, 0, 10);
        accent.setLayoutParams(accentLp);
        card.addView(accent);

        TextView badge = new TextView(context);
        badge.setTextSize(10.5f);
        badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        badge.setAllCaps(true);
        card.addView(badge);

        TextView value = new TextView(context);
        value.setTextColor(Color.parseColor("#F8FAFC"));
        value.setTextSize(21f);
        value.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        value.setPadding(0, 4, 0, 4);
        card.addView(value);

        TextView sub1 = new TextView(context);
        sub1.setTextColor(Color.parseColor("#CBD5E1"));
        sub1.setTextSize(11.5f);
        card.addView(sub1);

        TextView sub2 = new TextView(context);
        sub2.setTextColor(Color.parseColor("#94A3B8"));
        sub2.setTextSize(11f);
        sub2.setPadding(0, 2, 0, 0);
        card.addView(sub2);

        return new CardViews(card, accent, badge, value, sub1, sub2);
    }

    private TextView createPillView() {
        TextView tv = new TextView(context);
        tv.setTextColor(Color.parseColor("#F8FAFC"));
        tv.setTextSize(13f);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tv.setPadding(14, 4, 14, 4);
        return tv;
    }

    private View createDivider() {
        TextView div = new TextView(context);
        div.setText("•");
        div.setTextColor(Color.parseColor("#475569"));
        div.setTextSize(14f);
        div.setPadding(4, 0, 4, 0);
        return div;
    }

    private void bindTopBar(SmartHomeData.Snapshot s) {
        if (topBar == null) return;

        boolean out = s.isSmillaOut();
        pillSmilla.setText(out
                ? "🐱 Smilla: UTE (" + s.smillaHoursOut + ")"
                : "🐱 Smilla: Inne (" + s.smillaHoursOut + " ute)");
        pillSmilla.setTextColor(out ? Color.parseColor("#FBBF24") : Color.parseColor("#4ADE80"));

        pillElpris.setText(String.format(Locale.US, "⚡ %d öre (%.1f kW)", s.priceOre, s.currentPowerW / 1000.0));
        if (s.priceOre >= 180) {
            pillElpris.setTextColor(Color.parseColor("#FB7185"));
        } else if (s.priceOre >= 120) {
            pillElpris.setTextColor(Color.parseColor("#FBBF24"));
        } else {
            pillElpris.setTextColor(Color.parseColor("#34D399"));
        }

        pillKlimat.setText("🌡️ Inne " + s.indoorTempC + "°C  Ute " + s.outdoorTempC + "°C");
        pillKlimat.setTextColor(Color.parseColor("#38BDF8"));

        pillVarmvatten.setText("🚿 VV " + s.waterTempC + "°C (" + s.waterVolume40cL + " L)");
        pillVarmvatten.setTextColor(Color.parseColor("#FB923C"));

        pillInternet.setText(String.format(Locale.US, "🌐 ↓ %.1f Mbit/s (%s)", s.tvDownMbps, s.ucgState));
        pillInternet.setTextColor(Color.parseColor("#C084FC"));
    }

    private void bindBottomCards(SmartHomeData.Snapshot s) {
        if (bottomCardsRow == null) return;

        // 1. Smilla
        boolean out = s.isSmillaOut();
        int smillaColor = out ? Color.parseColor("#F59E0B") : Color.parseColor("#22C55E");
        cardSmilla.accentLine.setBackgroundColor(smillaColor);
        cardSmilla.badge.setText("🐱 SMILLA STATUS");
        cardSmilla.badge.setTextColor(smillaColor);
        cardSmilla.value.setText(out ? "UTE NU" : "INNE HEMMA");
        cardSmilla.sub1.setText("Ute idag: " + s.smillaHoursOut + " • " + s.smillaPassages);
        cardSmilla.sub2.setText("Kattlucka batteri: " + s.flapBattery);

        // 2. Elpris & Effekt
        int elColor = s.priceOre >= 180 ? Color.parseColor("#F43F5E")
                : (s.priceOre >= 120 ? Color.parseColor("#F59E0B") : Color.parseColor("#10B981"));
        cardElpris.accentLine.setBackgroundColor(elColor);
        cardElpris.badge.setText("⚡ ELPRIS & EFFEKT");
        cardElpris.badge.setTextColor(elColor);
        cardElpris.value.setText(s.priceOre + " öre/kWh");
        cardElpris.sub1.setText(String.format(Locale.US, "Nu: %.2f kW (%d W) • %s",
                s.currentPowerW / 1000.0, s.currentPowerW, s.dailyCostSek));
        cardElpris.sub2.setText("Min " + s.priceMinOre + " • Snitt " + s.priceAvgOre + " • Max " + s.priceMaxOre);

        // 3. Klimat
        int klimColor = Color.parseColor("#38BDF8");
        cardKlimat.accentLine.setBackgroundColor(klimColor);
        cardKlimat.badge.setText("🌡️ TEMPERATURER");
        cardKlimat.badge.setTextColor(klimColor);
        cardKlimat.value.setText(s.indoorTempC + " °C inne");
        cardKlimat.sub1.setText("Utomhus: " + s.outdoorTempC + " °C");
        cardKlimat.sub2.setText("Övre hall: " + s.upperHallTempC + " °C");

        // 4. Varmvattenberedare
        int vvColor = Color.parseColor("#FB923C");
        cardVarmvatten.accentLine.setBackgroundColor(vvColor);
        cardVarmvatten.badge.setText("🚿 VARMVATTEN (IVT)");
        cardVarmvatten.badge.setTextColor(vvColor);
        cardVarmvatten.value.setText(s.waterTempC + " °C");
        cardVarmvatten.sub1.setText("40°C-volym: " + s.waterVolume40cL + " liter");
        cardVarmvatten.sub2.setText("Mål " + s.waterTargetC + " °C • " + s.waterStatus);

        // 5. Internet & UniFi
        int netColor = Color.parseColor("#C084FC");
        cardInternet.accentLine.setBackgroundColor(netColor);
        cardInternet.badge.setText("🌐 INTERNET & UNIFI");
        cardInternet.badge.setTextColor(netColor);
        cardInternet.value.setText(String.format(Locale.US, "↓ %.1f Mbit/s", s.tvDownMbps));
        cardInternet.sub1.setText("UCG Max: " + s.ucgState + " (CPU " + s.ucgCpuPct + ")");
        cardInternet.sub2.setText("Wi-Fi: " + s.wifiClients + " st • NAS: " + s.nasDownKbps);
    }

    public void hideOverlay() {
        if (topBarAdded && topBar != null && topBar.getVisibility() != View.GONE) {
            topBar.setVisibility(View.GONE);
        }
        if (bottomCardsAdded && bottomCardsRow != null && bottomCardsRow.getVisibility() != View.GONE) {
            bottomCardsRow.setVisibility(View.GONE);
        }
    }

    public void destroy() {
        mainHandler.post(() -> {
            if (topBarAdded && topBar != null && windowManager != null) {
                try {
                    windowManager.removeViewImmediate(topBar);
                } catch (Exception ignored) {
                }
                topBarAdded = false;
                topBar = null;
            }
            if (bottomCardsAdded && bottomCardsRow != null && windowManager != null) {
                try {
                    windowManager.removeViewImmediate(bottomCardsRow);
                } catch (Exception ignored) {
                }
                bottomCardsAdded = false;
                bottomCardsRow = null;
            }
        });
    }

    private String detectForegroundPackage() {
        try {
            UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm != null) {
                long now = System.currentTimeMillis();
                UsageEvents events = usm.queryEvents(now - 30 * 60 * 1000L, now);
                UsageEvents.Event ev = new UsageEvents.Event();
                String latestPkg = null;
                long latestTs = 0L;
                while (events != null && events.hasNextEvent()) {
                    events.getNextEvent(ev);
                    if (ev.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED && ev.getTimeStamp() >= latestTs) {
                        String pkg = ev.getPackageName();
                        if (pkg != null && !"android".equals(pkg) && !"com.android.systemui".equals(pkg)) {
                            latestPkg = pkg;
                            latestTs = ev.getTimeStamp();
                        }
                    }
                }
                if (latestPkg != null) {
                    lastKnownForegroundPkg = latestPkg;
                }
            }
        } catch (Exception ignored) {
        }
        return lastKnownForegroundPkg;
    }
}
