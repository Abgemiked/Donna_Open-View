package com.yourcompany.donna.emaildraft

import com.yourcompany.donna.calendaredit.OnceLedger
import com.yourcompany.donna.device.DeviceActionReport
import com.yourcompany.donna.device.ResultKind
import org.json.JSONObject

/**
 * #147 Paket I — E-Mail-Entwurf öffnen: REINE Logik (zweites Tor auf dem Gerät, Intent-Beschreibung, Texte,
 * Einmal-Garantie). Keine Android-Aufrufe; das Starten der Mail-App steht getrennt im Modul. Grundsatz: fail-closed.
 * Es wird nur ein Entwurf GEÖFFNET — Donna sendet nie, hängt nichts an und setzt weder cc noch bcc.
 * Empfänger, Betreff und Text werden nie geloggt.
 */
data class EmailDraft(val to: String, val subject: String, val body: String)

object EmailDraftLimits {
    const val MAX_ADDRESS = 254
    const val MAX_LOCAL = 64
    const val MAX_SUBJECT = 200
    const val MAX_BODY = 4000
}

/** Erlaubte Felder der Server-Karte (alles andere lehnt das Gerät ab). */
private val ALLOWED_FIELDS = setOf("type", "to", "subject", "body", "action_id", "card_id")

object EmailDraftValidator {
    private const val LOCAL_CHARS = "!#$%&'*+/=^_`{|}~-"

    private fun isLocalChar(c: Char) = (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c == '.' || c in LOCAL_CHARS

    private fun isLabel(l: String): Boolean =
        l.isNotEmpty() && l.length <= 63 && !l.startsWith('-') && !l.endsWith('-') &&
            l.all { (it in 'a'..'z') || (it in 'A'..'Z') || (it in '0'..'9') || it == '-' }

    /** Genau EINE einfache Adresse (ASCII): kein Name-Teil, keine Liste, kein Leerraum, kein "?" oder "&" zum Einschmuggeln. */
    fun isSingleAddress(a: String): Boolean {
        if (a.isEmpty() || a.length > EmailDraftLimits.MAX_ADDRESS) return false
        if (a.count { it == '@' } != 1) return false
        val local = a.substringBefore('@')
        val domain = a.substringAfter('@')
        if (local.isEmpty() || local.length > EmailDraftLimits.MAX_LOCAL) return false
        if (!local.all(::isLocalChar) || local.startsWith('.') || local.endsWith('.') || ".." in local) return false
        val labels = domain.split('.')
        if (labels.size < 2 || !labels.all(::isLabel)) return false
        val tld = labels.last()
        return tld.length >= 2 && tld.any { it in 'a'..'z' || it in 'A'..'Z' }
    }

    private fun hasLineBreakOrControl(s: String) = s.any { it.isISOControl() || it == ' ' || it == ' ' }

    /** Betreff: eine Zeile, keine Steuerzeichen (kein CR/LF-Einschleusen weiterer Kopfzeilen). */
    fun isValidSubject(s: String) =
        s.length <= EmailDraftLimits.MAX_SUBJECT && !hasLineBreakOrControl(s) && !hasInvisibleFormat(s)

    /** Unsichtbare Format-Zeichen (Bidi-Umkehr, Zero-Width, BOM): Anzeigetäuschung im Entwurf -> ablehnen. */
    private fun hasInvisibleFormat(s: String) = s.any { Character.getType(it) == Character.FORMAT.toInt() }

    /** Text: Zeilenumbrüche und Tabs erlaubt, sonst keine Steuerzeichen. */
    fun isValidBody(s: String) =
        s.length <= EmailDraftLimits.MAX_BODY && s.none { (it.isISOControl() && it != '\n' && it != '\r' && it != '\t') } &&
            !hasInvisibleFormat(s)

    fun validate(d: EmailDraft): Boolean =
        (d.to.isEmpty() || isSingleAddress(d.to)) && isValidSubject(d.subject) && isValidBody(d.body) &&
            (d.subject.isNotBlank() || d.body.isNotBlank())
}

object EmailDraftParser {
    /** Server-Karte -> Entwurf; null = ablehnen (unbekannte Felder, falsche Typen, Grenzen verletzt). */
    fun parse(json: JSONObject): EmailDraft? {
        if (json.optString("type") != "email_draft") return null
        val keys = json.keys()
        while (keys.hasNext()) {
            if (keys.next() !in ALLOWED_FIELDS) return null
        }
        fun str(k: String): String? {
            if (!json.has(k) || json.isNull(k)) return ""
            return json.opt(k) as? String
        }
        val d = EmailDraft(str("to") ?: return null, str("subject") ?: return null, str("body") ?: return null)
        return d.takeIf { EmailDraftValidator.validate(it) }
    }
}

/** Beschreibung des Intents (rein; das Modul baut daraus den echten Intent). Daten-Uri ist IMMER nacktes "mailto:". */
data class MailIntentSpec(
    val action: String,
    val dataUri: String,
    val email: List<String>?,
    val subject: String?,
    val text: String?,
)

object EmailDraftIntent {
    const val ACTION_SENDTO = "android.intent.action.SENDTO"
    const val DATA_URI = "mailto:"

