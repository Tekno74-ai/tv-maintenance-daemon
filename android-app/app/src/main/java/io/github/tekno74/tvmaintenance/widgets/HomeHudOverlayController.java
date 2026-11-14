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
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

import io.github.tekno74.tvmaintenance.R;
import io.github.tekno74.tvmaintenance.config.DaemonConfig;
import io.github.tekno74.tvmaintenance.engine.SafetyInvariants;

/**
 * Module 7d (widgets): Renders the compact "Villa Wahren Live" widget strip at the top-left
 * of the Projectivy Home screen (x = 76..1436, y = 34..154, vertical center y = 94),
 * aligned directly to the left of Projectivy's [Ethernet][Settings][21:40] clock bar (x = 1463..1808, y = 54..134).
 */
public final class HomeHudOverlayController {
    private static final String TAG = "TvMaintHudOverlay";

    private final Context context;
    private final DaemonConfig config;
    private final WindowManager windowManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Style A (Default): Compact Top-Left 5-Card Row with Wahren Coat of Arms (left of 21:40 clock)
    private LinearLayout cardsRow;
    private CardViews cardSmilla;
    private CardViews cardElpris;
    private CardViews cardKlimat;
    private CardViews cardVarmvatten;
    private CardViews cardInternet;
    private boolean cardsAdded = false;

    // Style B (Optional single-line pill mode)
    private LinearLayout topBar;
    private TextView pillSmilla;
    private TextView pillElpris;
    private TextView pillKlimat;
    private TextView pillVarmvatten;
    private TextView pillInternet;
    private boolean topBarAdded = false;

    private String lastKnownForegroundPkg = "com.spocky.projengmenu";
    private String lastKnownForegroundClass = "";

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

                updateForegroundActivity();
                String launcherPkg = SafetyInvariants.extractLauncherPackage(config.getDefaultLauncher());
                boolean isLauncherPkg = lastKnownForegroundPkg.equals(launcherPkg)
                        || "com.spocky.projengmenu".equals(lastKnownForegroundPkg)
                        || "com.gathin.tvhome".equals(lastKnownForegroundPkg);
                boolean isSidePanel = lastKnownForegroundClass != null
                        && lastKnownForegroundClass.toLowerCase(Locale.ROOT).contains("sidepanel");

                if (!isLauncherPkg || isSidePanel) {
                    hideOverlay();
                    return;
                }

                // 1. Compact 5-Card Dashboard Row (left of the 21:40 clock at the same height)
                if (config.isHomeChannelEnabled()) {
                    ensureCardsRowCreated();
                    bindCardsRow(snap);
                    if (cardsRow.getVisibility() != View.VISIBLE) {
                        cardsRow.setVisibility(View.VISIBLE);
                    }
                } else if (cardsAdded && cardsRow != null) {
                    cardsRow.setVisibility(View.GONE);
                }

