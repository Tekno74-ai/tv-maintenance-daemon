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
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
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
 * Module 7d (widgets): Renders a sleek, responsive, Projectivy-matched "Frosted Glass"
 * lightweight status pill at the top-left of the Home screen (using dp + DisplayMetrics,
 * zero hardcoded pixel dimensions), aligned vertically with Projectivy's clock bar.
 *
 * The full interactive 6-card row is published natively to Projectivy's own layout manager
 * as an Android TV Home Channel ("Villa Wahren Live") via HomeChannelPublisher.
 */
public final class HomeHudOverlayController {
    private static final String TAG = "TvMaintHudOverlay";

    private final Context context;
    private final DaemonConfig config;
    private final WindowManager windowManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private LinearLayout topBar;
    private View smillaDot;
    private TextView pillKnitting;
    private View knittingDivider;
    private TextView pillSmilla;
    private TextView pillElpris;
    private TextView pillKlimat;
    private TextView pillVarmvatten;
    private TextView pillInternet;
    private boolean topBarAdded = false;

    private String lastKnownForegroundPkg = "com.spocky.projengmenu";
    private String lastKnownForegroundClass = "";

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

    private int dp(float value) {
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, dm));
    }

    private void ensureTopBarCreated() {
        if (topBarAdded && topBar != null) return;

        topBar = new LinearLayout(context);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(14), dp(8), dp(18), dp(8));

        // Projectivy-matched soft frosted glass capsule (low-opacity dark glass + subtle light edge)
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(Color.argb(150, 18, 24, 34));
        barBg.setCornerRadius(dp(24));
        barBg.setStroke(dp(1), Color.argb(46, 255, 255, 255));
        topBar.setBackground(barBg);

        // Wahren Coat of Arms integrated neatly inside the left end of the glass pill
        ImageView topCrest = new ImageView(context);
        topCrest.setImageResource(R.drawable.wahren_crest);
        topCrest.setScaleType(ImageView.ScaleType.FIT_CENTER);
        topCrest.setAdjustViewBounds(true);
        LinearLayout.LayoutParams crestLp = new LinearLayout.LayoutParams(dp(18), dp(24));
        crestLp.setMargins(0, 0, dp(10), 0);
        topCrest.setLayoutParams(crestLp);
        topBar.addView(topCrest);

        // Optional Knitting mode badge (only visible when active)
        pillKnitting = createPillView();
        pillKnitting.setText("Stickning PÅ");
        pillKnitting.setTextColor(Color.parseColor("#FDE047"));
        pillKnitting.setVisibility(View.GONE);
        topBar.addView(pillKnitting);

        knittingDivider = createDivider();
        knittingDivider.setVisibility(View.GONE);
        topBar.addView(knittingDivider);

        // Smilla status dot + text
        smillaDot = new View(context);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(7), dp(7));
        dotLp.setMargins(dp(2), 0, dp(6), 0);
        smillaDot.setLayoutParams(dotLp);
        topBar.addView(smillaDot);

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

        // Responsive WRAP_CONTENT pill aligned with Android TV 5% left overscan & Projectivy's clock center
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(38);
        lp.y = dp(28);

        windowManager.addView(topBar, lp);
        topBarAdded = true;
    }

    private TextView createPillView() {
        TextView tv = new TextView(context);
        tv.setTextColor(Color.parseColor("#F1F5F9"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setShadowLayer(2f, 0f, 1f, Color.argb(140, 0, 0, 0));
        tv.setPadding(dp(2), 0, dp(2), 0);
        return tv;
    }

    private View createDivider() {
        View line = new View(context);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(1), dp(13));
        lp.setMargins(dp(10), 0, dp(10), 0);
        line.setLayoutParams(lp);
        line.setBackgroundColor(Color.argb(50, 255, 255, 255));
        return line;
    }

    private void setDotColor(View dot, int color) {
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(color);
        dot.setBackground(circle);
    }

    private void bindTopBar(SmartHomeData.Snapshot s) {
        if (topBar == null) return;

        // Knitting badge
        int knitVis = s.knittingLightOn ? View.VISIBLE : View.GONE;
        pillKnitting.setVisibility(knitVis);
        knittingDivider.setVisibility(knitVis);

        // 1. Smilla (clean dot + short text)
        boolean out = s.isSmillaOut();
        int smillaColor = out ? Color.parseColor("#FBBF24") : Color.parseColor("#4ADE80");
        setDotColor(smillaDot, smillaColor);
        pillSmilla.setText(out
                ? "Smilla Ute (" + s.smillaHoursOut + ")"
                : "Smilla Inne (" + s.smillaHoursOut + ")");
        pillSmilla.setTextColor(Color.parseColor("#F8FAFC"));

        // 2. Electricity price & power
        pillElpris.setText(String.format(Locale.US, "%d öre · %.1f kW", s.priceOre, s.currentPowerW / 1000.0));
        if (s.priceOre >= 180) {
            pillElpris.setTextColor(Color.parseColor("#FDA4AF")); // Soft Rose
        } else if (s.priceOre >= 120) {
            pillElpris.setTextColor(Color.parseColor("#FDE68A")); // Soft Amber
        } else {
            pillElpris.setTextColor(Color.parseColor("#6EE7B7")); // Soft Emerald
        }

        // 3. Climate (Indoor / Outdoor)
        pillKlimat.setText(s.indoorTempC + "° / " + s.outdoorTempC + "°");
        pillKlimat.setTextColor(Color.parseColor("#BAE6FD"));

        // 4. Water Heater
        String waterShort = s.waterTempC;
        try {
            waterShort = String.format(Locale.US, "%.0f", Double.parseDouble(s.waterTempC));
        } catch (Exception ignored) {
        }
        pillVarmvatten.setText("VV " + waterShort + "° · " + s.waterVolume40cL + " L");
        pillVarmvatten.setTextColor(Color.parseColor("#FED7AA"));

        // 5. Internet & Wi-Fi
        pillInternet.setText(String.format(Locale.US, "↓ %.1f Mbit/s · %d Wi-Fi", s.tvDownMbps, s.wifiClients));
        pillInternet.setTextColor(Color.parseColor("#E9D5FF"));
    }

    public void hideOverlay() {
        if (topBarAdded && topBar != null && topBar.getVisibility() != View.GONE) {
            topBar.setVisibility(View.GONE);
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
