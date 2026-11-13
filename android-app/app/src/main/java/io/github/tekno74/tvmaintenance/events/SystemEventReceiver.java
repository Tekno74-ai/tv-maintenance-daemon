package io.github.tekno74.tvmaintenance.events;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Module 4c (events): Manifest receiver for BOOT_COMPLETED, MY_PACKAGE_REPLACED, and ADB test triggers.
 */
public final class SystemEventReceiver extends BroadcastReceiver {
    private static final String TAG = "TvMaintBootReceiver";
    public static final String ACTION_RUN_NOW = "io.github.tekno74.tvmaintenance.ACTION_RUN_NOW";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        Log.i(TAG, "Received broadcast: " + action);

        Intent svc = new Intent(context, WatcherService.class);
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            svc.setAction(WatcherService.ACTION_BOOT_VERIFY);
        } else if (ACTION_RUN_NOW.equals(action)) {
            svc.setAction(WatcherService.ACTION_MANUAL_RUN);
        }
        try {
            context.startForegroundService(svc);
        } catch (Exception e) {
            Log.w(TAG, "Could not start WatcherService from receiver: " + e.getMessage());
        }
    }
}
