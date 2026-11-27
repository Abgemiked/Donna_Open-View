package com.yourcompany.donna.widget

import org.json.JSONArray
import org.json.JSONObject

/** Ein Knopf des Szenen/Routinen-Widgets. [type] = [HomeActionsLogic.TYPE_SCENE] oder [HomeActionsLogic.TYPE_ROUTINE]. */
data class HomeButton(val type: String, val id: String, val name: String)

/** Ergebnis von `POST /routines/{id}/run`: ehrlicher Text des Servers + Geräte-Schritte für das Handy. */
data class RoutineRunResult(val text: String, val device: List<JSONObject>)

/**
 * #244: reine Logik des Szenen/Routinen-Widgets (ohne Android → JVM-Test). Quellen:
 *  - `GET /smarthome/scenes` → `{"scenes":[{"sceneId":"…","sceneName":"…"}]}` (SmartThings)
 *  - `GET /routines`         → `{"routines":[{"id":1,"name":"…","steps":[…]}]}`
 * Kennungen werden streng geprüft (sie landen im URL-Pfad); Namen werden gesäubert und gekürzt.
 */
object HomeActionsLogic {
    const val TYPE_SCENE = "scene"
    const val TYPE_ROUTINE = "routine"
    const val MAX_BUTTONS = 6
    const val NAME_MAX = 28
    const val TEXT_MAX = 300
    const val MAX_DEVICE_STEPS = 8

    private val SCENE_ID = Regex("^[A-Za-z0-9-]{1,64}$")
    private val ROUTINE_ID = Regex("^[1-9][0-9]{0,9}$")

    fun validId(type: String, id: String): Boolean = when (type) {
        TYPE_SCENE -> SCENE_ID.matches(id)
        TYPE_ROUTINE -> ROUTINE_ID.matches(id) && (id.toLongOrNull() ?: 0L) <= Int.MAX_VALUE
        else -> false
    }

    private fun button(type: String, id: String?, name: String?): HomeButton? {
        val i = id?.trim().orEmpty()
        val n = WidgetFormat.clean(name, NAME_MAX, stripBrackets = true)
        if (!validId(type, i) || n.isEmpty()) return null
        return HomeButton(type, i, n)
    }

    fun parseScenes(body: String?): List<HomeButton> {
        val arr = try { JSONObject(body ?: return emptyList()).optJSONArray("scenes") } catch (_: Exception) { null }
            ?: return emptyList()
        val out = ArrayList<HomeButton>()
        for (i in 0 until minOf(arr.length(), 100)) {
            val s = arr.optJSONObject(i) ?: continue
            button(TYPE_SCENE, s.optString("sceneId", ""), s.optString("sceneName", ""))?.let { out += it }
        }
        return out.distinctBy { it.id }
    }

    fun parseRoutines(body: String?): List<HomeButton> {
        val arr = try { JSONObject(body ?: return emptyList()).optJSONArray("routines") } catch (_: Exception) { null }
            ?: return emptyList()
        val out = ArrayList<HomeButton>()
        for (i in 0 until minOf(arr.length(), 100)) {
            val r = arr.optJSONObject(i) ?: continue
            val id = r.opt("id")
            if (id !is Int && id !is Long) continue
            button(TYPE_ROUTINE, id.toString(), r.optString("name", ""))?.let { out += it }
        }
        return out.distinctBy { it.id }
    }

    /** Auswahl je Widget speichern/lesen; Ungültiges fällt beim Lesen weg (fail-closed), höchstens [MAX_BUTTONS]. */
    fun toJson(list: List<HomeButton>): String = JSONArray().apply {
        list.take(MAX_BUTTONS).forEach { put(JSONObject().put("t", it.type).put("i", it.id).put("n", it.name)) }
    }.toString()

    fun fromJson(s: String?): List<HomeButton> {
        if (s.isNullOrBlank()) return emptyList()
        val arr = try { JSONArray(s) } catch (_: Exception) { return emptyList() }
        val out = ArrayList<HomeButton>()
        for (i in 0 until minOf(arr.length(), MAX_BUTTONS)) {
            val o = arr.optJSONObject(i) ?: continue
            button(o.optString("t", ""), o.optString("i", ""), o.optString("n", ""))?.let { out += it }
        }
        return out
    }

    fun key(b: HomeButton) = "${b.type}:${b.id}"

    /** Frage im Bestätigungsfenster. */
    fun question(b: HomeButton): String =
        if (b.type == TYPE_SCENE) "Szene „${b.name}“ starten?" else "Routine „${b.name}“ starten?"

    /** Antwort von `POST /routines/{id}/run`; nur `type=device_action`-Objekte, höchstens [MAX_DEVICE_STEPS]. */
    fun parseRunResult(body: String?): RoutineRunResult? {
        val root = try { JSONObject(body ?: return null) } catch (_: Exception) { return null }
        if (!root.optBoolean("ok", false)) return null
        val text = WidgetFormat.clean(root.optString("text", ""), TEXT_MAX)
        val dev = ArrayList<JSONObject>()
        val arr = root.optJSONArray("device")
        if (arr != null) {
            for (i in 0 until minOf(arr.length(), MAX_DEVICE_STEPS)) {
                val a = arr.optJSONObject(i) ?: continue
                if (a.optString("type") == "device_action") dev += a
            }
        }
        return RoutineRunResult(text, dev)
    }

    enum class ErrorKind { UNREACHABLE, UNKNOWN, NO_TOKEN, RATE_LIMITED, LIVE_LOCKED, NOT_CONFIGURED, GONE, FAILED }

    /** HTTP-Code (bzw. -1 nicht erreichbar / -2 Ergebnis unklar) -> Meldungsart des Bestätigungsfensters. */
    fun errorKind(code: Int): ErrorKind = when (code) {
        -1 -> ErrorKind.UNREACHABLE
        -2 -> ErrorKind.UNKNOWN
        401, 403 -> ErrorKind.NO_TOKEN
        423 -> ErrorKind.LIVE_LOCKED
        429 -> ErrorKind.RATE_LIMITED
        503 -> ErrorKind.NOT_CONFIGURED
        404 -> ErrorKind.GONE
        else -> ErrorKind.FAILED
    }

    /** Spalten je Breite (dp): schmal 2, sonst 3. */
    fun columnsFor(widthDp: Int): Int = if (widthDp in 1 until 220) 2 else 3

    /** Zeilen je Höhe (dp): niedrig (oder unbekannt) 1, sonst 2. */
    fun rowsFor(heightDp: Int): Int = if (heightDp >= 110) 2 else 1
}
