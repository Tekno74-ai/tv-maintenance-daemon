package io.github.tekno74.tvmaintenance.widgets;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.Locale;

/**
 * Module 7b (widgets): ContentProvider that dynamically renders 16:9 graphical poster cards (640x360 PNG)
 * for the Projectivy Launcher / Android TV Home Screen Channel ("Villa Wahren Live").
 */
public final class WidgetCardProvider extends ContentProvider {
    public static final String AUTHORITY = "io.github.tekno74.tvmaintenance.cards";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        Context ctx = getContext();
        if (ctx == null) throw new FileNotFoundException("Context is null");

        String segment = uri.getLastPathSegment();
        if (segment == null) segment = "smilla";
        String cardType = segment.replace(".png", "").toLowerCase(Locale.ROOT);

        SmartHomeData.Snapshot snap = SmartHomeData.loadCached(ctx);
        Bitmap bmp = renderCardBitmap(cardType, snap);

        File outDir = new File(ctx.getCacheDir(), "cards");
        //noinspection ResultOfMethodCallIgnored
        outDir.mkdirs();
        File outFile = new File(outDir, cardType + ".png");
        try (FileOutputStream fos = new FileOutputStream(outFile, false)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
        } catch (Exception e) {
            throw new FileNotFoundException("Failed to render card: " + e.getMessage());
        } finally {
            bmp.recycle();
        }
        return ParcelFileDescriptor.open(outFile, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private static Bitmap renderCardBitmap(String cardType, SmartHomeData.Snapshot s) {
        int w = 640;
        int h = 360;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);

        int accentColor;
        String badge;
        String headline;
        String subline1;
        String subline2;
        float barRatio;

        switch (cardType) {
            case "elpris": {
                if (s.priceOre >= 180) {
                    accentColor = Color.parseColor("#F43F5E"); // Rose Red
                } else if (s.priceOre >= 120) {
                    accentColor = Color.parseColor("#F59E0B"); // Amber
                } else {
                    accentColor = Color.parseColor("#10B981"); // Emerald Green
                }
                badge = "ELPRIS & EFFEKT (TIBBER)";
                headline = s.priceOre + " öre/kWh";
                subline1 = String.format(Locale.US, "Just nu: %.2f kW (%d W)", s.currentPowerW / 1000.0, s.currentPowerW);
                subline2 = "Min " + s.priceMinOre + " • Snitt " + s.priceAvgOre + " • Max " + s.priceMaxOre + " öre";
                barRatio = Math.min(1f, Math.max(0.08f, (float) s.priceOre / Math.max(1f, s.priceMaxOre)));
                break;
            }
            case "klimat": {
                accentColor = Color.parseColor("#38BDF8"); // Sky Blue
                badge = "KLIMAT INNE & UTE";
                headline = s.indoorTempC + " °C inne";
                subline1 = "Utetemperatur: " + s.outdoorTempC + " °C";
                subline2 = "Övre hall: " + s.upperHallTempC + " °C";
                barRatio = 0.65f;
                break;
            }
            case "varmvatten": {
                accentColor = Color.parseColor("#FB923C"); // Orange
                badge = "VARMVATTENBEREDARE (IVT)";
                headline = s.waterTempC + " °C";
                subline1 = "Tillgängligt 40°C-vatten: " + s.waterVolume40cL + " liter";
                subline2 = "Mål: " + s.waterTargetC + " °C • " + s.waterStatus;
                barRatio = 0.80f;
                break;
            }
            case "internet": {
                accentColor = Color.parseColor("#A855F7"); // Purple
                badge = "INTERNET & UNIFI UCG MAX";
                headline = String.format(Locale.US, "↓ %.1f Mbit/s", s.tvDownMbps);
                subline1 = "Gateway: " + s.ucgState + " (CPU " + s.ucgCpuPct + ") • Wi-Fi: " + s.wifiClients + " enheter";
                subline2 = "NAS ner: " + s.nasDownKbps + String.format(Locale.US, " • TV upp: ↑ %.1f Mbit/s", s.tvUpMbps);
                barRatio = Math.min(1f, Math.max(0.10f, (float) (s.tvDownMbps / 100.0)));
                break;
            }
            case "stickning": {
                accentColor = s.knittingLightOn ? Color.parseColor("#FBBF24") : Color.parseColor("#64748B");
                badge = "LJUSLÄGE STICKNING (LISA)";
                headline = s.knittingLightOn ? "TÄNT 💡" : "SLÄCKT 🌙";
                subline1 = "Tryck OK på fjärrkontrollen för att växla";
                subline2 = "Home Assistant: input_boolean.stickbelysning";
                barRatio = s.knittingLightOn ? 1.0f : 0.15f;
                break;
            }
            case "smilla":
            default: {
                boolean out = s.isSmillaOut();
                accentColor = out ? Color.parseColor("#F59E0B") : Color.parseColor("#22C55E");
                badge = "SMILLA (SUREPETCARE & AIRTAG)";
                headline = out ? "UTE NU" : "INNE HEMMA";
                subline1 = "Ute idag: " + s.smillaHoursOut + "  •  Passager: " + s.smillaPassages;
                subline2 = "Status: " + s.smillaStatus + "  •  Batteri: " + s.flapBattery;
                barRatio = out ? 0.85f : 0.35f;
                break;
            }
        }

        // Background gradient
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setShader(new LinearGradient(
                0, 0, w, h,
                Color.parseColor("#0F172A"),
                Color.parseColor("#1E293B"),
                Shader.TileMode.CLAMP));
        RectF fullRect = newRect(0, 0, w, h);
        c.drawRoundRect(fullRect, 28f, 28f, bgPaint);

        // Top accent bar
        Paint accentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        accentPaint.setColor(accentColor);
        c.drawRoundRect(newRect(0, 0, w, 12), 6f, 6f, accentPaint);

        // Badge pill
        Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pillPaint.setColor(Color.argb(45, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)));
        c.drawRoundRect(newRect(36, 36, w - 36, 86), 14f, 14f, pillPaint);

        Paint badgeText = new Paint(Paint.ANTI_ALIAS_FLAG);
        badgeText.setColor(accentColor);
        badgeText.setTextSize(22f);
        badgeText.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        c.drawText(badge, 54, 69, badgeText);

        // Main Headline
        Paint headText = new Paint(Paint.ANTI_ALIAS_FLAG);
        headText.setColor(Color.parseColor("#F8FAFC"));
        headText.setTextSize(62f);
        headText.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        c.drawText(headline, 40, 172, headText);

        // Subline 1
        Paint sub1Paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        sub1Paint.setColor(Color.parseColor("#E2E8F0"));
        sub1Paint.setTextSize(26f);
        sub1Paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
        c.drawText(subline1, 40, 228, sub1Paint);

        // Subline 2
        Paint sub2Paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        sub2Paint.setColor(Color.parseColor("#94A3B8"));
        sub2Paint.setTextSize(23f);
        c.drawText(subline2, 40, 270, sub2Paint);

        // Progress / Gauge track at bottom
        Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        trackPaint.setColor(Color.parseColor("#334155"));
        c.drawRoundRect(newRect(40, 306, w - 40, 324), 9f, 9f, trackPaint);

        float fillW = Math.max(24f, (w - 80) * barRatio);
        c.drawRoundRect(newRect(40, 306, 40 + fillW, 324), 9f, 9f, accentPaint);

        return bmp;
    }

    private static RectF newRect(float l, float t, float r, float b) {
        return new RectF(l, t, r, b);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return "image/png";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
