package com.yourcompany.donna.idea

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Eine gemerkte Idee. [nonce] bleibt ueber alle Versuche gleich (Server legt je Nonce hoechstens EIN Issue an). */
data class PendingIdea(
    val nonce: String,
    val text: String,
    val projekt: String,
    val kanal: String,
    val attempts: Int = 0,
)

/** SENT: angelegt (oder schon angelegt). RETRY: spaeter erneut. PERMANENT: Server lehnt die Eingabe ab. */
enum class IdeaOutcome { SENT, RETRY, PERMANENT }

/**
 * #233 — REINE Logik der Ideen-Schnellerfassung (JVM-testbar, keine Android-Aufrufe): feste Projektliste,
 * Eingabepruefung, Anfrage-Koerper, Ergebnis-Zuordnung und Offline-Warteschlange (Muster: ReminderLogic).
 * Server-Vertrag: backend/tests/contracts/inbox_capture_contract.json (IdeaCaptureLogicTest liest ihn mit).
 */
object IdeaCaptureLogic {
    const val PATH = "/inbox/capture"
    const val TEXT_MAX = 2000
    const val NONCE_PATTERN = "^[A-Za-z0-9_-]{16,64}$"
    private val NONCE_RE = Regex(NONCE_PATTERN)

    const val KANAL_WIDGET = "widget"
    const val KANAL_SHORTCUT = "shortcut"
    val KANAELE: List<String> = listOf(KANAL_WIDGET, KANAL_SHORTCUT)

    const val DEFAULT_PROJEKT = "uebergreifend"

    /** Feste Auswahl (= KNOWN_PROJECT_SLUGS des Servers); kein Freitext-Slug. Reihenfolge = Anzeige. */
    val PROJEKTE: List<String> = listOf(
        "uebergreifend",
        "donna-assistentin", "streampost", "streamtool", "steuern-crm", "chat-tool", "learning",
        "website-abgemiked", "soullink", "verwaltung", "nicebot", "turnier-abgemiked", "guess-rank",
        "central-auth", "twitch-eventsub-hub", "irl-streaming", "minecraft-challenge", "palworld",
        "cr-stats", "akquise-ewv", "stoersender", "legal-skill", "homeassistent", "rototech-cli",
        "weiterbildung", "dragonball", "dj", "ausschreibungsscouting", "haushaltskonto",
    )

    private val LABELS: Map<String, String> = mapOf(
        "uebergreifend" to "Übergreifend (alle Projekte)",
        "donna-assistentin" to "Donna",
        "steuern-crm" to "Steuern-CRM",
        "homeassistent" to "Smart Home",
        "website-abgemiked" to "Website",
        "weiterbildung" to "Weiterbildung (unKladde)",
    )

    /** Anzeige-Name eines Slugs (sonst der Slug selbst). */
    fun label(slug: String): String = LABELS[slug] ?: slug

    /** Gemerkte Wahl nur, wenn sie noch in der Liste steht; sonst Standard. */
    fun initialProjekt(saved: String?): String = if (saved != null && saved in PROJEKTE) saved else DEFAULT_PROJEKT

    fun kanalOrDefault(value: String?): String = if (value != null && value in KANAELE) value else KANAL_WIDGET

    /** Kuerzt auf [max] UTF-16-Einheiten, ohne ein Surrogatpaar (Emoji) zu zerschneiden. */
    fun takeSafe(s: String, max: Int): String {
        if (s.length <= max) return s
        val end = if (max > 0 && Character.isHighSurrogate(s[max - 1])) max - 1 else max
        return s.substring(0, end)
    }

    /** Bereinigter Text oder null (leer). Kuerzt hart auf [TEXT_MAX]. */
    fun cleanText(raw: String?): String? {
        val t = (raw ?: "").trim()
        return if (t.isEmpty()) null else takeSafe(t, TEXT_MAX).trim()
    }

