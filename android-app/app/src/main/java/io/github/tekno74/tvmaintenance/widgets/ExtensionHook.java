package io.github.tekno74.tvmaintenance.widgets;

import android.content.Context;

import io.github.tekno74.tvmaintenance.config.DaemonConfig;

/**
 * Optional lifecycle hook for flavor-specific UI extensions (e.g., custom smart-home widgets).
 * The default community flavor provides a zero-overhead no-op implementation.
 */
public interface ExtensionHook {
    void onServiceStart(Context context, DaemonConfig config);
    void onScreenOn(Context context, DaemonConfig config);
    void onScreenOff(Context context, DaemonConfig config);
    void onServiceDestroy();
}
