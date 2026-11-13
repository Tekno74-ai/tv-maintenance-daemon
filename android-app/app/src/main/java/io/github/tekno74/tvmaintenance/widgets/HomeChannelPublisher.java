package io.github.tekno74.tvmaintenance.widgets;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.tv.TvContract;
import android.net.Uri;
import android.util.Log;

import java.io.OutputStream;
import java.util.Locale;

import io.github.tekno74.tvmaintenance.ui.MainActivity;

/**
 * Module 7c (widgets - Alternativ A): Publishes and updates the native Android TV / Projectivy
 * Home Screen Channel ("Villa Wahren Live") with 5 graphical status cards.
 */
public final class HomeChannelPublisher {
    private static final String TAG = "TvMaintChannel";
    private static final Uri PREVIEW_PROGRAMS_URI = Uri.parse("content://android.media.tv/preview_program");
    private static final String CHANNEL_PROVIDER_ID = "villa_wahren_live_v1";

    public static synchronized void syncChannelAndCards(Context context, SmartHomeData.Snapshot snap) {
        try {
            long channelId = ensureChannel(context);
            if (channelId <= 0) {
                return;
            }
            long minuteBucket = System.currentTimeMillis() / 60_000L;

            // Card 1: Smilla
            String smillaTitle = snap.isSmillaOut()
                    ? "🐱 Smilla: UTE NU (" + snap.smillaHoursOut + " idag)"
                    : "🐱 Smilla: INNE HEMMA (" + snap.smillaHoursOut + " ute idag)";
            String smillaDesc = "Passager idag: " + snap.smillaPassages + " • Kattlucka batteri: " + snap.flapBattery;
            upsertCard(context, channelId, "smilla", 100, smillaTitle, smillaDesc, minuteBucket);

            // Card 2: Electricity Price & Power
            String elTitle = String.format(Locale.US, "⚡ Elpris: %d öre/kWh (%.2f kW)",
                    snap.priceOre, snap.currentPowerW / 1000.0);
            String elDesc = "Min " + snap.priceMinOre + " • Snitt " + snap.priceAvgOre
                    + " • Max " + snap.priceMaxOre + " öre • Idag: " + snap.dailyCostSek;
            upsertCard(context, channelId, "elpris", 90, elTitle, elDesc, minuteBucket);

            // Card 3: Climate Indoor & Outdoor
            String klimTitle = "🌡️ Inne: " + snap.indoorTempC + " °C  •  Ute: " + snap.outdoorTempC + " °C";
            String klimDesc = "Övre hall: " + snap.upperHallTempC + " °C";
            upsertCard(context, channelId, "klimat", 80, klimTitle, klimDesc, minuteBucket);

            // Card 4: Water Heater (IVT)
            String vvTitle = "🚿 Varmvatten: " + snap.waterTempC + " °C (" + snap.waterVolume40cL + " L 40°C)";
            String vvDesc = "Mål: " + snap.waterTargetC + " °C • Status: " + snap.waterStatus;
            upsertCard(context, channelId, "varmvatten", 70, vvTitle, vvDesc, minuteBucket);

            // Card 5: Internet & UniFi UCG Max
            String netTitle = String.format(Locale.US, "🌐 Internet: ↓ %.1f Mbit/s • UCG %s",
                    snap.tvDownMbps, snap.ucgState);
            String netDesc = "Gateway CPU: " + snap.ucgCpuPct + " • Wi-Fi: " + snap.wifiClients
                    + " enheter • NAS: " + snap.nasDownKbps;
            upsertCard(context, channelId, "internet", 60, netTitle, netDesc, minuteBucket);

        } catch (Exception e) {
            Log.w(TAG, "Could not sync Home Screen Channel: " + e.getMessage());
        }
    }

