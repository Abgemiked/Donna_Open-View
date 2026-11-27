package com.yourcompany.donna.device

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat

/** Eine Zeile aus MediaStore.Images (nur Metadaten; das Bild selbst wird hier nie gelesen). */
data class MediaRow(val id: Long, val dateAddedSec: Long, val relativePath: String?, val displayName: String?, val pending: Boolean)

/**
 * #219: findet den Screenshot, den die Systemaktion gerade angelegt hat. Rein (ohne Android), damit testbar.
 * Gewählt wird die NEUESTE Zeile, die (1) nicht mehr "pending" ist, (2) nicht vor der Auslösung entstand und
 * (3) nach Screenshot aussieht (Pfad enthält "Screenshot" oder Dateiname beginnt damit — Samsung: DCIM/Screenshots/).
 * Nichts passend -> null (keine Vorschau, nie ein falsches Bild raten).
 */
object ScreenshotLocator {
    /** DATE_ADDED hat Sekunden-Auflösung (abgerundet): ab der Sekunde der Auslösung, KEIN Spielraum nach unten (sonst ein älterer eigener Screenshot). */
    const val SLACK_SEC = 0L
    const val POLL_INTERVAL_MS = 400L
    const val POLL_TIMEOUT_MS = 6_000L

    fun looksLikeScreenshot(row: MediaRow): Boolean =
        (row.relativePath?.contains("screenshot", ignoreCase = true) == true) ||
            (row.displayName?.startsWith("screenshot", ignoreCase = true) == true)

    fun pick(rows: List<MediaRow>, triggeredAtMs: Long): MediaRow? {
        val since = triggeredAtMs / 1000 - SLACK_SEC
        return rows
            .filter { !it.pending && it.dateAddedSec >= since && looksLikeScreenshot(it) }
            .maxWithOrNull(compareBy<MediaRow> { it.dateAddedSec }.thenBy { it.id })
    }

    /** Die Vorschau-URI ist immer eine Bilder-Zeile von MediaStore; alles andere wird nie geöffnet (Intent-Schutz). */
    fun isMediaImageUri(uri: String?): Boolean {
        if (uri == null) return false
        val m = Regex("^content://media/external(?:_primary)?/images/media/(\\d{1,18})$").matchEntire(uri)
        return m != null
    }
}

/** Android-Anteil: MediaStore abfragen und die Galerie auf genau dieses Bild öffnen. */
object ScreenshotPreview {
    fun hasReadPermission(ctx: Context): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Wartet (BLOCKIEREND — nur auf einem Worker aufrufen) bis zu [ScreenshotLocator.POLL_TIMEOUT_MS] auf den neuen
     * Screenshot und liefert seine content-URI als Text. Ohne Leserecht oder bei Fehler sofort/zum Ende null.
     */
    fun awaitUri(ctx: Context, triggeredAtMs: Long): String? {
        if (!hasReadPermission(ctx)) return null
        val deadline = System.currentTimeMillis() + ScreenshotLocator.POLL_TIMEOUT_MS
        while (true) {
            try {
                ScreenshotLocator.pick(query(ctx, triggeredAtMs), triggeredAtMs)?.let {
                    return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it.id).toString()
                }
            } catch (_: Exception) {
                return null
            }
            if (System.currentTimeMillis() >= deadline) return null
            try { Thread.sleep(ScreenshotLocator.POLL_INTERVAL_MS) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return null }
        }
    }

    private fun query(ctx: Context, triggeredAtMs: Long): List<MediaRow> {
        val since = (triggeredAtMs / 1000 - ScreenshotLocator.SLACK_SEC).toString()
        val proj = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.IS_PENDING,
        )
        val out = ArrayList<MediaRow>()
        ctx.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, "${MediaStore.Images.Media.DATE_ADDED} >= ?", arrayOf(since),
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { c ->
            while (c.moveToNext() && out.size < 20) {
                out.add(MediaRow(c.getLong(0), c.getLong(1), c.getString(3), c.getString(2), c.getInt(4) != 0))
            }
        }
        return out
    }

    /** Öffnet GENAU dieses Bild (ACTION_VIEW auf die MediaStore-URI), nicht die Galerie allgemein. false = nicht geöffnet. */
    fun openInGallery(ctx: Context, uri: String?): Boolean {
        if (!ScreenshotLocator.isMediaImageUri(uri)) return false
        return try {
            val u = Uri.parse(uri)
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(u, "image/*")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
            true
        } catch (_: Exception) {
            false
        }
    }
}
