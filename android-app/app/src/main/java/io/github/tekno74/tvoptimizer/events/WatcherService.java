package io.github.tekno74.tvoptimizer.events;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.tekno74.tvoptimizer.R;
import io.github.tekno74.tvoptimizer.config.DaemonConfig;
import io.github.tekno74.tvoptimizer.engine.MaintenanceEngine;
import io.github.tekno74.tvoptimizer.engine.SafetyInvariants;
import io.github.tekno74.tvoptimizer.telemetry.HomeAssistantClient;
import io.github.tekno74.tvoptimizer.telemetry.MetricsCollector;

/**
 * Module 4b (events): Event-driven background service that listens to OS broadcasts:
 *  - ACTION_SCREEN_ON:        Cancels pending standby maintenance within the 15s debounce window.
 *  - ACTION_SCREEN_OFF:       Acquires PARTIAL_WAKE_LOCK, waits 15s debounce, and executes dark-mode maintenance.
 *  - ACTION_PACKAGE_ADDED / REPLACED: Queues installed/updated user apps for AOT dex2oat compilation.
 *  - Prolonged Playback:      Executes gentle cache/fstrim live-trim without interrupting video.
 */
public final class WatcherService extends Service {
    private static final String TAG = "TvMaintWatcher";

    public static final String ACTION_BOOT_VERIFY = "io.github.tekno74.tvoptimizer.ACTION_BOOT_VERIFY";
    public static final String ACTION_MANUAL_RUN = "io.github.tekno74.tvoptimizer.ACTION_MANUAL_RUN";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean abortRequested = new AtomicBoolean(false);

