package io.github.tekno74.tvmaintenance.engine;

import android.content.Context;
import android.util.Log;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.tekno74.tvmaintenance.adb.AdbSessionGuard;
import io.github.tekno74.tvmaintenance.adb.LocalAdbClient;
import io.github.tekno74.tvmaintenance.config.DaemonConfig;
import io.github.tekno74.tvmaintenance.events.DirtyPackageQueue;
import io.github.tekno74.tvmaintenance.telemetry.HomeAssistantClient;
import io.github.tekno74.tvmaintenance.telemetry.MetricsCollector;

/**
 * Module 5b (engine): Orchestrates dark-mode standby maintenance, selective background app stopping,
 * cache trimming, and on-demand AOT speed compilation via 127.0.0.1:5555.
 */
public final class MaintenanceEngine {
    private static final String TAG = "TvMaintEngine";

    public static synchronized void runBootVerification(Context context, DaemonConfig config) {
        Log.i(TAG, "Running cold-boot verification (mode=" + config.getMode() + ")...");
        MetricsCollector.SystemSnapshot before = MetricsCollector.collect(context);
        SafetyInvariants.enforceDirectSettings(context, config);

        String statusMsg = "Boot verified (Direct Settings OK)";
        try (AdbSessionGuard ignored = new AdbSessionGuard(context, config.isStandaloneMode())) {
            LocalAdbClient adb = new LocalAdbClient(context, config.getAdbPort());
            // Verify default launcher if configured
            String launcher = config.getDefaultLauncher();
            if (launcher != null && launcher.contains("/")) {
                adb.runShellCommand("cmd package set-home-activity --user 0 " + launcher, 10000);
            }
            statusMsg = "Boot verified (Loopback ADB + Launcher OK)";
        } catch (Exception e) {
            Log.w(TAG, "Loopback ADB check during boot skipped/failed: " + e.getMessage());
            statusMsg = "Boot verified (Direct Settings OK; ADB: " + e.getMessage() + ")";
        }

        MetricsCollector.SystemSnapshot after = MetricsCollector.collect(context);
        HomeAssistantClient.publishStatus(
                context, config, "BOOT_COMPLETED", statusMsg,
                before.memAvailableMb, after.memAvailableMb, 0, 0
        );
    }

    public static synchronized void runStandbyMaintenance(
            Context context,
            DaemonConfig config,
            DirtyPackageQueue dirtyQueue,
            AtomicBoolean abortSignal,
            boolean forceManual
    ) {
        long now = System.currentTimeMillis();
        long lastRun = HomeAssistantClient.getLastRunEpochMs(context);
        long minCooldownMs = config.getMinMinutesBetweenStandbyRuns() * 60_000L;

        if (!forceManual && !dirtyQueue.hasDirtyPackages() && (now - lastRun) < minCooldownMs) {
            Log.i(TAG, "Skipping standby maintenance (cooldown active and 0 dirty packages).");
            return;
        }

        MetricsCollector.SystemSnapshot before = MetricsCollector.collect(context);
        Log.i(TAG, "Starting standby maintenance (mode=" + config.getMode()
                + ", force=" + forceManual + ", RAM before=" + before.memAvailableMb + " MB)...");

        // Step 1: Enforce direct Android settings (stay_on=0, 0.5x animations, DNS/NTP)
        SafetyInvariants.enforceDirectSettings(context, config);

        int stoppedCount = 0;
        int compiledCount = 0;
        String statusMsg;

        // Step 2: Open ephemeral loopback ADB session (closes port 5555 automatically in standalone mode)
        try (AdbSessionGuard ignored = new AdbSessionGuard(context, config.isStandaloneMode())) {
            LocalAdbClient adb = new LocalAdbClient(context, config.getAdbPort());

            // 2a. Enforce disabled bloatware / DVB-tuner lock (Invariant 4)
            for (String disabledPkg : config.getDisabledPackages()) {
                if (abortSignal.get()) break;
                if (!SafetyInvariants.isProtectedPackage(disabledPkg, config)) {
                    adb.runShellCommand("pm disable-user --user 0 " + disabledPkg + " 2>/dev/null", 8000);
                }
            }

            // 2b. Selectively force-stop background streaming apps (only if screen is OFF or manual test)
            if (config.isForceStopBackgroundApps() && !before.interactive) {
                StringBuilder stopBatch = new StringBuilder();
                for (String pkg : config.getCoreApps()) {
                    if (!SafetyInvariants.isProtectedPackage(pkg, config)) {
                        stopBatch.append("am force-stop ").append(pkg).append("; ");
                        stoppedCount++;
                    }
                }
                if (stopBatch.length() > 0 && !abortSignal.get()) {
                    adb.runShellCommand(stopBatch.toString(), 15000);
                }
            }

            // 2c. Trim caches and fstrim flash storage
            if (!abortSignal.get()) {
                adb.runShellCommand("pm trim-caches 4096M; sm fstrim 2>/dev/null", 20000);
            }

            // 2d. Check which core_apps need AOT speed compilation (dirty queue + uncompiled inspection)
            Set<String> toCompile = new LinkedHashSet<>(dirtyQueue.getDirtyPackages());
            List<String> coreApps = config.getCoreApps();
            for (String pkg : coreApps) {
                if (abortSignal.get()) break;
                if (toCompile.contains(pkg)) continue;
                String dump = adb.runShellCommand(
                        "dumpsys package " + pkg + " | grep -E 'status=speed|Unable to find package'", 8000);
                if (dump.isEmpty()) {
                    // Package is installed but not compiled with status=speed
                    String exists = adb.runShellCommand("pm path " + pkg, 5000);
                    if (exists.startsWith("package:")) {
                        toCompile.add(pkg);
                    }
                }
            }

            // 2e. Compile dirty/uncompiled packages with cmd package compile -m speed
            for (String pkg : toCompile) {
                if (abortSignal.get()) {
                    Log.i(TAG, "Aborting AOT compilation early because TV woke up.");
                    break;
                }
                Log.i(TAG, "Running AOT speed compilation for: " + pkg);
                String res = adb.runShellCommand("cmd package compile -m speed -f " + pkg, 120000);
                if (res.contains("Success")) {
                    compiledCount++;
                    dirtyQueue.markClean(pkg);
                }
            }

            statusMsg = "OK (Trimmed caches, stopped=" + stoppedCount + ", AOT compiled=" + compiledCount + ")";
        } catch (Exception e) {
            Log.e(TAG, "Standby maintenance error: " + e.getMessage());
            statusMsg = "Error: " + e.getMessage();
        }

        MetricsCollector.SystemSnapshot after = MetricsCollector.collect(context);
        Log.i(TAG, "Finished standby maintenance: " + statusMsg + " | RAM after=" + after.memAvailableMb + " MB");

        HomeAssistantClient.publishStatus(
                context,
                config,
                forceManual ? "MANUAL_RUN" : "SCREEN_OFF",
                statusMsg,
                before.memAvailableMb,
                after.memAvailableMb,
                stoppedCount,
                compiledCount
        );
    }
}