    /** Diktat an vorhandenen Text anhaengen (mit Leerzeichen), Grenze beachten. */
    fun appendDictation(current: String, dictated: String?): String {
        val d = dictated?.trim().orEmpty()
        if (d.isEmpty()) return current
        val base = current.trimEnd()
        return takeSafe(if (base.isEmpty()) d else "$base $d", TEXT_MAX)
    }

    fun newNonce(): String = UUID.randomUUID().toString().replace("-", "")

    fun isValidNonce(n: String?): Boolean = n != null && NONCE_RE.matches(n)

    /** Anfrage-Koerper (genau die Felder des Vertrags). Null bei ungueltigem Eintrag (nie senden). */
    fun body(item: PendingIdea): String? {
        if (!isValidNonce(item.nonce) || item.projekt !in PROJEKTE || item.kanal !in KANAELE) return null
        val text = cleanText(item.text) ?: return null
        return JSONObject()
            .put("text", text)
            .put("projekt", item.projekt)
            .put("nonce", item.nonce)
            .put("kanal", item.kanal)
            .toString()
    }

    /**
     * 2xx = angelegt. 400/422 = Eingabe abgelehnt (dauerhaft). Alles andere (kein Netz, 401/403 nach Neu-Kopplung,
     * 409 laeuft noch, 429, 5xx/503) = spaeter erneut — eine Idee geht nicht wegen eines voruebergehenden Fehlers verloren.
     */
    fun outcome(httpCode: Int?): IdeaOutcome = when (httpCode) {
        null -> IdeaOutcome.RETRY
        in 200..299 -> IdeaOutcome.SENT
        400, 422 -> IdeaOutcome.PERMANENT
        else -> IdeaOutcome.RETRY
    }

    /** Issue-Nummer aus der Server-Antwort, sonst null. */
    fun issueNumber(responseBody: String?): Int? = try {
        val o = JSONObject(responseBody ?: "")
        if (o.has("number")) o.getInt("number") else null
    } catch (_: Exception) {
        null
    }

    // ── Offline-Warteschlange (JSON-Text in einer Pref) ───────────────
    const val QUEUE_MAX = 50
    const val MAX_ATTEMPTS = 3

    fun queueParse(raw: String?): List<PendingIdea> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val item = PendingIdea(
                    nonce = o.optString("nonce", ""),
                    text = o.optString("text", ""),
                    projekt = o.optString("projekt", ""),
                    kanal = o.optString("kanal", ""),
                    attempts = o.optInt("attempts", 0).coerceAtLeast(0),
                )
                if (body(item) == null) null else item
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun serialize(items: List<PendingIdea>): String {
        val arr = JSONArray()
        for (it in items) {
            arr.put(
                JSONObject().put("nonce", it.nonce).put("text", it.text).put("projekt", it.projekt)
                    .put("kanal", it.kanal).put("attempts", it.attempts),
            )
        }
        return arr.toString()
    }

    /** Haengt an (gleiche Nonce wird ersetzt). Null, wenn die Warteschlange voll ist (nichts verdraengen). */
    fun queueAdd(raw: String?, item: PendingIdea): String? {
        val items = queueParse(raw).filterNot { it.nonce == item.nonce }
        if (items.size >= QUEUE_MAX) return null
        return serialize(items + item)
    }

    fun queueRemove(raw: String?, nonce: String): String = serialize(queueParse(raw).filterNot { it.nonce == nonce })

    /** Dauerhafter Fehler: Versuch zaehlen, nach [MAX_ATTEMPTS] verwerfen. */
    fun queueFail(raw: String?, nonce: String): Pair<String, Boolean> {
        var dropped = false
        val items = queueParse(raw).mapNotNull {
            if (it.nonce != nonce) return@mapNotNull it
            val next = it.copy(attempts = it.attempts + 1)
            if (next.attempts >= MAX_ATTEMPTS) { dropped = true; null } else next
        }
        return serialize(items) to dropped
    }

    fun queueContains(raw: String?, nonce: String): Boolean = queueParse(raw).any { it.nonce == nonce }
}
