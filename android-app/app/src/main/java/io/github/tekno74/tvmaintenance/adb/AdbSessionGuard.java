package io.github.tekno74.tvmaintenance.adb;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Log;

/**
 * Module 2a (adb): AutoCloseable guard that controls Settings.Global.ADB_ENABLED.
 *
 * - In "parallel" validation mode (Phase 1): Ensures ADB_ENABLED = 1 and leaves it enabled on close()
 *   so the external Synology NAS daemon retains uninterrupted connectivity.
 * - In "standalone" production mode (Phase 2): Opens ADB_ENABLED = 1 for the duration of the maintenance
 *   window and guarantees ADB_ENABLED = 0 in close(), keeping port 5555 closed 99.9% of the day.
 */
public final class AdbSessionGuard implements AutoCloseable {
    private static final String TAG = "TvMaintAdbGuard";
    private static final String PERM_WRITE_SECURE = "android.permission.WRITE_SECURE_SETTINGS";

    private final ContentResolver resolver;
    private final boolean standaloneMode;
    private final boolean hasPermission;

    public AdbSessionGuard(Context context, boolean standaloneMode) {
        Context appCtx = context.getApplicationContext();
        this.resolver = appCtx.getContentResolver();
        this.standaloneMode = standaloneMode;
        this.hasPermission = appCtx.checkSelfPermission(PERM_WRITE_SECURE) == PackageManager.PERMISSION_GRANTED;

        if (hasPermission) {
            try {
                int current = Settings.Global.getInt(resolver, Settings.Global.ADB_ENABLED, 0);
                if (current != 1) {
                    Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 1);
                    Log.i(TAG, "Enabled loopback ADB for maintenance session (standalone=" + standaloneMode + ")");
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

    @Override
    public void close() {
        if (!standaloneMode) {
            // Phase 1 (Parallel validation with NAS): Never disable ADB on exit.
            return;
        }
        if (hasPermission) {
            try {
                Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 0);
                Log.i(TAG, "Standalone mode: Closed ADB_ENABLED (port 5555 is now closed).");
            } catch (Exception e) {
                Log.e(TAG, "Failed to disable ADB_ENABLED on close: " + e.getMessage());
            }
        }
    }
}
