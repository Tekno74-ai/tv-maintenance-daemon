package io.github.tekno74.tvoptimizer.engine;

import android.content.Context;
import android.util.Log;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import io.github.tekno74.tvoptimizer.adb.AdbSessionGuard;
import io.github.tekno74.tvoptimizer.adb.LocalAdbClient;
import io.github.tekno74.tvoptimizer.config.DaemonConfig;
import io.github.tekno74.tvoptimizer.events.DirtyPackageQueue;
import io.github.tekno74.tvoptimizer.telemetry.HomeAssistantClient;
import io.github.tekno74.tvoptimizer.telemetry.MetricsCollector;

/**
 * Module 5b (engine): Orchestrates dark-mode standby maintenance, gentle live trimming (UC5),
 * batched background app stopping, cache trimming, ANR inspection, and on-demand AOT speed compilation.
 */
public final class MaintenanceEngine {
    private static final String TAG = "TvMaintEngine";
    private static final Pattern PKG_PATTERN = Pattern.compile("^[a-zA-Z0-9_.]+$");

    public static synchronized void runBootVerification(Context context, DaemonConfig config) {
        Log.i(TAG, "Running cold-boot verification (mode=" + config.getMode() + ")...");
        MetricsCollector.SystemSnapshot before = MetricsCollector.collect(context);
        SafetyInvariants.enforceDirectSettings(context, config);

        String statusMsg = "Boot verified (Direct Settings OK)";
        try (AdbSessionGuard ignored = new AdbSessionGuard(context, config.shouldCloseAdbAfterRun())) {
            LocalAdbClient adb = new LocalAdbClient(context, config.getAdbPort());
            StringBuilder cmd = new StringBuilder();
            String launcher = config.getDefaultLauncher();
            if (launcher != null && launcher.contains("/")) {
                cmd.append("cmd package set-home-activity --user 0 ").append(launcher).append("; ");
            }
            enforceDisabledPackagesBatched(adb, config, cmd);
            if (cmd.length() > 0) {
                adb.runShellCommand(cmd.toString(), 12000);
            }
            statusMsg = "Boot verified (Loopback ADB + Launcher + Bloat Shield OK)";
        } catch (Exception e) {
            Log.w(TAG, "Loopback ADB check during boot skipped/failed: " + e.getMessage());
            statusMsg = "Boot verified (Direct Settings OK; ADB: " + e.getMessage() + ")";
        }

        MetricsCollector.SystemSnapshot after = MetricsCollector.collect(context);
        HomeAssistantClient.publishStatus(
                context, config, "BOOT_COMPLETED", statusMsg,
                before.memAvailableMb, after.memAvailableMb, 0, 0, 0
        );
    }

    /**
     * UC5: Gentle background cache trim + fstrim during prolonged active playback (>60 min).
     * Never force-stops apps and never runs CPU-heavy AOT compilation while the user is watching TV.
     */
    public static synchronized void runLiveTrim(
            Context context,
            DaemonConfig config,
            String foregroundPkg
    ) {
        MetricsCollector.SystemSnapshot before = MetricsCollector.collect(context);
        Log.i(TAG, "Running gentle UC5 Live Trim (foreground=" + foregroundPkg
                + ", RAM before=" + before.memAvailableMb + " MB)...");
        SafetyInvariants.enforceDirectSettings(context, config);

        String statusMsg;
        try (AdbSessionGuard ignored = new AdbSessionGuard(context, config.shouldCloseAdbAfterRun())) {
            LocalAdbClient adb = new LocalAdbClient(context, config.getAdbPort());
            adb.runShellCommand("pm trim-caches 4096M; sm fstrim 2>/dev/null", 15000);
            MetricsCollector.SystemSnapshot after = MetricsCollector.collect(context);
            long freed = Math.max(0L, after.memAvailableMb - before.memAvailableMb);
            statusMsg = "Live-trim OK (freed=" + freed + " MB, fg=" + foregroundPkg + ")";
            Log.i(TAG, "Finished UC5 Live Trim: " + statusMsg + " | RAM after=" + after.memAvailableMb + " MB");
            HomeAssistantClient.publishStatus(
                    context, config, "LIVE_TRIM", statusMsg,
                    before.memAvailableMb, after.memAvailableMb, 0, 0, 0
            );
        } catch (Exception e) {
            Log.w(TAG, "UC5 Live Trim failed: " + e.getMessage());
        }
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
            MetricsCollector.SystemSnapshot snap = MetricsCollector.collect(context);
            HomeAssistantClient.publishStatus(
                    context, config, "COOLDOWN_SKIP",
                    "Standby OK (Cooldown active, 0 dirty packages)",
                    snap.memAvailableMb, snap.memAvailableMb, 0, 0, 0
            );
            return;
        }

        MetricsCollector.SystemSnapshot before = MetricsCollector.collect(context);
        Log.i(TAG, "Starting standby maintenance (mode=" + config.getMode()
                + ", force=" + forceManual + ", RAM before=" + before.memAvailableMb + " MB)...");

        // Step 1: Enforce direct Android settings (stay_on=0, 0.5x animations, DNS/NTP)
        SafetyInvariants.enforceDirectSettings(context, config);

        int stoppedCount = 0;
        int compiledCount = 0;
        int freshAnrs = 0;
        String statusMsg;

