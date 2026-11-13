package io.github.tekno74.tvmaintenance.engine;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

import io.github.tekno74.tvmaintenance.adb.AdbSessionGuard;
import io.github.tekno74.tvmaintenance.config.DaemonConfig;

/**
 * Module 5a (engine): Hardcoded runtime invariants protecting the TV's dark standby state,
 * remote control accessibility helpers, and UI responsiveness.
 */
public final class SafetyInvariants {
    private static final String TAG = "TvMaintSafety";
    private static final String SELF_PACKAGE = "io.github.tekno74.tvmaintenance";

    /**
     * Enforces settings directly via ContentResolver using WRITE_SECURE_SETTINGS (zero ADB needed).
     */
    public static void enforceDirectSettings(Context context, DaemonConfig config) {
        if (!AdbSessionGuard.hasWriteSecureSettings(context)) {
            return;
        }
        ContentResolver resolver = context.getContentResolver();
        try {
            // Invariant 2: NEVER allow stay_on_while_plugged_in != 0 (prevents screen waking on ADB connect)
            int stayOn = Settings.Global.getInt(resolver, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0);
            if (stayOn != 0) {
                Settings.Global.putInt(resolver, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0);
                Log.i(TAG, "Reset stay_on_while_plugged_in to 0 (dark standby invariant).");
            }

            // UI Animation scales (0.5x)
            float scale = config.getAnimationScale();
            Settings.Global.putFloat(resolver, Settings.Global.WINDOW_ANIMATION_SCALE, scale);
            Settings.Global.putFloat(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, scale);
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale);

            // Cold-boot DNS & NTP resilience
            Settings.Global.putString(resolver, "ntp_server", "time.google.com");
            Settings.Global.putString(resolver, "private_dns_mode", "off");

            // Invariant 1: Accessibility services are NEVER touched unless explicitly opted in,
            // and even then the launcher's own helpers are strictly preserved.
            if (config.isDisableAccessibilityServicesOptIn()) {
                preserveLauncherAccessibilityOnly(resolver, config.getDefaultLauncher());
            }
        } catch (Exception e) {
            Log.w(TAG, "Error enforcing direct settings: " + e.getMessage());
        }
    }

    private static void preserveLauncherAccessibilityOnly(ContentResolver resolver, String defaultLauncher) {
        String current = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (current == null || current.trim().isEmpty() || "null".equalsIgnoreCase(current.trim())) {
            return;
        }
        String launcherPkg = extractLauncherPackage(defaultLauncher);
        String[] services = current.split(":");
        List<String> kept = new ArrayList<>();
        for (String svc : services) {
            String trimmed = svc.trim();
            if (!trimmed.isEmpty() && !launcherPkg.isEmpty() && trimmed.startsWith(launcherPkg + "/")) {
                kept.add(trimmed);
            }
        }
        String desired = String.join(":", kept);
        if (!desired.equals(current.trim())) {
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, desired);
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, kept.isEmpty() ? 0 : 1);
            Log.i(TAG, "Filtered non-launcher accessibility services; preserved: " + desired);
        }
    }

    public static String extractLauncherPackage(String defaultLauncher) {
        if (defaultLauncher == null || defaultLauncher.trim().isEmpty()) {
            return "";
        }
        String trimmed = defaultLauncher.trim();
        int slash = trimmed.indexOf('/');
        return (slash > 0) ? trimmed.substring(0, slash) : trimmed;
    }

    public static boolean isProtectedPackage(String pkg, DaemonConfig config) {
        if (pkg == null || pkg.isEmpty()) return true;
        if (SELF_PACKAGE.equals(pkg)) return true;
        String launcherPkg = extractLauncherPackage(config.getDefaultLauncher());
        if (!launcherPkg.isEmpty() && launcherPkg.equals(pkg)) return true;
        return "com.spocky.projengmenu".equals(pkg) || "com.gathin.tvhome".equals(pkg);
    }
}
