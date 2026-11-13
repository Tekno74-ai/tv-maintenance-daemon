package io.github.tekno74.tvmaintenance.events;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
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

import io.github.tekno74.tvmaintenance.R;
import io.github.tekno74.tvmaintenance.config.DaemonConfig;
import io.github.tekno74.tvmaintenance.engine.MaintenanceEngine;
import io.github.tekno74.tvmaintenance.telemetry.HomeAssistantClient;
import io.github.tekno74.tvmaintenance.telemetry.MetricsCollector;
import io.github.tekno74.tvmaintenance.widgets.HomeChannelPublisher;
import io.github.tekno74.tvmaintenance.widgets.HomeHudOverlayController;
import io.github.tekno74.tvmaintenance.widgets.SmartHomeData;

/**
 * Module 4b (events): Event-driven background service that listens to OS broadcasts:
 *  - ACTION_SCREEN_ON:        Cancels pending standby maintenance within the 15s regret window (UC2).
 *  - ACTION_SCREEN_OFF:       Acquires PARTIAL_WAKE_LOCK, waits 15s debounce, and executes dark-mode maintenance (UC1).
 *  - ACTION_PACKAGE_REPLACED: Queues updated streaming apps for AOT dex2oat compilation (UC3).
 */
public final class WatcherService extends Service {
    private static final String TAG = "TvMaintWatcher";

    public static final String ACTION_BOOT_VERIFY = "io.github.tekno74.tvmaintenance.ACTION_BOOT_VERIFY";
    public static final String ACTION_MANUAL_RUN = "io.github.tekno74.tvmaintenance.ACTION_MANUAL_RUN";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService widgetWorker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean abortRequested = new AtomicBoolean(false);

    private DaemonConfig config;
    private DirtyPackageQueue dirtyQueue;
    private HomeHudOverlayController hudOverlay;
    private PowerManager.WakeLock standbyWakeLock;
    private WifiManager.WifiLock standbyWifiLock;
    private Runnable pendingScreenOffTask;
    private Runnable widgetTickTask;
    private long lastChannelSyncMs = 0L;
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
            if (intent == null || !Intent.ACTION_PACKAGE_REPLACED.equals(intent.getAction())) return;
            Uri data = intent.getData();
            if (data == null) return;
            String pkg = data.getSchemeSpecificPart();
            if (pkg != null && config.getCoreApps().contains(pkg)) {
                Log.i(TAG, "Core app updated by Play Store: " + pkg + " -> queued for AOT speed compilation.");
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
        hudOverlay = new HomeHudOverlayController(this, config);
        registerDynamicReceivers();
        startWidgetLoop();
        Log.i(TAG, "WatcherService started in mode=" + config.getMode());
    }

    private void startWidgetLoop() {
        stopWidgetLoop();
        widgetTickTask = new Runnable() {
            @Override
            public void run() {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null && !pm.isInteractive()) {
                    hudOverlay.hideOverlay();
                    return;
                }
                widgetWorker.execute(() -> {
                    SmartHomeData.Snapshot snap = SmartHomeData.refresh(getApplicationContext(), config);
                    hudOverlay.updateOnMainThread(snap);
                    long now = System.currentTimeMillis();
                    if (config.isHomeChannelEnabled() && (now - lastChannelSyncMs) >= 60_000L) {
                        lastChannelSyncMs = now;
                        HomeChannelPublisher.syncChannelAndCards(getApplicationContext(), snap);
                    }
                });
                mainHandler.postDelayed(this, 3000L);
            }
        };
        mainHandler.post(widgetTickTask);
    }

    private void stopWidgetLoop() {
        if (widgetTickTask != null) {
            mainHandler.removeCallbacks(widgetTickTask);
            widgetTickTask = null;
        }
        if (hudOverlay != null) {
            hudOverlay.hideOverlay();
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

        IntentFilter pkgFilter = new IntentFilter(Intent.ACTION_PACKAGE_REPLACED);
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
        if (pendingScreenOffTask != null) {
            mainHandler.removeCallbacks(pendingScreenOffTask);
            pendingScreenOffTask = null;
        }
        releaseLocks();
        startWidgetLoop();
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
        stopWidgetLoop();
        config.syncFromExternalJsonIfPresent();
        int debounceSec = config.getScreenOffDebounceSeconds();
        Log.i(TAG, "ACTION_SCREEN_OFF detected: acquiring PARTIAL_WAKE_LOCK and waiting " + debounceSec + "s debounce...");

        abortRequested.set(false);
        acquireLocks();

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

    private synchronized void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                if (standbyWakeLock == null) {
                    standbyWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TvMaintenance::StandbyLock");
                    standbyWakeLock.setReferenceCounted(false);
                }
                // Maximum 3 minutes safety timeout so a lock can never drain or block sleep forever
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
        stopWidgetLoop();
        if (hudOverlay != null) {
            hudOverlay.destroy();
        }
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
