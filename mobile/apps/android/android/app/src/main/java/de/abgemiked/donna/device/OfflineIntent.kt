package com.yourcompany.donna.device

import java.util.Locale
import kotlin.math.abs

/**
 * #147 Paket D — Grundbefehle OHNE Server: strenge, deterministische Auswertung der NUTZERNACHRICHT auf dem Gerät.
 *
 * Das ist der Kotlin-Spiegel von `backend/app/services/device_intent.py` (Teilmenge: Taschenlampe, Lautstärke,
 * Timer, Wecker, App/Einstellungen öffnen, Helligkeit, Bildschirm sperren). Die Prüfregeln (Befehlsform, Frage,
 * Verneinung, Teilsätze, Zahlwörter, Dauer, Uhrzeit, Wortlisten) sind dieselben. REINE Logik, keine Android-Aufrufe.
 *
 * Drift-Schutz: Beide Seiten lesen `backend/tests/contracts/offline_commands.json` (Satz -> erwartete Aktion). Die
 * Änderung einer Regel an EINER Seite lässt den Vertragstest der anderen anschlagen. Wer hier ändert, ändert
 * `device_intent.py` mit (und umgekehrt) und ergänzt Zeilen in der Tabelle.
 *
 * Grundsatz wie auf dem Server: lieber einmal zu wenig auslösen als einmal zu viel. Alles Unklare ist KEIN Befehl.
 */
object OfflineIntent {

    // ── Grundlagen ───────────────────────────────────────────────────────────────────────────────────────────

    fun lowerKeep(text: String): String =
        text.lowercase(Locale.ROOT).replace("%", " prozent ").replace("ä", "ae").replace("ö", "oe")
            .replace("ü", "ue").replace("ß", "ss")

    private val NON_ALNUM = Regex("[^a-z0-9]+")

    fun fold(text: String): String =
        NON_ALNUM.replace(lowerKeep(text), " ").trim().split(' ').filter { it.isNotEmpty() }.joinToString(" ")