                // 2. Optional Single-Line Pill Bar
                if (config.isHudOverlayEnabled()) {
                    ensureTopBarCreated();
                    bindTopBar(snap);
                    if (topBar.getVisibility() != View.VISIBLE) {
                        topBar.setVisibility(View.VISIBLE);
                    }
                } else if (topBarAdded && topBar != null) {
                    topBar.setVisibility(View.GONE);
                }
            } catch (Exception e) {
                Log.w(TAG, "Widget overlay update error: " + e.getMessage());
            }
        });
    }

    private void ensureCardsRowCreated() {
        if (cardsAdded && cardsRow != null) return;

        cardsRow = new LinearLayout(context);
        cardsRow.setOrientation(LinearLayout.HORIZONTAL);
        cardsRow.setGravity(Gravity.CENTER_VERTICAL);
        cardsRow.setPadding(4, 4, 4, 4);

        ImageView rowCrest = new ImageView(context);
        rowCrest.setImageResource(R.drawable.wahren_crest);
        rowCrest.setScaleType(ImageView.ScaleType.FIT_CENTER);
        rowCrest.setAdjustViewBounds(true);
        LinearLayout.LayoutParams rowCrestLp = new LinearLayout.LayoutParams(74, 100);
        rowCrestLp.setMargins(0, 0, 12, 0);
        rowCrest.setLayoutParams(rowCrestLp);
        cardsRow.addView(rowCrest);

        cardSmilla = createCardView();
        cardElpris = createCardView();
        cardKlimat = createCardView();
        cardVarmvatten = createCardView();
        cardInternet = createCardView();

        cardsRow.addView(cardSmilla.container);
        cardsRow.addView(cardElpris.container);
        cardsRow.addView(cardKlimat.container);
        cardsRow.addView(cardVarmvatten.container);
        cardsRow.addView(cardInternet.container);

        // Placed at x=76, y=34, width=1360 -> spans [76..1436, 34..154], centered vertically at y=94
        // right to the left of Projectivy's [Ethernet][Settings][21:40] clock at [1463..1808, 54..134]!
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                1360,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 76;
        lp.y = 34;

        windowManager.addView(cardsRow, lp);
        cardsAdded = true;
    }

    private void ensureTopBarCreated() {
        if (topBarAdded && topBar != null) return;

        topBar = new LinearLayout(context);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(20, 10, 24, 10);

        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(Color.argb(220, 15, 23, 42));
        barBg.setCornerRadius(28f);
        barBg.setStroke(2, Color.argb(140, 56, 189, 248));
        topBar.setBackground(barBg);

        ImageView topCrest = new ImageView(context);
        topCrest.setImageResource(R.drawable.wahren_crest);
        topCrest.setScaleType(ImageView.ScaleType.FIT_CENTER);
        topCrest.setAdjustViewBounds(true);
        LinearLayout.LayoutParams crestLp = new LinearLayout.LayoutParams(28, 38);
        crestLp.setMargins(2, 0, 10, 0);
        topCrest.setLayoutParams(crestLp);
        topBar.addView(topCrest);

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
                1280,
                64,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 76;
        lp.y = 62;

        windowManager.addView(topBar, lp);
        topBarAdded = true;
    }

    private CardViews createCardView() {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(14, 9, 14, 9);

        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.argb(225, 15, 23, 42), Color.argb(235, 30, 41, 59)}
        );
        bg.setCornerRadius(13f);
        bg.setStroke(2, Color.argb(150, 51, 65, 85));
        card.setBackground(bg);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(238, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(5, 0, 5, 0);
        card.setLayoutParams(cardLp);

        View accent = new View(context);
        LinearLayout.LayoutParams accentLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 3);
        accentLp.setMargins(0, 0, 0, 5);
        accent.setLayoutParams(accentLp);
        card.addView(accent);

        TextView badge = new TextView(context);
        badge.setTextSize(8.5f);
        badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        badge.setSingleLine(true);
        badge.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(badge);

        TextView value = new TextView(context);
        value.setTextColor(Color.parseColor("#F8FAFC"));
        value.setTextSize(14.5f);
        value.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        value.setPadding(0, 1, 0, 1);
        value.setSingleLine(true);
        value.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(value);

        TextView sub1 = new TextView(context);
        sub1.setTextColor(Color.parseColor("#CBD5E1"));
        sub1.setTextSize(9.5f);
        sub1.setSingleLine(true);
        sub1.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(sub1);

        TextView sub2 = new TextView(context);
        sub2.setTextColor(Color.parseColor("#94A3B8"));
        sub2.setTextSize(9.0f);
        sub2.setPadding(0, 1, 0, 0);
        sub2.setSingleLine(true);
        sub2.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(sub2);

        return new CardViews(card, accent, badge, value, sub1, sub2);
    }

    private TextView createPillView() {
        TextView tv = new TextView(context);
        tv.setTextColor(Color.parseColor("#F8FAFC"));
        tv.setTextSize(12.5f);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setPadding(10, 2, 10, 2);
        return tv;
    }

    private View createDivider() {
        TextView div = new TextView(context);
        div.setText("•");
        div.setTextColor(Color.parseColor("#475569"));
        div.setTextSize(13f);
        div.setSingleLine(true);
        div.setPadding(4, 0, 4, 0);
        return div;
    }

    private void bindTopBar(SmartHomeData.Snapshot s) {
        if (topBar == null) return;

        boolean out = s.isSmillaOut();
        String knitPrefix = s.knittingLightOn ? "🧶 Stickning: PÅ 💡 • " : "";
        pillSmilla.setText(knitPrefix + (out
                ? "🐱 Smilla: UTE (" + s.smillaPassages + ")"
                : "🐱 Smilla: Inne (" + s.smillaPassages + ")"));
        pillSmilla.setTextColor(s.knittingLightOn
                ? Color.parseColor("#FDE047")
                : (out ? Color.parseColor("#FBBF24") : Color.parseColor("#4ADE80")));

        pillElpris.setText(String.format(Locale.US, "⚡ %d öre (%.1f kW)", s.priceOre, s.currentPowerW / 1000.0));
        if (s.priceOre >= 180) {
            pillElpris.setTextColor(Color.parseColor("#FB7185"));
        } else if (s.priceOre >= 120) {
            pillElpris.setTextColor(Color.parseColor("#FBBF24"));
        } else {
            pillElpris.setTextColor(Color.parseColor("#34D399"));
        }

        pillKlimat.setText("🌡️ Inne " + s.indoorTempC + "°C • Ute " + s.outdoorTempC + "°C");
        pillKlimat.setTextColor(Color.parseColor("#38BDF8"));

        pillVarmvatten.setText("🚿 VV " + s.waterTempC + "°C (" + s.waterVolume40cL + " L)");
        pillVarmvatten.setTextColor(Color.parseColor("#FB923C"));

        pillInternet.setText(String.format(Locale.US, "🌐 ↓ %.1f Mbit/s • %d Wi-Fi", s.tvDownMbps, s.wifiClients));
        pillInternet.setTextColor(Color.parseColor("#C084FC"));
    }

    private void bindCardsRow(SmartHomeData.Snapshot s) {
        if (cardsRow == null) return;

        // 1. Smilla (+ Stickbelysning indicator when active)
        boolean out = s.isSmillaOut();
        int smillaColor = s.knittingLightOn ? Color.parseColor("#FDE047")
                : (out ? Color.parseColor("#F59E0B") : Color.parseColor("#22C55E"));
        cardSmilla.accentLine.setBackgroundColor(smillaColor);
        cardSmilla.badge.setText(s.knittingLightOn ? "🧶 STICKNING PÅ • 🐱 SMILLA" : "🐱 SMILLA STATUS");
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

        // 5. Internet & UniFi (Real-time WAN throughput + Wi-Fi clients, no NAS)
        int netColor = Color.parseColor("#C084FC");
        cardInternet.accentLine.setBackgroundColor(netColor);
        cardInternet.badge.setText("🌐 INTERNET & WIFI");
        cardInternet.badge.setTextColor(netColor);
        cardInternet.value.setText(String.format(Locale.US, "↓ %.1f Mbit/s", s.tvDownMbps));
        cardInternet.sub1.setText(String.format(Locale.US, "↑ %.1f Mbit/s • Ping %d ms", s.tvUpMbps, s.wanLatencyMs));
        cardInternet.sub2.setText("Wi-Fi: " + s.wifiClients + " klienter • CPU " + s.ucgCpuPct.replace(" ", ""));
    }

    public void hideOverlay() {
        if (topBarAdded && topBar != null && topBar.getVisibility() != View.GONE) {
            topBar.setVisibility(View.GONE);
        }
        if (cardsAdded && cardsRow != null && cardsRow.getVisibility() != View.GONE) {
            cardsRow.setVisibility(View.GONE);
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
            if (cardsAdded && cardsRow != null && windowManager != null) {
                try {
                    windowManager.removeViewImmediate(cardsRow);
                } catch (Exception ignored) {
                }
                cardsAdded = false;
                cardsRow = null;
            }
        });
    }

    private void updateForegroundActivity() {
        try {
            UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm != null) {
                long now = System.currentTimeMillis();
                UsageEvents events = usm.queryEvents(now - 24 * 3600 * 1000L, now);
                UsageEvents.Event ev = new UsageEvents.Event();
                String latestPkg = null;
                String latestCls = null;
                long latestTs = 0L;
                while (events != null && events.hasNextEvent()) {
                    events.getNextEvent(ev);
                    if (ev.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED && ev.getTimeStamp() >= latestTs) {
                        String pkg = ev.getPackageName();
                        if (pkg != null && !"android".equals(pkg) && !"com.android.systemui".equals(pkg)) {
                            latestPkg = pkg;
                            latestCls = ev.getClassName();
                            latestTs = ev.getTimeStamp();
                        }
                    }
                }
                if (latestPkg != null) {
                    lastKnownForegroundPkg = latestPkg;
                    lastKnownForegroundClass = (latestCls != null) ? latestCls : "";
                }
            }
        } catch (Exception ignored) {
        }
    }
}
