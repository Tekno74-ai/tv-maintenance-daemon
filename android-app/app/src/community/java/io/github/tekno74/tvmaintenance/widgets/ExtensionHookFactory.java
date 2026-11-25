package io.github.tekno74.tvmaintenance.widgets;

import android.content.Context;

import io.github.tekno74.tvmaintenance.config.DaemonConfig;

/**
 * Community edition no-op ExtensionHook factory.
 */
public final class ExtensionHookFactory {
    private static final ExtensionHook NO_OP = new ExtensionHook() {
        @Override
        public void onServiceStart(Context context, DaemonConfig config) {}
        @Override
        public void onScreenOn(Context context, DaemonConfig config) {}
        @Override
        public void onScreenOff(Context context, DaemonConfig config) {}
        @Override
        public void onServiceDestroy() {}
    };

    public static ExtensionHook create(Context context, DaemonConfig config) {
        return NO_OP;
    }
}
