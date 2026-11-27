package com.yourcompany.donna.device

import org.json.JSONObject

/**
 * #147 Paket D — Entscheidung für Grundbefehle ohne Server. Greift NUR, wenn der Server nicht erreichbar ist
 * ([OfflineGate]); mit Server gilt unverändert der Weg über das Modell.
 *
 * Regeln (alle fail-closed):
 *  - Aktion und Werte stammen allein aus Mikes eigenen Worten ([OfflineIntent], Spiegel von device_intent.py).
 *  - Genau EINE Aktion je Äußerung; mehrere Befehle, unklare Uhrzeit, unzulässige Werte -> keine Aktion, ehrliche Antwort.
 *  - Der Schalter je Aktionsart gilt auch offline (aus bleibt aus). Nichts Bestätigungspflichtiges, keine Erinnerungen,
 *    kein Smart Home, keine Nachrichten/Anrufe: alles, was den Server braucht, wird ehrlich so benannt.
 *  - Routinen aus dem lokalen Stand: nur Geräte-Schritte, jeder Schritt mit Schalter und erneuter Prüfung.
 *  - Sprache: "angestoßen", nie "erledigt" (kein Rückkanal ohne Server).
 */
object OfflineCommands {

    const val NEEDS_SERVER = "Dafür brauche ich gerade die Verbindung zu meinem Server."
    const val MULTI = "Mehrere Befehle schaffe ich nur mit Verbindung. Sag mir bitte einen."
    const val CLARIFY_TIME = "Sag die Uhrzeit bitte mit morgens oder abends."
    const val REMINDER = "Erinnerungen gehen nur mit Verbindung."
    const val BRIGHTNESS_RANGE = "Die Helligkeit stelle ich nur zwischen 5 und 100 Prozent."
    const val ALARM_VOLUME = "Den Wecker-Ton stelle ich nicht stumm oder fast stumm."
    /** Vor dem Ergebnis nichts behaupten: die Karte des Geräts meldet danach, wie es ausging. */
    const val STARTED = "Mach ich."
    const val STALE = "Meine Routinen sind zu alt. Dafür brauche ich Verbindung."

    /** Routinen-Stand ohne Abgleich länger als 7 Tage läuft offline nicht mehr. */
    const val MAX_SNAPSHOT_AGE_MS = 7L * 24 * 60 * 60 * 1000

    private fun kindLabel(kind: String): String = when (kind) {
        "flashlight" -> "Die Taschenlampe"
        "volume" -> "Die Lautstärke"
        "open_app" -> "Apps öffnen"
        "open_settings" -> "Einstellungen öffnen"
        "timer_alarm" -> "Timer und Wecker"
        "brightness" -> "Die Helligkeit"
        "lock_screen" -> "Bildschirm sperren"
        else -> "Das"
    }

    private fun disabledText(@Suppress("UNUSED_PARAMETER") kind: String) = "Das ist in den Einstellungen ausgeschaltet."

    /** Ergebnis der reinen Einordnung (auch Grundlage des Vertragstests). */
    data class Classified(val outcome: String, val act: OfflineIntent.Act? = null)

    fun classify(text: String): Classified {
        val findings = OfflineIntent.findings(text)
        val acts = findings.filterIsInstance<OfflineIntent.Finding.Action>().map { it.act }.distinct()
        val ranges = findings.filterIsInstance<OfflineIntent.Finding.OutOfRange>()
        val ambiguous = findings.any { it is OfflineIntent.Finding.AmbiguousTime }
        val several = findings.any { it is OfflineIntent.Finding.Several }
        val distinct = acts.size + (if (ranges.isNotEmpty()) 1 else 0) + (if (ambiguous) 1 else 0) + (if (several) 2 else 0)
        return when {
            distinct == 0 -> Classified("none")
            distinct > 1 -> Classified("multi")
            acts.size == 1 -> Classified("action", acts[0])
            ambiguous -> Classified("clarify_time")
            else -> Classified("out_of_range")
        }
    }

    private fun rangeText(f: List<OfflineIntent.Finding>): String =
        if (f.filterIsInstance<OfflineIntent.Finding.OutOfRange>().any { it.what == "alarm_volume" }) ALARM_VOLUME else BRIGHTNESS_RANGE

    sealed class Outcome {
        /** [actions] gehen durch die bestehende Prüfung (Gate: Schema, Schalter, Budget) und den Runner. */
        data class Run(val actions: List<JSONObject>, val speech: String) : Outcome()
        data class Say(val speech: String) : Outcome()
    }

    fun toJson(act: OfflineIntent.Act): JSONObject {
        val j = JSONObject().put("type", DeviceActionKind.TYPE).put("action", act.name)
        for ((k, v) in act.params) j.put(k, v)
        return j
    }