    private DaemonConfig config;
    private DirtyPackageQueue dirtyQueue;
    private PowerManager.WakeLock standbyWakeLock;
    private WifiManager.WifiLock standbyWifiLock;
    private Runnable pendingScreenOffTask;
    private Runnable liveTrimMonitorTask;
    private long activePlaybackStartMs = 0L;
    private String activePlaybackPkg = "";
    private boolean receiversRegistered = false;

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_ON.equals(action)) {
                handleScreenOn();
            } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                handleScreenOff();
            }
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            String action = intent.getAction();
            if (!Intent.ACTION_PACKAGE_REPLACED.equals(action) && !Intent.ACTION_PACKAGE_ADDED.equals(action)) return;
            Uri data = intent.getData();
            if (data == null) return;
            String pkg = data.getSchemeSpecificPart();
            if (pkg == null || pkg.isEmpty()) return;
            boolean tracked = config.getCoreApps().contains(pkg)
                    || (config.isAutoDiscoverUserApps()
                        && !SafetyInvariants.isProtectedPackage(pkg, config)
                        && SafetyInvariants.isUserInstalledApp(context, pkg));
            if (tracked) {
                Log.i(TAG, "App installed/updated (" + action + "): " + pkg + " -> queued for AOT speed compilation.");
                dirtyQueue.markDirty(pkg);
            }
        }
    };

    private static final String CHANNEL_ID = "tv_maint_watcher_channel";
    private static final int NOTIF_ID = 1001;

    @Override
    public void onCreate() {
        super.onCreate();
        ensureForeground();
        config = new DaemonConfig(this);
        dirtyQueue = new DirtyPackageQueue(this);
        registerDynamicReceivers();
        startLiveTrimMonitor();
        Log.i(TAG, "WatcherService started (standalone On-Device TV Optimizer)");
    }

    private void startLiveTrimMonitor() {
        stopLiveTrimMonitor();
        liveTrimMonitorTask = new Runnable() {
            @Override
            public void run() {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm == null || !pm.isInteractive()) {
                    return;
                }
                worker.execute(() -> checkLiveTrimNeeded());
                mainHandler.postDelayed(this, 60_000L);
            }
        };
        mainHandler.postDelayed(liveTrimMonitorTask, 60_000L);
    }

    private void stopLiveTrimMonitor() {
        if (liveTrimMonitorTask != null) {
            mainHandler.removeCallbacks(liveTrimMonitorTask);
            liveTrimMonitorTask = null;
        }
    }

    private void ensureForeground() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID, "TV Maintenance Watcher", NotificationManager.IMPORTANCE_MIN);
                channel.setShowBadge(false);
                nm.createNotificationChannel(channel);
            }
            Notification notif = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("TV Optimizer Active")
                    .setContentText("Monitoring standby & app updates")
                    .setSmallIcon(R.drawable.icon)
                    .setOngoing(true)
                    .build();
            startForeground(NOTIF_ID, notif);
        } catch (Exception e) {
            Log.w(TAG, "Could not enter foreground state: " + e.getMessage());
        }
    }

    private void registerDynamicReceivers() {
        if (receiversRegistered) return;
        IntentFilter screenFilter = new IntentFilter();
        screenFilter.addAction(Intent.ACTION_SCREEN_ON);
        screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenReceiver, screenFilter);

        IntentFilter pkgFilter = new IntentFilter();
        pkgFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        pkgFilter.addDataScheme("package");
        registerReceiver(packageReceiver, pkgFilter);

        receiversRegistered = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ensureForeground();
        config.syncFromExternalJsonIfPresent();
        if (intent != null && intent.getAction() != null) {
            String act = intent.getAction();
            if (ACTION_BOOT_VERIFY.equals(act)) {
                worker.execute(() -> MaintenanceEngine.runBootVerification(getApplicationContext(), config));
            } else if (ACTION_MANUAL_RUN.equals(act)) {
                worker.execute(() -> MaintenanceEngine.runStandbyMaintenance(
                        getApplicationContext(), config, dirtyQueue, abortRequested, true));
            }
        }
        return START_STICKY;
    }

    private void handleScreenOn() {
        Log.i(TAG, "ACTION_SCREEN_ON detected: cancelling any pending standby maintenance.");
        abortRequested.set(true);
        activePlaybackStartMs = 0L;
        activePlaybackPkg = "";
        if (pendingScreenOffTask != null) {
            mainHandler.removeCallbacks(pendingScreenOffTask);
            pendingScreenOffTask = null;
        }
        releaseLocks();
        startLiveTrimMonitor();
        worker.execute(() -> {
            MetricsCollector.SystemSnapshot snap = MetricsCollector.collect(getApplicationContext());
            HomeAssistantClient.publishStatus(
                    getApplicationContext(),
                    config,
                    "SCREEN_ON",
                    "TV Awake (active viewing)",
                    snap.memAvailableMb,
                    snap.memAvailableMb,
                    0,
                    0
            );
        });
    }

    private void handleScreenOff() {
        stopLiveTrimMonitor();
        activePlaybackStartMs = 0L;
        activePlaybackPkg = "";
        config.syncFromExternalJsonIfPresent();
        int debounceSec = config.getScreenOffDebounceSeconds();
        Log.i(TAG, "ACTION_SCREEN_OFF detected: acquiring PARTIAL_WAKE_LOCK and waiting " + debounceSec + "s debounce...");

        abortRequested.set(false);
        acquireLocks();

        worker.execute(() -> {
            MetricsCollector.SystemSnapshot snap = MetricsCollector.collect(getApplicationContext());
            HomeAssistantClient.publishStatus(
                    getApplicationContext(),
                    config,
                    "SCREEN_OFF_PENDING",
                    "TV Sleeping (15s debounce before trim)",
                    snap.memAvailableMb,
                    snap.memAvailableMb,
                    0,
                    0
            );
        });

        if (pendingScreenOffTask != null) {
            mainHandler.removeCallbacks(pendingScreenOffTask);
        }

        pendingScreenOffTask = () -> {
            pendingScreenOffTask = null;
            worker.execute(() -> {
                try {
                    if (abortRequested.get()) {
                        Log.i(TAG, "Standby maintenance aborted before start (TV woke up).");
                        return;
                    }
                    MaintenanceEngine.runStandbyMaintenance(
                            getApplicationContext(), config, dirtyQueue, abortRequested, false);
                } finally {
                    releaseLocks();
                }
            });
        };
        mainHandler.postDelayed(pendingScreenOffTask, debounceSec * 1000L);
    }

    private void checkLiveTrimNeeded() {
        try {
            String fg = detectForegroundPackage();
            boolean isTrackedApp = config.getCoreApps().contains(fg)
                    || (config.isAutoDiscoverUserApps() && SafetyInvariants.isUserInstalledApp(this, fg));
            if (fg.isEmpty() || SafetyInvariants.isProtectedPackage(fg, config) || !isTrackedApp) {
                activePlaybackStartMs = 0L;
                activePlaybackPkg = "";
                return;
            }
            long now = System.currentTimeMillis();
            if (!fg.equals(activePlaybackPkg)) {
                activePlaybackPkg = fg;
                activePlaybackStartMs = now;
                return;
            }
            long thresholdMs = Math.max(15, config.getLiveTrimIntervalMinutes()) * 60_000L;
            long lastLiveTrimMs = HomeAssistantClient.getLastLiveTrimEpochMs(this);
            if (activePlaybackStartMs > 0 && (now - activePlaybackStartMs) >= thresholdMs
                    && (now - lastLiveTrimMs) >= 12 * 3_600_000L) {
                activePlaybackStartMs = now;
                final String pkgSnapshot = fg;
                MaintenanceEngine.runLiveTrim(getApplicationContext(), config, pkgSnapshot);
            }
        } catch (Exception e) {
            Log.w(TAG, "Live trim check error: " + e.getMessage());
        }
    }

    private String detectForegroundPackage() {
        try {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return "";
            long now = System.currentTimeMillis();
            UsageEvents events = usm.queryEvents(now - 2 * 3_600_000L, now);
            UsageEvents.Event ev = new UsageEvents.Event();
            String latestPkg = "";
            long latestTs = 0L;
            while (events != null && events.hasNextEvent()) {
                events.getNextEvent(ev);
                if (ev.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED && ev.getTimeStamp() >= latestTs) {
                    String pkg = ev.getPackageName();
                    if (pkg != null && !"android".equals(pkg) && !"com.android.systemui".equals(pkg)) {
                        latestPkg = pkg;
                        latestTs = ev.getTimeStamp();
                    }
                }
            }
            return latestPkg.trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private synchronized void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                if (standbyWakeLock == null) {
                    standbyWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TvMaintenance::StandbyLock");
                    standbyWakeLock.setReferenceCounted(false);
                }
                standbyWakeLock.acquire(3 * 60 * 1000L);
            }
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                if (standbyWifiLock == null) {
                    standbyWifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TvMaintenance::WifiLock");
                    standbyWifiLock.setReferenceCounted(false);
                }
                standbyWifiLock.acquire();
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not acquire WakeLock/WifiLock: " + e.getMessage());
        }
    }

    private synchronized void releaseLocks() {
        try {
            if (standbyWakeLock != null && standbyWakeLock.isHeld()) {
                standbyWakeLock.release();
            }
        } catch (Exception ignored) {
        }
        try {
            if (standbyWifiLock != null && standbyWifiLock.isHeld()) {
                standbyWifiLock.release();
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onDestroy() {
        stopLiveTrimMonitor();
        if (receiversRegistered) {
            try {
                unregisterReceiver(screenReceiver);
                unregisterReceiver(packageReceiver);
            } catch (Exception ignored) {
            }
            receiversRegistered = false;
        }
        releaseLocks();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