    /** Editierdistanz (inkl. Vertauschung benachbarter Zeichen) <= limit. */
    private fun osa(a: String, b: String, limit: Int): Boolean {
        if (abs(a.length - b.length) > limit) return false
        var prev2: IntArray? = null
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (prev2 != null && i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    cur[j] = minOf(cur[j], prev2[j - 2] + 1)
                }
            }
            prev2 = prev
            prev = cur
        }
        return prev[b.length] <= limit
    }

    /** Exakt, oder ein Tippfehler (Distanz 1) NUR bei langen, eindeutigen Wörtern (>= 9 Zeichen). */
    fun fuzzyOk(token: String, word: String): Boolean {
        if (token == word) return true
        return word.length >= 9 && osa(token, word, 1)
    }

    private val W_WORDS = setOf(
        "wie", "wann", "was", "wo", "wer", "wem", "wen", "wessen", "warum", "wieso", "weshalb", "welche", "welcher",
        "welches", "welchen", "welchem", "wohin", "woher", "wozu", "womit", "wofuer", "wieviel", "wieviele",
    )
    private val STATE_VERBS = setOf(
        "ist", "sind", "hat", "haben", "war", "waren", "bin", "bist", "seid", "habe", "hast", "hatte", "hatten",
        "wird", "werden",
    )
    private val EXPLAIN = setOf("erklaer", "erklaere", "erklaeren", "sag", "sage", "sagen")
    val NEG = setOf("nicht", "kein", "keine", "keinen", "keinem", "keiner", "keines", "nie", "niemals")
    val ABORT = setOf("vergiss", "vergessen", "vergesse", "stopp", "stop", "nein", "nee")
    private val DELETE = setOf(
        "aus", "ab", "ausschalten", "abstellen", "ausmachen", "loeschen", "loesche", "weg", "stoppen", "beenden",
        "abbrechen", "deaktivieren", "deaktiviere", "entfernen", "verwerfen", "absagen", "abschalten",
    )
    private val REJECT = setOf("wie", "ob", "man", "zeigen", "erklaeren", "sagen", "helfen", "wissen")
    private val LEAD_FILLERS = setOf("bitte", "donna", "hey", "ok", "okay", "und", "dann", "jetzt", "also", "mal")

    private val KIND_IMP: Map<String, Set<String>> = mapOf(
        "flashlight" to setOf("mach", "mache", "schalt", "schalte", "knips", "knipse", "aktivier", "aktiviere",
            "deaktivier", "deaktiviere", "leucht", "leuchte"),
        "volume" to setOf("mach", "mache", "stell", "stelle", "dreh", "drehe", "schalt", "schalte", "setz", "setze",
            "reg", "regle", "bring", "sei"),
        "open" to setOf("oeffne", "starte", "mach", "mache", "ruf", "rufe"),
        "settings" to setOf("oeffne", "zeig", "zeige", "starte", "mach", "mache", "ruf", "rufe"),
        "timer" to setOf("stell", "stelle", "setz", "setze", "mach", "mache", "starte", "start", "erstell", "erstelle",
            "richte", "aktivier", "aktiviere", "weck", "wecke", "erinner", "erinnere"),
        "alarm" to setOf("stell", "stelle", "setz", "setze", "mach", "mache", "erstell", "erstelle", "richte",
            "aktivier", "aktiviere", "weck", "wecke"),
    )
    private val ALL_IMP: Set<String> = KIND_IMP.values.flatten().toSet()
    private val KIND_IMP_SYS: Map<String, Set<String>> = mapOf(
        "brightness" to setOf("mach", "mache", "stell", "stelle", "dreh", "drehe", "setz", "setze", "reg", "regle",
            "schalt", "schalte"),
        "dnd" to setOf("schalt", "schalte", "mach", "mache", "stell", "stelle", "setz", "setze", "aktivier",
            "aktiviere", "deaktivier", "deaktiviere"),
        "lock" to setOf("sperr", "sperre"),
        "screenshot" to setOf("mach", "mache", "nimm", "nehm", "nehme", "erstell", "erstelle"),
        "camera" to setOf("oeffne", "starte", "mach", "mache", "ruf", "rufe", "nimm", "nehm", "nehme"),
    )
    private val ANY_IMP: Set<String> = ALL_IMP + KIND_IMP_SYS.values.flatten().toSet()

    private val VOCAB = setOf(
        "taschenlampe", "handylicht", "handylampe", "taschenleuchte", "licht", "lampe", "am", "handy", "telefon",
        "timer", "stoppuhr", "countdown", "eieruhr", "wecker", "alarm", "musik", "medien", "media", "video", "ton",
        "klingelton", "klingeln", "anruf", "lautstaerke", "volume", "sound",
        "an", "aus", "ein", "stumm", "lautlos", "lauter", "leiser", "leise", "anmachen", "ausmachen", "einschalten",
        "ausschalten", "anschalten", "starten", "schalten", "um", "auf", "fuer", "in", "uhr", "halb", "viertel",
        "dreiviertel", "vor", "nach", "prozent", "minute", "minuten", "min", "stunde", "stunden", "std", "sekunde",
        "sekunden", "sek", "morgens", "abends", "nachts", "frueh", "mittags", "nachmittags", "vormittags", "die", "den",
        "das", "der", "dem", "einen", "eine", "bitte", "etwas", "bisschen", "ohne", "mir", "anderthalb",
        "eineinhalb", "halbe", "halben", "halbstunde", "viertelstunde", "dreiviertelstunde",
    )
    private val ATTACHED = Regex("^(\\d+)(sek|min|std|h|s)$")

    private fun isDigits(s: String) = s.isNotEmpty() && s.length <= 9 && s.all { it in '0'..'9' }

    private fun vocab(tok: String): Boolean =
        tok in VOCAB || tok.endsWith("einhalb") || isDigits(tok) || toInt(tok) != null || ATTACHED.matches(tok) ||
            fuzzyOk(tok, "taschenlampe")

    data class Sent(
        val text: String,
        val tokens: List<String>,
        val question: Boolean,
        val low: String = "",
        val neg: Boolean = false,
        val imp: String? = null,
        val polite: Boolean = false,
        val short: Boolean = false,
    )

    /** Teilsätze: Trennung an . ! ? ; Zeilenumbruch und Komma (nicht in Dezimalzahlen), NICHT am Doppelpunkt. */
    fun sentences(raw: String): List<Sent> {
        val low = lowerKeep(raw)
        val segs = ArrayList<Pair<String, Boolean>>()
        val buf = StringBuilder()
        for (i in low.indices) {
            val ch = low[i]
            val betweenDigits = i > 0 && low[i - 1].isDigit() && i + 1 < low.length && low[i + 1].isDigit()
            if (ch in "!?;\n" || (ch in ".," && !betweenDigits)) {
                segs.add(buf.toString() to (ch == '?'))
                buf.setLength(0)
            } else {
                buf.append(ch)
            }
        }
        if (buf.isNotEmpty()) segs.add(buf.toString() to false)
        val out = ArrayList<Sent>()
        for ((seg, endedQ) in segs) {
            val text = fold(seg)
            if (text.isEmpty()) continue
            val toks = text.split(' ')
            val core = toks.toMutableList()
            while (core.isNotEmpty() && core[0] in LEAD_FILLERS) core.removeAt(0)
            if (core.isEmpty()) continue
            val tokset = core.toSet()
            val rejects = tokset.any { it in REJECT }
            val imp = if (core[0] in ANY_IMP && !rejects && !(core.size > 2 && core[1] == "mir" &&
                    core[2] in setOf("wie", "was", "ob", "warum", "wann", "wo"))) core[0] else null
            val polite = !rejects && (
                (core[0] in setOf("kannst", "koenntest", "wuerdest") && core.size > 1 && core[1] == "du") ||
                    (core[0] == "ich" && core.size > 1 && core[1] in setOf("moechte", "will", "brauche", "haette"))
                )
            val shortToks = core.filter { it != "bitte" }
            val short = shortToks.isNotEmpty() && shortToks.size <= 6 && shortToks.all { vocab(it) }
            val politeQ = core[0] in setOf("kannst", "koenntest", "wuerdest")
            val asks = core[0] in W_WORDS || core[0] in STATE_VERBS || core[0] in EXPLAIN || "ob" in tokset ||
                (endedQ && !politeQ)
            val neg = tokset.any { it in NEG || it in ABORT }
            out.add(Sent(text, toks, asks, seg, neg, imp, polite, short))
        }
        return out
    }

    private val NOT_ANYMORE_MUTE = Regex("\\bnicht mehr stumm\\w*")

    /** Befehls-Teilsätze: Imperativ, höfliche Bitte oder Kurzform; keine Fragen, KEINE Verneinung/Abbruch. */
    fun commands(raw: String, allowUnmute: Boolean = false): List<Sent> {
        val out = ArrayList<Sent>()
        for (c in sentences(raw)) {
            if (c.question || !(c.imp != null || c.polite || c.short)) continue
            if (c.neg) {
                val hits = c.tokens.filter { it in NEG || it in ABORT }
                if (!(allowUnmute && NOT_ANYMORE_MUTE.containsMatchIn(c.text) && hits == listOf("nicht"))) continue
            }
            out.add(c)
        }
        return out
    }

    private fun form(kind: String, c: Sent): Boolean =
        (c.imp != null && c.imp in KIND_IMP.getValue(kind)) || c.polite || c.short

    private fun deleteWord(c: Sent): Boolean = c.tokens.any { it in DELETE }

    // ── Zahlwörter ───────────────────────────────────────────────────────────────────────────────────────────

    private val UNITS = mapOf(
        "null" to 0, "ein" to 1, "eine" to 1, "einen" to 1, "einem" to 1, "einer" to 1, "eins" to 1, "zwei" to 2,
        "drei" to 3, "vier" to 4, "fuenf" to 5, "sechs" to 6, "sieben" to 7, "acht" to 8, "neun" to 9, "zehn" to 10,
        "elf" to 11, "zwoelf" to 12, "dreizehn" to 13, "vierzehn" to 14, "fuenfzehn" to 15, "sechzehn" to 16,
        "siebzehn" to 17, "achtzehn" to 18, "neunzehn" to 19,
    )
    private val TENS = mapOf(
        "zwanzig" to 20, "dreissig" to 30, "vierzig" to 40, "fuenfzig" to 50, "sechzig" to 60, "siebzig" to 70,
        "achtzig" to 80, "neunzig" to 90,
    )
    private val ARTICLES = setOf("ein", "eine", "einen", "einem", "einer")

    fun toInt(tok: String): Int? {
        if (isDigits(tok)) return if (tok.length <= 4) tok.toInt() else null
        UNITS[tok]?.let { return it }
        TENS[tok]?.let { return it }
        val idx = tok.indexOf("und")
        if (idx >= 0) {
            val a = tok.substring(0, idx)
            val b = tok.substring(idx + 3)
            val ua = UNITS[a]
            if (ua != null && ua in 1..9 && b in TENS) return ua + TENS.getValue(b)
        }
        return null
    }

    // ── Dauer (Timer) ────────────────────────────────────────────────────────────────────────────────────────

    private val SEC = setOf("sekunde", "sekunden", "sek", "sec")
    private val MIN = setOf("minute", "minuten", "min")
    private val HOUR = setOf("stunde", "stunden", "std", "h")
    private val DEC_RE = Regex("(\\d+)[.,](\\d+)\\s*(stunden|stunde|std|h|minuten|minute|min)\\b")

    private const val MAX_NUM = 100_000L

    private fun clauseSeconds(clause: String): Long {
        var low = lowerKeep(clause)
        var seconds = 0L
        var bad = false
        low = DEC_RE.replace(low) { m ->
            val whole = m.groupValues[1].toLongOrNull()?.takeIf { it <= MAX_NUM } ?: run { bad = true; 0L }
            val frac = m.groupValues[2]
            val unit = m.groupValues[3]
            if (unit.startsWith("stund") || unit.startsWith("std") || unit.startsWith("h")) {
                val extra = when (frac) {
                    "5", "50" -> 1800L
                    "25" -> 900L
                    "75" -> 2700L
                    "0", "00" -> 0L
                    else -> null
                }
                if (extra == null) bad = true else seconds += whole * 3600L + extra
            } else if (frac.trim('0').isEmpty()) {
                seconds += whole * 60L
            } else {
                bad = true
            }
            " "
        }
        if (bad) return 0L
        val toks = fold(low).split(' ').filter { it.isNotEmpty() }
        var i = 0
        while (i < toks.size) {
            val t = toks[i]
            val nxt = if (i + 1 < toks.size) toks[i + 1] else ""
            if ((t == "anderthalb" || t == "eineinhalb") && nxt in HOUR) { seconds += 5400L; i += 2; continue }
            if (t.endsWith("einhalb") && nxt in HOUR && toInt(t.dropLast("einhalb".length)) != null) {
                seconds += (toInt(t.dropLast("einhalb".length)) ?: 0) * 3600L + 1800L
                i += 2
                continue
            }
            if (t in setOf("halbe", "halben", "halbstunde") && (t == "halbstunde" || nxt in HOUR)) {
                seconds += 1800L
                i += if (t == "halbstunde") 1 else 2
                continue
            }
            if (t == "viertelstunde" || (t == "viertel" && nxt in HOUR)) {
                seconds += 900L
                i += if (t == "viertelstunde") 1 else 2
                continue
            }
            if (t == "dreiviertelstunde" || (t == "dreiviertel" && nxt in HOUR)) {
                seconds += 2700L
                i += if (t == "dreiviertelstunde") 1 else 2
                continue
            }
            val m = ATTACHED.matchEntire(t)
            if (m != null) {
                val n = m.groupValues[1].toLongOrNull()?.takeIf { it <= MAX_NUM } ?: return 0L
                val u = m.groupValues[2]
                seconds += n * (if (u == "std" || u == "h") 3600L else if (u == "min") 60L else 1L)
                i += 1
                continue
            }
            val n = toInt(t)
            if (n != null) {
                if (nxt in SEC) { seconds += n; i += 2; continue }
                if (nxt in MIN) { seconds += n * 60L; i += 2; continue }
                if (nxt in HOUR) {
                    seconds += n * 3600L
                    i += 2
                    val v = if (i < toks.size) toInt(toks[i]) else null
                    val after = if (i + 1 < toks.size) toks[i + 1] else ""
                    if (v != null && v in 1..59 && after !in (SEC + MIN + HOUR + setOf("uhr", "prozent"))) {
                        seconds += v * 60
                        i += 1
                    }
                    continue
                }
            }
            i += 1
        }
        if (seconds == 0L) {
            val set = toks.toSet()
            if ("timer" in set || toks.any { it.startsWith("erinner") || it.startsWith("weck") }) {
                for (j in 0 until toks.size - 1) {
                    if (toks[j] != "in") continue
                    val n = toInt(toks[j + 1])
                    val after = if (j + 2 < toks.size) toks[j + 2] else ""
                    if (n != null && n > 0 && after in setOf("", "minuten", "minute", "min", "bitte") && toks[j + 1] !in ARTICLES) {
                        seconds = n * 60L
                        break
                    }
                }
            }
        }
        return seconds
    }

    private val COMMA_SPLIT = Regex("(?<!\\d),|,(?!\\d)")

    fun sentDurations(low: String): Set<Int> {
        val clauses = COMMA_SPLIT.split(low).filter { it.isNotBlank() }
        val sums = clauses.map { clauseSeconds(it) }
        val out = sums.filter { it > 0 }.map { ((it + 59) / 60).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }.toMutableSet()
        val total = sums.sum()
        if (total > 0) out.add(((total + 59) / 60).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        return out
    }

    // ── Uhrzeit (Wecker) ─────────────────────────────────────────────────────────────────────────────────────

    private val AM = setOf("morgens", "frueh", "vormittags")
    private val NIGHT = setOf("nachts")
    private val PM = setOf("abends", "nachmittags", "mittags")
    private val NOT_HOUR_AFTER = setOf(
        "minuten", "minute", "min", "stunden", "stunde", "std", "prozent", "sekunden", "sek", "tage", "tagen", "tag",
        "wochen", "woche", "mal",
    )

    private fun expand(h0: Int, m: Int, part: String?): Set<Pair<Int, Int>> {
        var h = h0
        if (!(h in 0..24 && m in 0..59)) return emptySet()
        if (h == 24) h = 0
        if (h > 12 || h == 0) return setOf(h to m)
        if (part == "night") return setOf((if (h == 12) 0 else if (h >= 7) h + 12 else h) to m)
        if (part == "am") return setOf((if (h == 12) 0 else h) to m)
        if (part == "pm") return setOf((if (h == 12) 12 else h + 12) to m)
        return if (h != 12) setOf(h to m, (if (h < 12) h + 12 else h) to m) else setOf(12 to m, 0 to m)
    }

    private val CLOCK_RE = Regex("\\b(\\d{1,2}):(\\d{2})\\b")
    private val CLOCK_DOT_RE = Regex("\\b(\\d{1,2})\\.(\\d{2})\\s*uhr\\b")

    fun sentTimes(raw: String): Set<Pair<Int, Int>> {
        if (raw.isBlank()) return emptySet()
        var low = lowerKeep(raw)
        val cands = ArrayList<Pair<Int, Int>>()
        low = CLOCK_RE.replace(low) { m -> cands.add(m.groupValues[1].toInt() to m.groupValues[2].toInt()); " " }
        low = CLOCK_DOT_RE.replace(low) { m -> cands.add(m.groupValues[1].toInt() to m.groupValues[2].toInt()); " " }
        val rawToks = fold(low).split(' ').filter { it.isNotEmpty() }
        val toks = ArrayList<String>()
        for ((i, t) in rawToks.withIndex()) {
            val n = toInt(t)
            val nxt = if (i + 1 < rawToks.size) rawToks[i + 1] else ""
            if (n != null && (t !in ARTICLES || nxt == "uhr")) toks.add(n.toString()) else toks.add(t)
        }
        var part: String? = null
        val tset = toks.toSet()
        if (tset.any { it in AM }) part = "am"
        if (tset.any { it in PM }) part = if (part == "am") null else "pm"
        if (tset.any { it in NIGHT }) part = "night"
        val used = BooleanArray(toks.size)

        fun num(i: Int): Int? = if (i in toks.indices && isDigits(toks[i])) toks[i].toInt() else null

        val explicit = ArrayList<Pair<Int, Int>>()
        for (i in toks.indices) {
            if (used[i]) continue
            val t = toks[i]
            val n1 = num(i + 1)
            if (t == "halb" && n1 != null && n1 in 1..12) {
                explicit.add(((n1 - 1).let { if (it == 0) 12 else it }) to 30)
                used[i] = true; used[i + 1] = true
            } else if ((t == "viertel" || t == "dreiviertel") && n1 != null && n1 in 1..12) {
                explicit.add(((n1 - 1).let { if (it == 0) 12 else it }) to (if (t == "viertel") 15 else 45))
                used[i] = true; used[i + 1] = true
            } else if (t == "viertel" && i + 2 < toks.size && toks[i + 1] in setOf("nach", "vor") && num(i + 2) != null) {
                val h = num(i + 2)!!
                explicit.add(if (toks[i + 1] == "nach") h to 15 else ((h - 1).let { if (it == 0) 12 else it }) to 45)
                used[i] = true; used[i + 1] = true; used[i + 2] = true
            } else if (isDigits(t) && i + 2 < toks.size && toks[i + 1] in setOf("nach", "vor") && num(i + 2) != null &&
                t.toInt() in 1..59
            ) {
                val x = t.toInt()
                val h = num(i + 2)!!
                explicit.add(if (toks[i + 1] == "nach") h to x else ((h - 1).let { if (it == 0) 12 else it }) to (60 - x))
                used[i] = true; used[i + 1] = true; used[i + 2] = true
            }
        }
        for (i in toks.indices) {
            val t = toks[i]
            if (used[i] || !isDigits(t)) continue
            if (i + 1 < toks.size && toks[i + 1] == "uhr") {
                val m = num(i + 2)
                val minutes = if (m != null && m in 0..59 && (i + 3 >= toks.size || toks[i + 3] !in NOT_HOUR_AFTER)) m else 0
                explicit.add(t.toInt() to minutes)
                used[i] = true; used[i + 1] = true
                if (minutes != 0 && m != null) used[i + 2] = true
            } else if (i > 0 && toks[i - 1] in setOf("um", "fuer", "auf", "gegen", "ab", "wecker", "alarm") &&
                (i + 1 >= toks.size || toks[i + 1] !in NOT_HOUR_AFTER) && t.toInt() <= 24
            ) {
                explicit.add(t.toInt() to 0)
                used[i] = true
            }
        }
        val out = HashSet<Pair<Int, Int>>()
        for ((h, m) in cands + explicit) out += expand(h, m, part)
        return out
    }

    // ── Taschenlampe ─────────────────────────────────────────────────────────────────────────────────────────

    private val ON_PREFIX = listOf("anmach", "einschalt", "anschalt", "anknips", "aktivier", "einmach")
    private val OFF_PREFIX = listOf("ausmach", "ausschalt", "abschalt", "ausknips", "deaktivier")
    private val PHONE_LIGHT = Regex(
        "\\b(?:licht|lampe|leuchte)\\b.{0,12}\\b(?:am|vom|auf dem|an meinem|auf meinem|von meinem)\\b" +
            ".{0,10}\\b(?:handy|telefon|smartphone)\\b",
    )
    private val HANDY_LIGHT = Regex("\\bhandy (?:licht|lampe)\\b")

    private fun flashKey(s: Sent): Boolean =
        s.tokens.any { fuzzyOk(it, "taschenlampe") || it in setOf("handylicht", "handylampe", "taschenleuchte") } ||
            PHONE_LIGHT.containsMatchIn(s.text) || HANDY_LIGHT.containsMatchIn(s.text)

    private fun flashDir(s: Sent): String? {
        val on = s.tokens.any { it == "an" || it == "ein" || ON_PREFIX.any { p -> it.startsWith(p) } }
        val off = s.tokens.any { it == "aus" || OFF_PREFIX.any { p -> it.startsWith(p) } }
        if (on == off) return null
        return if (on) "on" else "off"
    }

    // ── Lautstärke ───────────────────────────────────────────────────────────────────────────────────────────

    private val CH_MEDIA = setOf("musik", "medien", "media", "video", "videos", "lied", "lieder", "song", "songs")
    private val MUTE_RE = Regex("\\b(?:ton|klingelton|klingeln|musik|medien|anruf|anrufe|gespraech)\\s+aus\\b")
    private val UNMUTE_RE = Regex("\\bton an\\b|\\bnicht mehr stumm\\b|\\bwieder laut\\b|\\bstummschaltung (?:auf|aus)\\b")

    private fun channels(tokens: List<String>): Set<String> {
        val ch = HashSet<String>()
        for (t in tokens) {
            when {
                t in CH_MEDIA -> ch.add("media")
                t.startsWith("klingel") -> ch.add("ring")
                t.startsWith("wecker") || t == "alarm" -> ch.add("alarm")
                t.startsWith("anruf") || t in setOf("gespraech", "telefonat", "hoerer") -> ch.add("call")
            }
        }
        return ch
    }

    private fun percents(raw: String): Set<Int> {
        val toks = fold(raw).split(' ').filter { it.isNotEmpty() }
        val out = HashSet<Int>()
        for (i in 1 until toks.size) {
            if (toks[i] != "prozent") continue
            val n = toInt(toks[i - 1])
            if (n != null && n <= 100) out.add(n)
            if (Regex("^\\d{1,3}$").matches(toks[i - 1]) && toks[i - 1].toInt() <= 100) out.add(toks[i - 1].toInt())
        }
        return out
    }

    private class VolWords(val up: Boolean, val down: Boolean, val mute: Boolean, val unmute: Boolean,
                           val volume: Boolean, val percent: Boolean)

    private fun volWords(s: Sent): VolWords {
        val t = s.tokens
        return VolWords(
            up = "lauter" in t,
            down = "leiser" in t || "leise" in t,
            mute = (t.any { it.startsWith("stumm") || it == "lautlos" } || MUTE_RE.containsMatchIn(s.text)) &&
                !NOT_ANYMORE_MUTE.containsMatchIn(s.text),
            unmute = UNMUTE_RE.containsMatchIn(s.text),
            volume = t.any { fuzzyOk(it, "lautstaerke") || it == "volume" },
            percent = percents(s.text).isNotEmpty(),
        )
    }

    private val VOL_AUF = Regex("\\b(?:lautstaerke|volume)\\s+auf\\b")

    private fun trigVolume(s: Sent): Boolean {
        if (!form("volume", s)) return false
        val w = volWords(s)
        val chan = channels(s.tokens).isNotEmpty() || "ton" in s.tokens || "sound" in s.tokens
        if (w.percent && VOL_AUF.containsMatchIn(s.text)) return true
        if (w.up || w.mute || w.unmute || w.volume || "leiser" in s.tokens || "leise" in s.tokens) return true
        return w.percent && chan
    }

    // ── App / Einstellungen öffnen ───────────────────────────────────────────────────────────────────────────

    val APP_FILLER = setOf(
        "app", "apps", "die", "das", "der", "den", "dem", "des", "mal", "und", "oder", "ein", "eine", "einen", "einem",
        "mir", "mich", "uns", "bitte", "auf", "mit", "fuer", "zu", "im", "in", "an", "es", "ich", "du", "doch", "noch",
        "mach", "stell",
    )
    private val OPEN_RE = Regex("\\b(?:mach\\w*|ruf\\w*)\\b(?:\\s+\\w+){1,4}\\s+auf\\b(?!\\s+\\d)")
    private val OPEN_INF = setOf("oeffnen", "starten", "oeffne", "starte")

    private fun openVerb(s: Sent): Boolean {
        if (!form("open", s)) return false
        if (s.imp == "oeffne" || s.imp == "starte") return true
        if ("prozent" in s.tokens) return s.tokens.any { it in OPEN_INF }
        return OPEN_RE.containsMatchIn(s.text) || s.tokens.any { it in OPEN_INF }
    }

    private val PAGE_TERMS: Map<String, Regex> = linkedMapOf(
        "wifi" to Regex("\\b(?:wlan\\w*|wifi\\w*|wi fi|w lan)"),
        "bluetooth" to Regex("\\bbluetooth\\w*"),
        "mobile_data" to Regex("\\b(?:mobile daten|mobilfunk\\w*|datenverbindung|mobiles internet)"),
        "display" to Regex("\\b(?:display\\w*|bildschirm\\w*|helligkeit)"),
        "sound" to Regex("\\b(?:ton|toene|sound\\w*|klingelton\\w*|klingeltoene)\\b"),
        "battery" to Regex("\\b(?:akku\\w*|batterie\\w*|energiespar\\w*)"),
        "location" to Regex("\\b(?:standort\\w*|ortung\\w*|gps)\\b"),
        "notifications" to Regex("\\bbenachrichtigung\\w*"),
        "app_info" to Regex("\\b(?:app info\\w*|appinfo\\w*|app informationen)"),
        "accessibility" to Regex("\\b(?:bedienungshilfe\\w*|barrierefreiheit)"),
        "airplane" to Regex("\\b(?:flugmodus|flugzeugmodus)"),
        "internet_panel" to Regex("\\b(?:internet|netzwerk\\w*)\\b"),
        "volume_panel" to Regex("\\blautstaerke\\w*"),
    )

    private fun settingsVerb(s: Sent): Boolean {
        if (!form("settings", s)) return false
        if (s.imp in setOf("oeffne", "zeig", "zeige", "starte")) return true
        if ("prozent" in s.tokens) return s.tokens.any { it in setOf("oeffnen", "oeffne", "starte") }
        return OPEN_RE.containsMatchIn(s.text) || s.tokens.any { it in setOf("oeffnen", "oeffne", "starte") }
    }

    private fun pagesIn(s: Sent): List<String> = PAGE_TERMS.filter { it.value.containsMatchIn(s.text) }.keys.toList()

    // ── Timer / Wecker ───────────────────────────────────────────────────────────────────────────────────────

    private val REMIND_IN = Regex("\\b(erinner\\w*|weck\\w*)\\s+(?:mich|uns)\\s+in\\b")
    private val REMIND_CONTENT = Regex("\\b(?:an|dass|ans|zum|zur|daran)\\b")
    private val WECK_MICH = Regex("\\bweck\\w*\\s+(?:mich|uns)\\b")

    private fun timerTrig(s: Sent): Boolean {
        if (!form("timer", s) || deleteWord(s)) return false
        val m = REMIND_IN.find(s.text)
        if (m != null) {
            if (!(m.groupValues[1].startsWith("erinner") && REMIND_CONTENT.containsMatchIn(s.text.substring(m.range.last + 1)))) {
                return true
            }
        }
        return s.tokens.any { it in setOf("timer", "stoppuhr", "countdown", "eieruhr") }
    }

    private fun alarmTrig(s: Sent): Boolean {
        if (!form("alarm", s) || deleteWord(s)) return false
        if (WECK_MICH.containsMatchIn(s.text)) return true
        return s.tokens.any { it == "wecker" || it == "alarm" }
    }

    // ── Abschnitte (Helligkeit, Sperren) ─────────────────────────────────────────────────────────────────────

    private val SEG_SPLIT = setOf("und", "dann", "danach", "ausserdem", "anschliessend", "sowie", "aber")
    private val SEG_NOISE = setOf("bitte", "mal", "doch", "donna", "hey", "ok", "okay", "also", "jetzt", "gleich",
        "sofort", "schnell", "kurz", "noch")
    private val POLITE_TOKS = setOf("kannst", "koenntest", "wuerdest", "du", "ich", "moechte", "will", "brauche",
        "haette", "mir", "uns")
    private val SYS_VOCAB = setOf(
        "helligkeit", "bildschirm", "display", "heller", "dunkler", "aufhellen", "abdunkeln", "bildschirmhelligkeit",
        "displayhelligkeit", "hell", "dunkel",
    )
    private val LIGHT_WORDS = setOf(
        "licht", "lichter", "lampe", "lampen", "leuchte", "leuchten", "steckdose", "heizung", "ventilator", "fernseher",
        "tv", "klima", "klimaanlage", "rollo", "rolladen", "rollladen", "tesla", "taschenlampe", "handylicht",
        "handylampe", "taschenleuchte", "lichterkette", "stehlampe", "deckenlampe", "wohnzimmer", "schlafzimmer",
        "kueche", "bad", "flur",
    )
    private val COND_WORDS = setOf(
        "wenn", "falls", "sobald", "solange", "nachdem", "bevor", "waehrend", "bis", "fuer", "erst", "spaeter", "nachher",
        "morgen", "uebermorgen", "abend", "abends", "nachts", "mittags", "vormittags", "nachmittags", "frueh",
        "morgens", "wieder", "immer", "automatisch", "jedes", "jeden", "taeglich", "uhr", "minute", "minuten", "min",
        "stunde", "stunden", "std", "sekunde", "sekunden", "sek", "tag", "tage", "woche", "wochen", "anruf", "anrufe",
        "anrufen", "anrufer", "nachricht", "nachrichten", "sms", "whatsapp", "mails", "mail", "bei", "in", "um",
        "nach", "vor", "zu", "sonst", "ausser", "ohne",
    )
    private val COND_LEAD = setOf("wenn", "falls", "sobald", "solange", "nachdem", "bevor", "waehrend", "bis", "erst",
        "spaeter", "nachher", "fuer", "in", "um", "nach", "sonst")
    private val COND_OPENERS = setOf("wenn", "falls", "sobald", "solange", "nachdem", "bevor", "waehrend")
    private val LOCK_FOREIGN = setOf("karte", "karten", "konto", "konten", "sim", "simkarte", "pin", "kreditkarte",
        "bankkarte", "nummer", "nummern", "kontakt", "kontakte", "ec", "account", "zugang")
    private val BR_NOUN = setOf("helligkeit", "bildschirm", "display", "displays", "bildschirme")
    private val WORD_NUM = mapOf("hundert" to 100, "einhundert" to 100)

    class Seg(
        val toks: List<String>,
        val lead: String,
        val polite: Boolean,
        val text: String,
        val sentToks: List<String>,
        val cond: Boolean,
    )

    private fun segs(s: Sent, condIn: Boolean): List<Seg> {
        val rejects = s.tokens.any { it in REJECT }
        val plain = s.tokens.joinToString(" ").replace("guten morgen", " ").replace("gute nacht", " ")
            .replace("guten abend", " ").split(' ').filter { it.isNotEmpty() }
        val cond = condIn || plain.any { it in COND_WORDS }
        val out = ArrayList<Seg>()
        var cur = ArrayList<String>()
        fun flush() {
            val core = cur.toMutableList()
            while (core.isNotEmpty() && core[0] in LEAD_FILLERS) core.removeAt(0)
            val c2 = core.filter { it !in SEG_NOISE }
            if (c2.isNotEmpty()) out.add(Seg(c2, if (rejects) "" else c2[0], s.polite && !rejects, c2.joinToString(" "), plain, cond))
        }
        for (t in plain) {
            if (t in SEG_SPLIT) { flush(); cur = ArrayList() } else cur.add(t)
        }
        flush()
        return out
    }

    fun cmdSegs(raw: String): List<Seg> {
        val out = ArrayList<Seg>()
        val sents = sentences(raw)
        for ((i, s) in sents.withIndex()) {
            if (s.question || s.neg) continue
            val nxt = if (i + 1 < sents.size) sents[i + 1].tokens.firstOrNull { it !in LEAD_FILLERS } ?: "" else ""
            val prev = if (i > 0) sents[i - 1].tokens.firstOrNull { it !in LEAD_FILLERS } ?: "" else ""
            out.addAll(segs(s, nxt in COND_LEAD || prev in COND_OPENERS))
        }
        return out
    }

    private fun sysShort(seg: Seg): Boolean = seg.toks.size <= 6 && seg.toks.all { vocab(it) || it in SYS_VOCAB }

    private fun brNoun(t: String) = t in BR_NOUN || (t.endsWith("helligkeit") && t.length > 10)

    private fun brDir(toks: List<String>): String? {
        val up = toks.any { it == "heller" || it == "aufhellen" || it.startsWith("aufhell") }
        val down = toks.any { it == "dunkler" || it == "abdunkeln" || it.startsWith("abdunkel") }
        if (up == down) return null
        return if (up) "up" else "down"
    }

    private fun brPercents(toks: List<String>): Set<Int> {
        val found = HashSet<Int>()
        for (i in 0 until toks.size - 1) {
            if (toks[i] != "auf") continue
            val n = WORD_NUM[toks[i + 1]] ?: toInt(toks[i + 1])
            val nxt = if (i + 2 < toks.size) toks[i + 2] else ""
            if (n != null && n in 1..100 && (nxt == "" || nxt == "prozent")) found.add(n)
        }
        return found
    }

    private val BR_ALLOWED = setOf(
        "mach", "mache", "machen", "stell", "stelle", "stellen", "dreh", "drehe", "drehen", "setz", "setze", "setzen",
        "reg", "regle", "schalt", "schalte", "den", "die", "das", "der", "dem", "mein", "meine", "meinen", "meines",
        "meinem", "dein", "deine", "deinen", "handy", "handys", "telefon", "smartphone", "auf", "prozent", "etwas",
        "bisschen", "ein", "eine", "einen", "bitte", "heller", "dunkler", "aufhellen", "abdunkeln", "hell", "dunkel",
    ) + BR_NOUN

    private fun brAllowed(t: String) =
        t in BR_ALLOWED || brNoun(t) || isDigits(t) || toInt(t) != null || t in WORD_NUM

    /** (Richtung, Prozent) wenn der Abschnitt einen Helligkeits-BEFEHL trägt, sonst null. */
    private fun brSeg(seg: Seg): Pair<String?, Int?>? {
        val toks = seg.toks
        if (seg.cond || toks.none { brNoun(it) }) return null
        if (toks.any { it in LIGHT_WORDS } || toks.any { fuzzyOk(it, "taschenlampe") }) return null
        val allowed = if (seg.polite) POLITE_TOKS else emptySet()
        if (!toks.all { brAllowed(it) || it in allowed }) return null
        if (!(seg.lead in KIND_IMP_SYS.getValue("brightness") || seg.polite || sysShort(seg))) return null
        if (brPercents(seg.sentToks).size > 1) return null
        val whole = seg.sentToks
        if (whole.any { it == "heller" || it == "aufhellen" || it == "dunkler" || it == "abdunkeln" } && brDir(whole) == null) return null
        val d = brDir(toks)
        val ps = brPercents(toks)
        val p = if (ps.size == 1) ps.first() else null
        if (d == null && p == null) return null
        return d to p
    }

    private val LOCK_VERBS = setOf("sperr", "sperre", "sperren")
    private val LOCK_OBJ = setOf("bildschirm", "display", "handy", "telefon", "smartphone")
    private val LOCK_ALLOWED = LOCK_VERBS + LOCK_OBJ + setOf("das", "den", "dein", "mein", "meine", "meinen", "deinen", "ein")

    private fun lockSeg(seg: Seg): Boolean {
        val toks = seg.toks
        if (seg.cond || !(toks.any { it in LOCK_VERBS } && toks.any { it in LOCK_OBJ })) return false
        if (seg.sentToks.any { it in LOCK_FOREIGN }) return false
        val allowed = if (seg.polite) LOCK_ALLOWED + POLITE_TOKS else LOCK_ALLOWED
        return toks.all { it in allowed }
    }

    // ── Auswertung: Nutzernachricht -> Kandidaten ────────────────────────────────────────────────────────────

    /**
     * Nur OFFLINE strenger als der Server (dort entscheidet zusätzlich das Modell und die Karte): Bedingungs-, Zeit-
     * und Wahlwörter machen einen Satz zu keinem Sofort-Befehl ("wenn", "morgen", "oder" ...).
     */
    private val OFFLINE_COND = setOf(
        "wenn", "falls", "sobald", "solange", "nachdem", "bevor", "waehrend", "bis", "erst", "spaeter", "nachher",
        "morgen", "uebermorgen", "jeden", "jedes", "taeglich", "immer", "automatisch", "wieder", "oder", "sonst",
        "ausser", "noch", "zweiten", "zweite", "weiteren", "weitere", "anderen", "andere",
    )

    /** Eine erkannte Geräte-Aktion in der Wire-Form des Servers (`action` + Werte, ohne `type`). */
    data class Act(val name: String, val params: Map<String, Any> = emptyMap()) {
        /** Aktionsart (Schalter) laut Positivliste. */
        val kindKey: String
            get() = when (name) {
                "set_timer", "set_alarm" -> "timer_alarm"
                "set_brightness" -> "brightness"
                else -> name
            }
    }

    /** Eine Beobachtung der Auswertung: eine eindeutige Aktion, oder ein benannter Grund, warum keine entsteht. */
    sealed class Finding {
        data class Action(val act: Act) : Finding()
        /** Mehrere Dauern/Timer in einem Satz ("10 Minuten oder 15", "und noch einen"): nie raten. */
        object Several : Finding()
        /** Uhrzeit ohne Tageshälfte ("Wecker um 7"): nie raten. */
        object AmbiguousTime : Finding()
        /** Wert außerhalb der erlaubten Grenzen (Helligkeit unter 5 Prozent). */
        data class OutOfRange(val what: String) : Finding()
    }

    private val NOT_APP_WORDS = setOf(
        "timer", "stoppuhr", "countdown", "eieruhr", "wecker", "alarm", "taschenlampe", "handylicht", "handylampe",
        "taschenleuchte", "lautstaerke", "volume", "helligkeit", "bildschirm", "display", "minute", "minuten", "stunde",
        "stunden", "sekunden", "uhr", "prozent",
        "tuer", "tor", "garage", "garagentor", "fenster", "rollo", "rolladen", "auto", "fernseher", "licht", "lampe", "neu", "an",
        "aus", "zu", "ab", "ein", "dem", "von", "auf",
    )
    private val CAMERA_WORDS = setOf("kamera", "fotokamera", "videokamera", "handykamera", "kameraapp")

    private val VOL_FILL = setOf(
        "die", "den", "das", "der", "dem", "mir", "bitte", "etwas", "bisschen", "auf", "prozent", "handy", "telefon",
        "nicht", "mehr", "wieder", "laut", "an", "aus", "ein", "um", "ton", "sound", "volume", "lautstaerke", "und",
    )
    private val VOL_WORDS = CH_MEDIA + setOf("klingelton", "klingeln", "wecker", "alarm", "anruf", "anrufe", "gespraech",
        "telefonat", "hoerer", "lauter", "leiser", "leise", "stumm", "lautlos", "stummschaltung")

    /** Offline nur, wenn der GANZE Satz aus Befehlsverb, Kanal-/Richtungswörtern und Zahlen besteht ("Mach den Fernseher leiser" nie). */
    private fun volumeClean(s: Sent): Boolean {
        val toks = s.tokens.filter { it !in LEAD_FILLERS && it !in POLITE_TOKS }
        if (s.imp == "sei") return false
        val verbs = KIND_IMP.getValue("volume")
        return toks.all {
            it in verbs || it in VOL_FILL || it in VOL_WORDS || it.startsWith("klingel") || it.startsWith("wecker") ||
                it.startsWith("anruf") || it.startsWith("stumm") || isDigits(it) || toInt(it) != null ||
                fuzzyOk(it, "lautstaerke")
        }
    }

    private val ERINNER_FILL = setOf("mich", "uns", "in", "bitte", "minute", "minuten", "min", "stunde", "stunden", "std",
        "sekunde", "sekunden", "sek", "donna") + POLITE_TOKS

    /** "Erinner mich in 5 Minuten Anna anzurufen" ist eine Erinnerung MIT Inhalt (braucht den Server), kein Timer. */
    private fun reminderWithContent(s: Sent): Boolean {
        if (s.tokens.none { it.startsWith("erinner") } || "timer" in s.tokens) return false
        val rest = s.tokens.filter {
            !it.startsWith("erinner") && it !in ERINNER_FILL && !isDigits(it) && toInt(it) == null && it !in LEAD_FILLERS
        }
        return rest.isNotEmpty()
    }

    private val TIMER_NOUNS = setOf("timer", "stoppuhr", "countdown", "eieruhr")

    private fun appName(s: Sent): String? {
        val toks = s.tokens.toMutableList()
        while (toks.isNotEmpty() && toks[0] in LEAD_FILLERS) toks.removeAt(0)
        val name: List<String> = when {
            s.imp == "oeffne" || s.imp == "starte" -> toks.drop(1)
            s.imp == "mach" || s.imp == "mache" -> {
                val aufIdx = toks.lastIndexOf("auf")
                if (aufIdx <= 1) return null
                toks.subList(1, aufIdx)
            }
            else -> {
                val idx = toks.indexOfFirst { it in OPEN_INF }
                if (idx < 0) return null
                val head = toks.subList(0, idx)
                val skip = head.indexOfFirst { it !in POLITE_TOKS && it != "bitte" }
                if (skip < 0) return null
                head.drop(skip)
            }
        }
        val words = name.filter { it !in APP_FILLER }
        if (words.isEmpty() || words.size > 3) return null
        if (words.joinToString("").length < 3) return null
        // Kamera ist eine eigene Aktionsart (offline noch nicht); Aktionswörter und Zahlen im "Namen" heißen: das war
        // gar kein App-Name ("Starte einen Timer auf zwanzig Minuten")
        if (words.any { it in CAMERA_WORDS || it in NOT_APP_WORDS || isDigits(it) || toInt(it) != null }) return null
        return words.joinToString(" ")
    }

    /**
     * Alle Beobachtungen der Nachricht. Je Befehlsteilsatz höchstens eine je Aktionsart; die Entscheidung, ob daraus
     * eine Aktion wird (genau eine) trifft [OfflineCommands].
     */
    fun findings(raw: String): List<Finding> {
        val out = ArrayList<Finding>()
        val all = sentences(raw)
        for (s in commands(raw, allowUnmute = true)) {
            val i = all.indexOfFirst { it.text == s.text }
            val nxt = if (i >= 0 && i + 1 < all.size) all[i + 1].tokens.firstOrNull { it !in LEAD_FILLERS } ?: "" else ""
            val prv = if (i > 0) all[i - 1].tokens.firstOrNull { it !in LEAD_FILLERS } ?: "" else ""
            val conditional = s.tokens.any { it in OFFLINE_COND } || nxt in COND_LEAD || prv in COND_OPENERS
            // Volume darf als einzige Art "nicht mehr stumm" tragen; alle anderen Arten brauchen einen unverneinten Satz
            if (s.neg) {
                if (!conditional && trigVolume(s) && volumeClean(s)) volumeFinding(s)?.let { out.add(it) }
                continue
            }
            if (conditional) continue
            // Taschenlampe
            if (flashKey(s) && form("flashlight", s)) {
                flashDir(s)?.let { out.add(Finding.Action(Act("flashlight", mapOf("state" to it)))) }
            }
            // Lautstärke
            if (trigVolume(s) && volumeClean(s)) volumeFinding(s)?.let { out.add(it) }
            // Einstellungen vor App: "öffne WLAN" ist eine Seite, keine App
            val pages = if (settingsVerb(s)) pagesIn(s) else emptyList()
            if (pages.size == 1) {
                out.add(Finding.Action(Act("open_settings", mapOf("page" to pages[0]))))
            } else if (pages.isEmpty() && openVerb(s)) {
                appName(s)?.let { out.add(Finding.Action(Act("open_app", mapOf("app" to it)))) }
            }
            // Timer
            if (timerTrig(s) && !reminderWithContent(s)) {
                if (s.tokens.count { it in TIMER_NOUNS } > 1) {
                    out.add(Finding.Several)
                } else {
                    val d = sentDurations(s.low)
                    if (d.size == 1) out.add(Finding.Action(Act("set_timer", mapOf("minutes" to d.first()))))
                    else if (d.size > 1) out.add(Finding.Several)
                }
            }
            // Wecker
            if (alarmTrig(s)) {
                val t = sentTimes(s.low)
                if (t.size == 1) {
                    val (h, m) = t.first()
                    out.add(Finding.Action(Act("set_alarm", mapOf("time" to "%02d:%02d".format(Locale.ROOT, h, m)))))
                } else if (t.size > 1) {
                    out.add(Finding.AmbiguousTime)
                }
            }
        }
        for (seg in cmdSegs(raw)) {
            brSeg(seg)?.let { (d, p) ->
                if (d != null && p != null) return@let // Richtung UND Wert im selben Abschnitt: mehrdeutig, nie auslösen
                if (d != null) out.add(Finding.Action(Act("set_brightness", mapOf("mode" to d))))
                else if (p != null) {
                    if (p < 5) out.add(Finding.OutOfRange("brightness"))
                    else out.add(Finding.Action(Act("set_brightness", mapOf("mode" to "set", "percent" to p))))
                }
            }
            if (lockSeg(seg)) out.add(Finding.Action(Act("lock_screen")))
        }
        return out
    }

    private fun volumeFinding(s: Sent): Finding? {
        val w = volWords(s)
        val named = channels(s.tokens)
        if (named.size > 1) return null
        val channel = named.firstOrNull() ?: "media"
        val pct = percents(s.text)
        val modes = ArrayList<String>()
        if (w.up) modes.add("up")
        if (w.down) modes.add("down")
        if (w.mute) modes.add("mute")
        if (w.unmute) modes.add("unmute")
        if (modes.isEmpty() && pct.size == 1 && (w.volume || "ton" in s.tokens || "sound" in s.tokens || named.isNotEmpty())) {
            if (channel == "alarm" && pct.first() < 10) return Finding.OutOfRange("alarm_volume")
            return Finding.Action(Act("volume", mapOf("channel" to channel, "mode" to "set", "percent" to pct.first())))
        }
        if (modes.size != 1 || pct.isNotEmpty()) return null
        if (channel == "alarm" && modes[0] == "mute") return Finding.OutOfRange("alarm_volume")
        return Finding.Action(Act("volume", mapOf("channel" to channel, "mode" to modes[0])))
    }

    // ── Routinen-Auslöser (match_trigger) ────────────────────────────────────────────────────────────────────

    private const val MAX_INPUT = 1500
    private const val MAX_NAME_WORDS = 3
    private val CREATE_VERBS = setOf("leg", "lege", "erstell", "erstelle", "richte", "speicher", "speichere", "erzeuge",
        "erzeug", "mach", "mache", "baue", "bau", "definiere", "definier", "stell", "stelle", "fueg", "fuege")
    private val UPDATE_VERBS = setOf("aender", "aendere", "bearbeite", "bearbeit", "erweitere", "erweiter", "ergaenze",
        "ergaenz", "ersetze", "ersetz", "passe", "pass", "update", "korrigiere", "korrigier")
    private val DELETE_VERBS = setOf("loesch", "loesche", "entferne", "entfern", "verwirf", "verwerfe", "streich",
        "streiche", "vergiss")
    private val FILL = setOf("bitte", "mal", "doch", "jetzt", "donna", "hey", "ok", "okay", "also", "und", "dann",
        "kannst", "du", "koenntest", "wuerdest", "ich", "moechte", "will", "brauche", "haette", "gern", "gerne")
    private val TRIGGER_VERBS = setOf("starte", "start", "aktiviere", "aktivier", "fuehre", "fuehr", "schalte",
        "schalt", "mach", "mache", "lass", "lasse", "los")
    private val DETERMINERS = setOf("die", "den", "das", "meine", "meinen", "mein", "der", "eine", "einen")
    private val TRIG_TRAIL = setOf("starten", "an", "ein", "los", "laufen", "bitte")

    /** Verwaltungsabsicht ("Leg eine Routine an ...") — damit ein Auslöser sie nie überlagert. */
    private fun routineIntent(raw: String): Boolean {
        if ('?' in raw.take(MAX_INPUT)) return false
        val toks = fold(raw.take(MAX_INPUT)).split(' ').filter { it.isNotEmpty() }
        if ("routine" !in toks || toks.any { it in NEG || it in ABORT }) return false
        val core = toks.toMutableList()
        while (core.isNotEmpty() && core[0] in FILL) core.removeAt(0)
        if (core.isEmpty()) return false
        val head = core[0]
        return head in DELETE_VERBS || head in UPDATE_VERBS || head in CREATE_VERBS
    }

    /** Ruft Mikes GANZER Satz genau eine Routine auf? [names]: Schlüssel -> Anzeigename. */
    fun matchTrigger(raw: String, names: Map<String, String>): String? {
        if (names.isEmpty() || '?' in raw.take(MAX_INPUT)) return null
        if (routineIntent(raw)) return null
        val toks = fold(raw.take(MAX_INPUT)).split(' ').filter { it.isNotEmpty() }.toMutableList()
        val lead = (FILL - setOf("und", "dann")) + TRIGGER_VERBS + DETERMINERS + setOf("routine", "modus")
        var runAus = false
        while (toks.isNotEmpty() && toks[0] in lead) {
            if (toks[0] == "fuehre" || toks[0] == "fuehr") runAus = true
            toks.removeAt(0)
        }
        if (toks.isNotEmpty() && toks.last() == "aus" && runAus) toks.removeAt(toks.size - 1)
        while (toks.isNotEmpty() && toks.last() in TRIG_TRAIL) toks.removeAt(toks.size - 1)
        if (toks.isEmpty() || toks.size > MAX_NAME_WORDS + 1) return null
        val keys = hashSetOf(toks.joinToString(""))
        if (toks.last() == "routine" || toks.last() == "modus") keys.add(toks.dropLast(1).joinToString(""))
        val hits = names.filterKeys { it in keys }.values.toSet()
        return if (hits.size == 1) hits.first() else null
    }

    /** Schlüssel einer Routine: gefaltet, ohne Leer-/Bindezeichen ("Stream-Modus" == "streammodus"). */
    fun nameKey(name: String): String = fold(name).replace(" ", "")

    /** Erinnerungswunsch ("erinner mich an ...") — offline nie als Server-Erinnerung möglich. */
    fun mentionsReminder(raw: String): Boolean =
        sentences(raw).any { s -> !s.question && s.tokens.any { it.startsWith("erinner") } }
}
