package com.yourcompany.donna

/**
 * NotificationEntry — DONNA-122: Datenklasse für gepufferte Notifications
 *
 * Enthält die für Donna relevanten Metadaten einer Notification.
 * Kein Persist — nur RAM-Puffer (Ring-Buffer in NotificationStore).
 */
data class NotificationEntry(
    val timestamp: Long,         // System.currentTimeMillis()
    val packageName: String,     // z.B. "com.whatsapp"
    val appLabel: String,        // z.B. "WhatsApp"
    val title: String?,          // Notification.EXTRA_TITLE
    val text: String?,           // Notification.EXTRA_TEXT (gefiltert)
)
