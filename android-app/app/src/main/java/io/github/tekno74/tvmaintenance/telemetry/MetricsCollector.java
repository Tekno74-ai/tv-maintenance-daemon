package io.github.tekno74.tvmaintenance.telemetry;

import android.content.Context;
import android.os.Environment;
import android.os.PowerManager;
import android.os.StatFs;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Locale;

/**
 * Module 3a (telemetry): Reads TV memory, storage, and power state directly from the Linux kernel
 * and Android APIs with zero ADB overhead.
 */
public final class MetricsCollector {

    public static final class SystemSnapshot {
        public final long memAvailableMb;
        public final long memTotalMb;
        public final double dataFreeGb;
        public final double dataTotalGb;
        public final boolean interactive;

        public SystemSnapshot(long memAvailableMb, long memTotalMb, double dataFreeGb, double dataTotalGb, boolean interactive) {
            this.memAvailableMb = memAvailableMb;
            this.memTotalMb = memTotalMb;
            this.dataFreeGb = dataFreeGb;
            this.dataTotalGb = dataTotalGb;
            this.interactive = interactive;
        }
    }

    public static SystemSnapshot collect(Context context) {
        long memAvailKb = 0;
        long memTotalKb = 0;
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("MemAvailable:")) {
                    memAvailKb = parseKb(line);
                } else if (line.startsWith("MemTotal:")) {
                    memTotalKb = parseKb(line);
                }
                if (memAvailKb > 0 && memTotalKb > 0) {
                    break;
                }
            }
        } catch (Exception ignored) {
        }

        double freeGb = 0.0;
        double totalGb = 0.0;
        try {
            StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
            long availBytes = stat.getAvailableBytes();
            long totalBytes = stat.getTotalBytes();
            freeGb = Math.round((availBytes / (1024.0 * 1024.0 * 1024.0)) * 100.0) / 100.0;
            totalGb = Math.round((totalBytes / (1024.0 * 1024.0 * 1024.0)) * 100.0) / 100.0;
        } catch (Exception ignored) {
        }

        boolean isInteractive = false;
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                isInteractive = pm.isInteractive();
            }
        } catch (Exception ignored) {
        }

        return new SystemSnapshot(
                memAvailKb / 1024L,
                memTotalKb / 1024L,
                freeGb,
                totalGb,
                isInteractive
        );
    }

    private static long parseKb(String line) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length >= 2) {
            try {
                return Long.parseLong(parts[1]);
            } catch (NumberFormatException ignored) {
            }
        }
        return 0L;
    }

    public static String formatSummary(SystemSnapshot s) {
        return String.format(Locale.US, "RAM: %d / %d MB free | Storage: %.2f / %.2f GB free | Screen: %s",
                s.memAvailableMb, s.memTotalMb, s.dataFreeGb, s.dataTotalGb, s.interactive ? "ON" : "OFF");
    }
}