    fun interpret(
        text: String,
        enabled: Set<DeviceActionKind>,
        snapshot: OfflineRoutineSnapshot?,
        nowMs: Long = System.currentTimeMillis(),
    ): Outcome {
        val msg = text.take(1500)
        // 1. Routine (Name in Mikes Worten, ganzer Satz) — ohne jedes Modell
        if (snapshot != null) {
            val name = OfflineIntent.matchTrigger(msg, snapshot.names())
            val routine = name?.let { snapshot.byName(it) }
            if (routine != null) {
                val age = nowMs - snapshot.syncedAtMs
                if (snapshot.syncedAtMs <= 0L || age > MAX_SNAPSHOT_AGE_MS) return Outcome.Say(STALE)
                return runRoutine(routine, enabled, age)
            }
        }
        // 2. Einzelbefehl
        val c = classify(msg)
        return when (c.outcome) {
            "action" -> {
                val act = c.act!!
                val kind = DeviceActionKind.fromKey(act.kindKey)
                val json = toJson(act)
                if (kind == null || DeviceActionParser.parse(json) !is ParseResult.Ok) Outcome.Say(NEEDS_SERVER)
                else if (kind !in enabled) Outcome.Say(disabledText(act.kindKey))
                else Outcome.Run(listOf(json), STARTED)
            }
            "multi" -> Outcome.Say(MULTI)
            "clarify_time" -> Outcome.Say(CLARIFY_TIME)
            "out_of_range" -> Outcome.Say(rangeText(OfflineIntent.findings(msg)))
            else -> Outcome.Say(if (OfflineIntent.mentionsReminder(msg)) REMINDER else NEEDS_SERVER)
        }
    }

    /**
     * #244: Routine aus dem Widget ohne Server (gleiche Regeln wie oben: nur Geräte-Schritte aus dem lokalen Stand,
     * Schalter je Art, Stand höchstens 7 Tage alt). null = die Routine steht nicht im lokalen Stand.
     */
    fun runStoredRoutine(
        name: String,
        enabled: Set<DeviceActionKind>,
        snapshot: OfflineRoutineSnapshot?,
        nowMs: Long = System.currentTimeMillis(),
    ): Outcome? {
        val routine = snapshot?.byName(name) ?: return null
        val age = nowMs - snapshot.syncedAtMs
        if (snapshot.syncedAtMs <= 0L || age > MAX_SNAPSHOT_AGE_MS) return Outcome.Say(STALE)
        return runRoutine(routine, enabled, age)
    }

    /** Nur die Arten, die offline auch als Einzelbefehl gehen — keine Erweiterung der Positivliste über Routinen. */
    private val OFFLINE_KINDS = setOf(
        DeviceActionKind.FLASHLIGHT, DeviceActionKind.VOLUME, DeviceActionKind.OPEN_APP, DeviceActionKind.OPEN_SETTINGS,
        DeviceActionKind.TIMER_ALARM, DeviceActionKind.BRIGHTNESS, DeviceActionKind.LOCK_SCREEN,
    )

    private fun runRoutine(r: OfflineRoutine, enabled: Set<DeviceActionKind>, ageMs: Long): Outcome {
        val run = ArrayList<JSONObject>()
        val skipped = ArrayList<String>()
        val last = r.steps.lastIndex
        val seen = HashSet<String>()
        for ((i, st) in r.steps.withIndex()) {
            val label = st.label.ifBlank { "Schritt ${i + 1}" }
            val dev = st.device
            if (dev == null) { skipped.add("$label braucht Verbindung"); continue }
            val action = (DeviceActionParser.parse(dev) as? ParseResult.Ok)?.action
            if (action == null) { skipped.add("$label ist ungültig"); continue }
            // nie aus einer (manipulierten) Datei: Screenshot gibt es in Routinen nicht, Sperren nur als letzter Schritt
            if (action is DeviceAction.Screenshot || (action is DeviceAction.LockScreen && i != last)) {
                skipped.add("$label ist ungültig"); continue
            }
            if (action.kind !in OFFLINE_KINDS) { skipped.add("$label braucht Verbindung"); continue }
            if (action.kind !in enabled) { skipped.add("$label ist ausgeschaltet"); continue }
            if (run.size >= TurnBudget.MAX_PER_TURN) { skipped.add("$label geht offline nicht mehr, höchstens ${TurnBudget.MAX_PER_TURN} Schritte"); continue }
            if (!seen.add(action.dedupeKey)) { skipped.add("$label steht doppelt"); continue }
            run.add(dev)
        }
        val head = if (run.isEmpty()) "Routine ${r.name}: nichts angestoßen." else "Routine ${r.name}: ${run.size} angestoßen."
        val days = ageMs / (24L * 60 * 60 * 1000)
        val old = if (days >= 1) " Stand von vor $days Tagen." else ""
        val speech = (if (skipped.isEmpty()) head else "$head Ausgelassen: ${skipped.joinToString(". ")}.") + old
        return if (run.isEmpty()) Outcome.Say(speech) else Outcome.Run(run, speech)
    }
}
