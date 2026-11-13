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
import android.view.KeyEvent;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.Executors;

import io.github.tekno74.tvmaintenance.config.DaemonConfig;
import io.github.tekno74.tvmaintenance.events.WatcherService;
import io.github.tekno74.tvmaintenance.widgets.SmartHomeData;

/**
 * 1-Click TV Remote Toggle Activity for Lisa's "Stickning" (Knitting) Lighting Mode.
 * Appears as its own tile ("Stickbelysning") on the Projectivy Home Screen:
 *  - Pressing OK on the remote immediately toggles input_boolean.stickbelysning in Home Assistant,
 *    displays a warm 1.8-second confirmation card over the current screen, and closes automatically.
 */
public final class KnittingToggleActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView stateTitle;
    private TextView stateSub;
    private GradientDrawable cardBg;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.argb(110, 0, 0, 0));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(72, 48, 72, 48);

        cardBg = new GradientDrawable();
        cardBg.setColor(Color.parseColor("#1E293B"));
        cardBg.setCornerRadius(28f);
        cardBg.setStroke(4, Color.parseColor("#F59E0B"));
        card.setBackground(cardBg);

        stateTitle = new TextView(this);
        stateTitle.setText("🧶 Ljusläge: Stickning...");
        stateTitle.setTextColor(Color.parseColor("#FDE68A"));
        stateTitle.setTextSize(28f);
        stateTitle.setTypeface(Typeface.DEFAULT_BOLD);
        stateTitle.setGravity(Gravity.CENTER);
        card.addView(stateTitle);

        stateSub = new TextView(this);
        stateSub.setText("Växlar belysning i Home Assistant...");
        stateSub.setTextColor(Color.parseColor("#F8FAFC"));
        stateSub.setTextSize(18f);
        stateSub.setGravity(Gravity.CENTER);
        stateSub.setPadding(0, 12, 0, 0);
        card.addView(stateSub);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        root.addView(card, lp);
        setContentView(root);

        DaemonConfig config = new DaemonConfig(this);
        Executors.newSingleThreadExecutor().execute(() -> {
            boolean isOn = SmartHomeData.toggleKnittingLight(getApplicationContext(), config);
            // Notify WatcherService so HUD bar updates immediately
            startForegroundService(new Intent(getApplicationContext(), WatcherService.class));

            handler.post(() -> {
                if (isOn) {
                    cardBg.setStroke(4, Color.parseColor("#FBBF24"));
                    stateTitle.setText("🧶 Stickbelysning: PÅ 💡");
                    stateTitle.setTextColor(Color.parseColor("#FDE047"));
                    stateSub.setText("Full stickbelysning för Lisa är nu aktiverad (" + config.getKnittingEntityId() + ")");
                } else {
                    cardBg.setStroke(4, Color.parseColor("#64748B"));
                    stateTitle.setText("🧶 Stickbelysning: AV 🌙");
                    stateTitle.setTextColor(Color.parseColor("#CBD5E1"));
                    stateSub.setText("Återgår till vanlig TV-/kvällsbelysning");
                }
                handler.postDelayed(this::finish, 1800L);
            });
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