    fun spec(d: EmailDraft) = MailIntentSpec(
        action = ACTION_SENDTO,
        dataUri = DATA_URI,
        email = if (d.to.isNotEmpty()) listOf(d.to) else null,
        subject = d.subject.takeIf { it.isNotEmpty() },
        text = d.body.takeIf { it.isNotEmpty() },
    )
}

object EmailDraftMessages {
    fun opened() = "Entwurf geöffnet — gesendet hast du noch nichts."
    fun alreadyOpened() = "Der Entwurf wurde schon geöffnet — gesendet hast du noch nichts."
    fun noMailApp() = "Keine Mail-App gefunden."
    fun rejected() = "Das kann ich so nicht ausführen — ich habe nichts geöffnet."
    fun disabled() = "Das darf ich noch nicht selbst tun. Du kannst es in den Einstellungen unter „Donna darf selbst …“ einschalten."
    fun failed() = "Das hat nicht geklappt — ich habe nichts geöffnet."
}

enum class LaunchResult { STARTED, NO_APP, ERROR }

data class EmailOutcome(val status: String, val message: String, val kind: ResultKind)

/** Ablage der Einmal-Marken (im Modul: Einstellungen; im Test: Speicher). */
interface DoneStore {
    fun contains(id: String): Boolean
    fun mark(id: String)
}

object EmailDraftService {
    private val CARD_ID = Regex("^[a-z0-9-]{6,40}$")

    /** Schlüssel der Einmal-Ausführung: Aktionskennung, sonst Karten-Kennung; ohne beides nie ausführen. */
    fun onceKey(json: JSONObject): String? =
        DeviceActionReport.actionId(json) ?: (json.opt("card_id") as? String)?.takeIf { CARD_ID.matches(it) }

    /**
     * Nach dem Tipp. Reihenfolge: Schalter -> Prüfung -> Einmal-Garantie -> Start. Die Marke wird erst nach erfolgreichem Start
     * gesetzt (kein Mail-App-Fehler verbraucht die Karte). Der Aufrufer serialisiert (Doppeltipp).
     */
    fun execute(enabled: Boolean, request: JSONObject, done: DoneStore, launch: (EmailDraft) -> LaunchResult): EmailOutcome {
        if (!enabled) return EmailOutcome("disabled", EmailDraftMessages.disabled(), ResultKind.DISABLED)
        val draft = EmailDraftParser.parse(request) ?: return EmailOutcome("rejected", EmailDraftMessages.rejected(), ResultKind.REJECTED)
        val once = onceKey(request) ?: return EmailOutcome("rejected", EmailDraftMessages.rejected(), ResultKind.REJECTED)
        if (done.contains(once)) return EmailOutcome("ok", EmailDraftMessages.alreadyOpened(), OnceLedger.REPORT_FOR_ALREADY_DONE)
        return when (val r = try { launch(draft) } catch (_: Exception) { LaunchResult.ERROR }) {
            LaunchResult.STARTED -> { done.mark(once); EmailOutcome("ok", EmailDraftMessages.opened(), ResultKind.OK) }
            LaunchResult.NO_APP -> EmailOutcome("unsupported", EmailDraftMessages.noMailApp(), ResultKind.UNSUPPORTED)
            LaunchResult.ERROR -> EmailOutcome("failed", EmailDraftMessages.failed(), ResultKind.FAILED)
        }
    }
}
