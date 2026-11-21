package com.yourcompany.donna

import java.util.LinkedList

/**
 * NotificationStore — DONNA-122: Singleton Ring-Buffer für gepufferte Notifications
 *
 * Speichert die letzten MAX_SIZE Notifications im RAM.
 * Kein Persist — Daten gehen beim App-Kill verloren (gewollt: Datensparsamkeit).
 *
 * Thread-safe via synchronized-Blöcke.
 */
object NotificationStore {

    private const val MAX_SIZE = 20

    private val buffer: LinkedList<NotificationEntry> = LinkedList()
    private val lock = Any()

    /**
     * Fügt eine neue Notification hinzu.
     * Älteste Einträge werden verworfen wenn der Puffer voll ist.
     */
    fun add(entry: NotificationEntry) {
        synchronized(lock) {
            if (buffer.size >= MAX_SIZE) {
                buffer.removeFirst()
            }
            buffer.addLast(entry)
        }
    }

    /**
     * Gibt die letzten n Einträge zurück (neueste zuletzt).
     * n wird auf MAX_SIZE geklemmt.
     */
    fun getRecent(n: Int): List<NotificationEntry> {
        synchronized(lock) {
            val count = minOf(n, buffer.size)
            return buffer.takeLast(count)
        }
    }

    /**
     * Gibt alle gepufferten Einträge zurück.
     */
    fun getAll(): List<NotificationEntry> {
        synchronized(lock) {
            return buffer.toList()
        }
    }

    /**
     * Löscht den Puffer (für Tests / Onboarding-Reset).
     */
    fun clear() {
        synchronized(lock) {
            buffer.clear()
        }
    }
}