        // Step 2: Open ephemeral loopback ADB session (closes port 5555 automatically in standalone mode)
        try (AdbSessionGuard ignored = new AdbSessionGuard(context, config.shouldCloseAdbAfterRun())) {
            LocalAdbClient adb = new LocalAdbClient(context, config.getAdbPort());
            Set<String> targetApps = collectTargetApps(adb, config);

            // 2a. Batch bloatware verification (using pm list packages -d -u), selective force-stop,
            // cache trim, fstrim, and recent ANR count in minimal shell roundtrips.
            StringBuilder batchCmd = new StringBuilder();
            enforceDisabledPackagesBatched(adb, config, batchCmd);

            if (config.isForceStopBackgroundApps() && !before.interactive) {
                for (String pkg : targetApps) {
                    if (isValidPackage(pkg) && !SafetyInvariants.isProtectedPackage(pkg, config)) {
                        batchCmd.append("am force-stop ").append(pkg).append("; ");
                        stoppedCount++;
                    }
                }
            }

            if (!abortSignal.get()) {
                batchCmd.append("pm trim-caches 4096M >/dev/null 2>&1; sm fstrim 2>/dev/null; ");
                batchCmd.append("echo ANR_COUNT=$(find /data/anr -type f -mtime -1 2>/dev/null | wc -l)");
                String batchOut = adb.runShellCommand(batchCmd.toString(), 25000);
                freshAnrs = parseIntMarker(batchOut, "ANR_COUNT=");
            }

            // 2b. Single batched AOT check for all core_apps + auto-discovered user apps
            Set<String> toCompile = new LinkedHashSet<>();
            for (String dirtyPkg : dirtyQueue.getDirtyPackages()) {
                if (isValidPackage(dirtyPkg)) {
                    toCompile.add(dirtyPkg);
                } else {
                    dirtyQueue.markClean(dirtyPkg);
                }
            }

            if (!abortSignal.get() && !targetApps.isEmpty()) {
                StringBuilder aotCheck = new StringBuilder("for p in");
                for (String pkg : targetApps) {
                    if (isValidPackage(pkg) && !toCompile.contains(pkg)) {
                        aotCheck.append(" ").append(pkg);
                    }
                }
                aotCheck.append("; do pm path \"$p\" >/dev/null 2>&1 || continue; "
                        + "dumpsys package \"$p\" | grep -Fq '[status=speed]' || echo \"$p\"; done");
                String missingAot = adb.runShellCommand(aotCheck.toString(), 20000);
                if (!missingAot.isEmpty()) {
                    for (String line : missingAot.split("\\r?\\n")) {
                        String cand = line.trim();
                        if (isValidPackage(cand)) {
                            toCompile.add(cand);
                        }
                    }
                }
            }

            // 2c. Compile dirty/uncompiled packages with cmd package compile -m speed
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

            statusMsg = "OK (Trimmed caches, stopped=" + stoppedCount
                    + ", AOT compiled=" + compiledCount + ", ANR=" + freshAnrs + ")";
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
                compiledCount,
                freshAnrs
        );
    }

    private static Set<String> collectTargetApps(LocalAdbClient adb, DaemonConfig config) {
        Set<String> targets = new LinkedHashSet<>();
        for (String pkg : config.getCoreApps()) {
            if (isValidPackage(pkg)) {
                targets.add(pkg);
            }
        }
        if (!config.isAutoDiscoverUserApps()) {
            return targets;
        }
        Set<String> disabled = new HashSet<>(config.getDisabledPackages());
        try {
            String raw = adb.runShellCommand("pm list packages -3", 8000);
            for (String line : raw.split("\\r?\\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("package:")) {
                    String pkg = trimmed.substring("package:".length()).trim();
                    if (isValidPackage(pkg) && !disabled.contains(pkg)) {
                        targets.add(pkg);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Auto-discovery of third-party apps failed: " + e.getMessage());
        }
        return targets;
    }

    private static void enforceDisabledPackagesBatched(LocalAdbClient adb, DaemonConfig config, StringBuilder outCmd) {
        List<String> desiredDisabled = config.getDisabledPackages();
        if (desiredDisabled.isEmpty()) return;

        Set<String> alreadyDisabled = new HashSet<>();
        try {
            String raw = adb.runShellCommand("pm list packages -d -u", 8000);
            for (String line : raw.split("\\r?\\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("package:")) {
                    alreadyDisabled.add(trimmed.substring("package:".length()).trim());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not list disabled packages: " + e.getMessage());
        }

        for (String pkg : desiredDisabled) {
            if (isValidPackage(pkg)
                    && !SafetyInvariants.isProtectedPackage(pkg, config)
                    && !alreadyDisabled.contains(pkg)) {
                outCmd.append("pm disable-user --user 0 ").append(pkg).append(" >/dev/null 2>&1; ");
            }
        }
    }

    private static boolean isValidPackage(String pkg) {
        return pkg != null && !pkg.isEmpty() && PKG_PATTERN.matcher(pkg).matches();
    }

    private static int parseIntMarker(String text, String marker) {
        if (text == null || text.isEmpty()) return 0;
        for (String line : text.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(marker)) {
                try {
                    return Integer.parseInt(trimmed.substring(marker.length()).trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 0;
    }
}
