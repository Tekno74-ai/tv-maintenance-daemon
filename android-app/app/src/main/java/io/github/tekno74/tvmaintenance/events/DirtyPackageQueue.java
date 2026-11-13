package io.github.tekno74.tvmaintenance.events;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Module 4a (events): Persistent queue of core streaming apps that were updated by Google Play
 * (ACTION_PACKAGE_REPLACED) and need AOT speed-compilation on the next SCREEN_OFF window.
 */
public final class DirtyPackageQueue {
    private static final String PREFS_NAME = "tv_maintenance_dirty_pkgs";
    private static final String KEY_DIRTY = "dirty_set";

    private final SharedPreferences prefs;

    public DirtyPackageQueue(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public synchronized void markDirty(String packageName) {
        if (packageName == null || packageName.isEmpty()) return;
        Set<String> current = new LinkedHashSet<>(prefs.getStringSet(KEY_DIRTY, Collections.emptySet()));
        current.add(packageName);
        prefs.edit().putStringSet(KEY_DIRTY, current).apply();
    }

    public synchronized Set<String> getDirtyPackages() {
        return new LinkedHashSet<>(prefs.getStringSet(KEY_DIRTY, Collections.emptySet()));
    }

    public synchronized void markClean(String packageName) {
        Set<String> current = new LinkedHashSet<>(prefs.getStringSet(KEY_DIRTY, Collections.emptySet()));
        if (current.remove(packageName)) {
            prefs.edit().putStringSet(KEY_DIRTY, current).apply();
        }
    }

    public synchronized boolean hasDirtyPackages() {
        return !getDirtyPackages().isEmpty();
    }
}
