package com.yourcompany.donna

import android.Manifest
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap

class ContactsModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    override fun getName() = "ContactsModule"

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            reactApplicationContext,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

    @ReactMethod
    fun hasReadPermission(promise: Promise) {
        promise.resolve(hasPermission())
    }

    private fun normalize(s: String): String =
        s.lowercase()
         .replace("ä", "a").replace("ö", "o").replace("ü", "u")
         .replace("ß", "ss").replace("é", "e").replace("è", "e")
         .replace("ê", "e").replace("à", "a").replace("â", "a")

    /**
     * Sucht Kontakte deren Name das Query-Substring (case-insensitive) enthaelt.
     * Sortiert: exakte Treffer → startsWith → contains.
     * Gibt fuer jeden Kontakt die erste Telefonnummer zurueck.
     * Fallback: Umlaut-normalisierter Client-seitiger Filter wenn erster Query leer.
     *
     * @return Array von { name, number, contactId }
     */
    @ReactMethod
    fun searchByName(query: String, promise: Promise) {
        if (!hasPermission()) {
            promise.reject("NO_PERMISSION", "READ_CONTACTS permission missing")
            return
        }
        val q = query.trim()
        if (q.isEmpty()) {
            promise.resolve(Arguments.createArray())
            return
        }
        try {
            val resolver = reactApplicationContext.contentResolver
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY,
                ContactsContract.CommonDataKinds.Phone.IS_PRIMARY,
            )
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val args = arrayOf("%$q%")
            val cursor = resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection, selection, args,
                "${ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY} DESC, ${ContactsContract.CommonDataKinds.Phone.IS_PRIMARY} DESC"
            )
            val results = mutableListOf<Triple<String, String, Long>>()  // name, number, contactId
            val seen = mutableSetOf<Long>()  // contactId -> erste Nummer gewinnt
            cursor?.use {
                val idIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (it.moveToNext()) {
                    val cid = it.getLong(idIdx)
                    if (cid in seen) continue
                    seen.add(cid)
                    val name = it.getString(nameIdx) ?: continue
                    val number = it.getString(numIdx) ?: continue
                    results.add(Triple(name, number, cid))
                }
            }

            // Fallback: normalisierter Client-seitiger Filter wenn erster Query leer
            if (results.isEmpty()) {
                val qNorm = normalize(q)
                val fallbackCursor = resolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    projection, null, null,
                    "${ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY} DESC, ${ContactsContract.CommonDataKinds.Phone.IS_PRIMARY} DESC"
                )
                var candidateCount = 0
                var rowsRead = 0
                val maxRows = 500
                fallbackCursor?.use {
                    val idIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                    val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    while (it.moveToNext() && candidateCount < 20 && rowsRead < maxRows) {
                        rowsRead++
                        val cid = it.getLong(idIdx)
                        if (cid in seen) continue
                        val name = it.getString(nameIdx) ?: continue
                        val number = it.getString(numIdx) ?: continue
                        if (normalize(name).contains(qNorm)) {
                            seen.add(cid)
                            results.add(Triple(name, number, cid))
                            candidateCount++
                        }
                    }
                }
            }

            // Sortierung: exact → startsWith → contains; danach alphabetisch
            val qLower = q.lowercase()
            val sorted = results.sortedWith(compareBy(
                { c ->
                    val n = c.first.lowercase()
                    when {
                        n == qLower -> 0
                        n.startsWith(qLower) -> 1
                        else -> 2
                    }
                },
                { it.first.lowercase() }
            ))

            val arr: WritableArray = Arguments.createArray()
            for ((name, number, cid) in sorted.take(10)) {
                val item: WritableMap = Arguments.createMap()
                item.putString("name", name)
                item.putString("number", number)
                item.putDouble("contactId", cid.toDouble())
                arr.pushMap(item)
            }
            promise.resolve(arr)
        } catch (e: Exception) {
            promise.reject("CONTACTS_ERROR", e.message ?: "Fehler beim Lesen der Kontakte")
        }
    }
}
