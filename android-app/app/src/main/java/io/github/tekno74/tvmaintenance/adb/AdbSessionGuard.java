package io.github.tekno74.tvmaintenance.adb;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Log;

/**
 * Module 2a (adb): AutoCloseable guard that controls Settings.Global.ADB_ENABLED.
 *
 * - Ensures ADB_ENABLED = 1 for the duration of the loopback maintenance session.
 * - When closeOnExit is true (keep_adb_open = false), guarantees ADB_ENABLED = 0 in close().
 * - When closeOnExit is false (keep_adb_open = true), leaves ADB_ENABLED = 1 on exit.
 */
public final class AdbSessionGuard implements AutoCloseable {
    private static final String TAG = "TvMaintAdbGuard";
    private static final String PERM_WRITE_SECURE = "android.permission.WRITE_SECURE_SETTINGS";

    private final ContentResolver resolver;
    private final boolean closeOnExit;
    private final boolean hasPermission;

    public AdbSessionGuard(Context context, boolean closeOnExit) {
        Context appCtx = context.getApplicationContext();
        this.resolver = appCtx.getContentResolver();
        this.closeOnExit = closeOnExit;
        this.hasPermission = appCtx.checkSelfPermission(PERM_WRITE_SECURE) == PackageManager.PERMISSION_GRANTED;

        if (hasPermission) {
            try {
                int current = Settings.Global.getInt(resolver, Settings.Global.ADB_ENABLED, 0);
                if (current != 1) {
                    Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 1);
                    Log.i(TAG, "Enabled loopback ADB for maintenance session (closeOnExit=" + closeOnExit + ")");
                    Thread.sleep(1200);
                }
            } catch (Exception e) {
                Log.w(TAG, "Could not toggle ADB_ENABLED on open: " + e.getMessage());
            }
        } else {
            Log.w(TAG, "WRITE_SECURE_SETTINGS not yet granted; relying on existing ADB state.");
        }
    }

    public static boolean hasWriteSecureSettings(Context context) {
        return context.checkSelfPermission(PERM_WRITE_SECURE) == PackageManager.PERMISSION_GRANTED;
    }

    public static void setAdbEnabledDirect(Context context, boolean enabled) {
        if (!hasWriteSecureSettings(context)) return;
        try {
            Settings.Global.putInt(
                    context.getApplicationContext().getContentResolver(),
                    Settings.Global.ADB_ENABLED,
                    enabled ? 1 : 0
            );
            Log.i(TAG, "Directly set ADB_ENABLED=" + (enabled ? 1 : 0));
        } catch (Exception e) {
            Log.w(TAG, "Could not directly set ADB_ENABLED: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        if (!closeOnExit) {
            return;
        }
        if (hasPermission) {
            try {
                Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 0);
                Log.i(TAG, "Closed ADB_ENABLED on session exit (port 5555 is now closed).");
            } catch (Exception e) {
                Log.e(TAG, "Failed to disable ADB_ENABLED on close: " + e.getMessage());
            }
        }
    }
}
