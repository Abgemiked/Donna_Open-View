package com.yourcompany.donna.device

import org.json.JSONArray
import org.json.JSONObject

/**
 * #147 Paket D — lokaler, zuletzt synchronisierter Stand der Routinen (Server: `GET /routines/offline`).
 * Reine Datenklasse + Parser; die Ablage in den Prefs steht in [OfflineRoutineStore]. Geräte-Schritte tragen die
 * Wire-Form des Servers (`action` + Werte); Smart-Home-Schritte tragen nur eine Beschriftung (offline nie ausführbar).
 */
data class OfflineRoutineStep(val device: JSONObject?, val label: String)

data class OfflineRoutine(val name: String, val steps: List<OfflineRoutineStep>)

class OfflineRoutineSnapshot(val syncedAtMs: Long, val routines: List<OfflineRoutine>) {

    /** Schlüssel -> Anzeigename (für den Auslöser-Abgleich). */
    fun names(): Map<String, String> = routines.associate { OfflineIntent.nameKey(it.name) to it.name }

    fun byName(name: String): OfflineRoutine? = routines.firstOrNull { it.name == name }

    fun toJson(): String = JSONObject().apply {
        put("synced_at", syncedAtMs)
        put("routines", JSONArray().apply {
            routines.forEach { r ->
                put(JSONObject().apply {
                    put("name", r.name)
                    put("steps", JSONArray().apply {
                        r.steps.forEach { st ->
                            put(JSONObject().apply {
                                put("label", st.label)
                                st.device?.let { put("device", it) }
                            })
                        }
                    })
                })
            }
        })
    }.toString()

    companion object {
        const val MAX_ROUTINES = 20
        const val MAX_STEPS = 8

        /** Antwort von `GET /routines/offline` -> Snapshot; Ungültiges fällt weg (fail-closed). */
        fun parseServer(body: String, nowMs: Long): OfflineRoutineSnapshot? = try {
            val arr = JSONObject(body).optJSONArray("routines")
            if (arr == null) null else OfflineRoutineSnapshot(nowMs, readRoutines(arr, serverForm = true))
        } catch (_: Exception) {
            null
        }

        /** Gespeicherter Stand (Prefs) -> Snapshot. */
        fun parseStored(body: String): OfflineRoutineSnapshot? = try {
            val root = JSONObject(body)
            val arr = root.optJSONArray("routines")
            if (arr == null) null else OfflineRoutineSnapshot(root.optLong("synced_at", 0L), readRoutines(arr, serverForm = false))
        } catch (_: Exception) {
            null
        }

        private fun readRoutines(arr: JSONArray, serverForm: Boolean): List<OfflineRoutine> {
            val out = ArrayList<OfflineRoutine>()
            for (i in 0 until minOf(arr.length(), MAX_ROUTINES)) {
                val r = arr.optJSONObject(i) ?: continue
                val name = r.optString("name", "").trim()
                if (name.isEmpty() || name.length > 40) continue
                val steps = r.optJSONArray("steps") ?: continue
                val list = ArrayList<OfflineRoutineStep>()
                for (j in 0 until minOf(steps.length(), MAX_STEPS)) {
                    val st = steps.optJSONObject(j) ?: continue
                    val label = st.optString("label", "").take(80)
                    if (serverForm) {
                        if (st.optString("kind") == "device" && st.has("action")) {
                            val dev = JSONObject().put("type", DeviceActionKind.TYPE)
                            for (k in st.keys()) if (k != "kind" && k != "label" && k != "action_id" && k != "group_id") dev.put(k, st.get(k))
                            list.add(OfflineRoutineStep(dev, label))
                        } else {
                            list.add(OfflineRoutineStep(null, label)) // Smart Home o. ä.: offline nie
                        }
                    } else {
                        list.add(OfflineRoutineStep(st.optJSONObject("device"), label))
                    }
                }
                if (list.isNotEmpty()) out.add(OfflineRoutine(name, list))
            }
            return out
        }
    }
}
