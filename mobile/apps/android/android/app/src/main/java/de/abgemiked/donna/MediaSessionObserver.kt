package com.yourcompany.donna

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log

/**
 * MediaSessionObserver — DONNA-123: MediaSession-Integration
 *
 * Beobachtet aktive MediaSessions des Geräts und hält den aktuellen
 * Wiedergabe-Status im Singleton-State.
 *
 * Benötigt: NotificationListenerService-Permission (DonnaNotificationListener).
 * Wird in MainActivity initialisiert und bei Heartbeat ausgelesen.
 *
 * Thread-safe: MediaInfo-State via @Volatile.
 */
object MediaSessionObserver {

    private const val TAG = "MediaSessionObserver"

    /**
     * Aktueller Wiedergabe-Status.
     * null = keine aktive MediaSession / nicht initialisiert.
     */
    @Volatile
    var currentMedia: MediaInfo? = null
        private set

    data class MediaInfo(
        val app: String,         // Package-Name der abspielenden App
        val title: String?,      // Track-Titel (aus MediaMetadata)
        val artist: String?,     // Künstler (aus MediaMetadata)
        val isPlaying: Boolean,  // true wenn STATE_PLAYING
    )

    /**
     * Liest aktuell aktive MediaSessions und aktualisiert currentMedia.
     *
     * Wird vom TrackingService-Loop und bei Heartbeat aufgerufen.
     * ComponentName muss auf einen aktiven NotificationListenerService zeigen —
     * DonnaNotificationListener ist die korrekte Referenz.
     */
    fun refresh(context: Context) {
        val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE)
            as? MediaSessionManager ?: return

        try {
            val componentName = ComponentName(context, DonnaNotificationListener::class.java)
            val controllers: List<MediaController> = msm.getActiveSessions(componentName)

            if (controllers.isEmpty()) {
                currentMedia = null
                return
            }

            // Ersten aktiven (oder playing) Controller bevorzugen
            val controller = controllers.firstOrNull { ctrl ->
                ctrl.playbackState?.state == PlaybackState.STATE_PLAYING
            } ?: controllers.first()

            val metadata = controller.metadata
            val playbackState = controller.playbackState

            val isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING

            currentMedia = MediaInfo(
                app = controller.packageName ?: "unknown",
                title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
                artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST),
                isPlaying = isPlaying,
            )

            Log.d(TAG, "MediaSession aktualisiert: ${currentMedia?.app} — " +
                "${currentMedia?.title} (playing=${currentMedia?.isPlaying})")

        } catch (e: SecurityException) {
            // NotificationListenerService-Permission fehlt — graceful degradation
            Log.w(TAG, "MediaSession Zugriff verweigert — NotificationListenerService-Permission fehlt?", e)
            currentMedia = null
        } catch (e: Exception) {
            Log.w(TAG, "MediaSession Refresh fehlgeschlagen: ${e.message}")
            currentMedia = null
        }
    }

    /**
     * Gibt den aktuellen Media-State als Dict für den Tracking-Payload zurück.
     * Gibt null zurück wenn kein Medium aktiv ist.
     */
    fun toDict(): Map<String, Any?>? {
        val info = currentMedia ?: return null
        return mapOf(
            "app"     to info.app,
            "title"   to info.title,
            "artist"  to info.artist,
            "playing" to info.isPlaying,
        )
    }
}