    private static long ensureChannel(Context context) {
        ContentResolver resolver = context.getContentResolver();
        long existingId = -1;
        String[] proj = {TvContract.Channels._ID, TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID};
        try (Cursor c = resolver.query(TvContract.Channels.CONTENT_URI, proj, null, null, null)) {
            if (c != null) {
                while (c.moveToNext()) {
                    String pid = c.getString(1);
                    if (CHANNEL_PROVIDER_ID.equals(pid)) {
                        existingId = c.getLong(0);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Query channels failed: " + e.getMessage());
        }

        Intent launchIntent = new Intent(context, MainActivity.class);
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        ContentValues cv = new ContentValues();
        cv.put(TvContract.Channels.COLUMN_DISPLAY_NAME, "Villa Wahren Live");
        cv.put(TvContract.Channels.COLUMN_DESCRIPTION, "Smilla, Elpris, Temperaturer, Varmvatten & Internet");
        cv.put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_PREVIEW);
        cv.put(TvContract.Channels.COLUMN_APP_LINK_INTENT_URI, launchIntent.toUri(Intent.URI_INTENT_SCHEME));
        cv.put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID, CHANNEL_PROVIDER_ID);

        if (existingId > 0) {
            Uri chUri = TvContract.buildChannelUri(existingId);
            resolver.update(chUri, cv, null, null);
            return existingId;
        }

        Uri inserted = resolver.insert(TvContract.Channels.CONTENT_URI, cv);
        if (inserted == null) return -1;
        long newId = ContentUris.parseId(inserted);
        writeChannelLogo(context, newId);
        try {
            TvContract.requestChannelBrowsable(context, newId);
        } catch (Exception ignored) {
        }
        Log.i(TAG, "Created Android TV Home Channel 'Villa Wahren Live' (id=" + newId + ")");
        return newId;
    }

    private static void writeChannelLogo(Context context, long channelId) {
        Uri logoUri = TvContract.buildChannelLogoUri(channelId);
        Bitmap bmp = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(Color.parseColor("#0F172A"));
        canvas.drawCircle(40, 40, 38, bg);
        Paint fg = new Paint(Paint.ANTI_ALIAS_FLAG);
        fg.setColor(Color.parseColor("#38BDF8"));
        fg.setTextSize(34f);
        fg.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("⚡", 40, 52, fg);
        try (OutputStream os = context.getContentResolver().openOutputStream(logoUri)) {
            if (os != null) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
            }
        } catch (Exception ignored) {
        } finally {
            bmp.recycle();
        }
    }

    private static void upsertCard(
            Context context,
            long channelId,
            String cardKey,
            int weight,
            String title,
            String desc,
            long minuteBucket
    ) {
        ContentResolver resolver = context.getContentResolver();
        long programId = -1;
        String[] proj = {"_id", "internal_provider_id"};
        try (Cursor c = resolver.query(PREVIEW_PROGRAMS_URI, proj, "channel_id=?",
                new String[]{String.valueOf(channelId)}, null)) {
            if (c != null) {
                while (c.moveToNext()) {
                    if (cardKey.equals(c.getString(1))) {
                        programId = c.getLong(0);
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
        }

        Intent clickIntent = new Intent(context, MainActivity.class);
        clickIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        String artUri = "content://" + WidgetCardProvider.AUTHORITY + "/" + cardKey + ".png?t=" + minuteBucket;

        ContentValues cv = new ContentValues();
        cv.put("channel_id", channelId);
        cv.put("type", 4); // TYPE_CLIP
        cv.put("title", title);
        cv.put("short_description", desc);
        cv.put("poster_art_uri", artUri);
        cv.put("poster_art_aspect_ratio", 0); // 16:9
        cv.put("intent_uri", clickIntent.toUri(Intent.URI_INTENT_SCHEME));
        cv.put("internal_provider_id", cardKey);
        cv.put("weight", weight);

        if (programId > 0) {
            Uri progUri = ContentUris.withAppendedId(PREVIEW_PROGRAMS_URI, programId);
            resolver.update(progUri, cv, null, null);
        } else {
            resolver.insert(PREVIEW_PROGRAMS_URI, cv);
        }
    }
}
