package com.yourcompany.donna.widget

/** Ein Teilnehmer eines Termins (Adresse nur zum Abgleich, nie zur Anzeige). */
data class Attendee(val email: String, val name: String?)

/**
 * #243: Personen-Tag im Termine-Widget — wer den Termin erstellt hat und wer beteiligt ist (reine Logik, JVM-getestet).
 *  - Mike erstellt → „Mike“, Ämi erstellt → „Ämi“ (Name der Partnerin).
 *  - Mike erstellt mit Ämi als Teilnehmerin bzw. Ämi erstellt mit Mike → „Mike + Ämi“ / „Ämi + Mike“ (Ersteller zuerst).
 *  - Andere Ersteller: kurzer Name (Teilnehmername bzw. erster Teil der Adresse) — NIE eine E-Mail-Adresse.
 *  - Kalender ohne Person (Feiertage, Gruppen-/Abo-Kalender): kein Tag.
 * Mikes Adressen = die Konten der Kalender auf seinem Gerät (ACCOUNT_NAME). Die Partnerin wird nicht im Code festgelegt:
 * Einstellung (Adresse + Anzeigename) oder — ohne Einstellung — genau ein geteilter Kalender einer fremden Person
 * (OWNER_ACCOUNT ≠ Mike) auf dem Gerät.
 */
object CalendarPeopleLogic {
    const val MIKE_NAME = "Mike"
    const val PARTNER_DEFAULT_NAME = "Ämi"
    const val NAME_MAX = 14

    private fun norm(email: String?): String = email?.trim()?.lowercase().orEmpty()

    /** Kalender-/Gruppenadressen statt Person (Google-Gruppen-, Abo- und Feiertagskalender, lokale Konten). */
    fun isGroupAddress(email: String?): Boolean {
        val e = norm(email)
        if (e.isEmpty() || '@' !in e) return true
        if ('#' in e) return true
        val domain = e.substringAfterLast('@')
        return domain.endsWith("calendar.google.com") || domain.startsWith("group.") || domain.startsWith("import.") ||
            domain == "localhost" || domain.endsWith(".local")
    }

    /** Mikes Adressen: alle Konten mit Kalendern auf dem Gerät, die wie eine Person aussehen. */
    fun mikeAddresses(accountNames: Collection<String?>): Set<String> =
        accountNames.map { norm(it) }.filter { !isGroupAddress(it) }.toSet()

    /** Partnerin ohne Einstellung: genau eine fremde Person als Eigentümerin eines Kalenders auf dem Gerät, sonst null. */
    fun detectPartner(calendarOwners: Collection<String?>, mike: Set<String>): String? =
        calendarOwners.map { norm(it) }.filter { !isGroupAddress(it) && it !in mike }.distinct().singleOrNull()

    /** Eingestellter Anzeigename: nur Buchstaben/Ziffern/Leerzeichen, nie eine Adresse; leer → Standardname. */
    fun displayName(configured: String?): String {
        val c = configured?.takeIf { '@' !in it }.orEmpty()
        val clean = WidgetFormat.clean(c, NAME_MAX).filter { it.isLetterOrDigit() || it == ' ' }.trim()
        return clean.ifEmpty { PARTNER_DEFAULT_NAME }
    }

    /** Kurzer Anzeigename: Vorname aus dem Teilnehmernamen, sonst erster Teil der Adresse (ohne Domain). */
    fun shortName(email: String, displayName: String?): String {
        val fromName = displayName?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.takeIf { it.isNotEmpty() && '@' !in it }
        val raw = fromName ?: norm(email).substringBefore('@').split('.', '_', '-', '+').firstOrNull { it.isNotEmpty() }.orEmpty()
        val clean = WidgetFormat.clean(raw, NAME_MAX).filter { it.isLetterOrDigit() || it == '…' }
        return clean.replaceFirstChar { it.uppercase() }
    }

    /**
     * Tag für einen Termin. [organizer] = Events.ORGANIZER (leer → Eigentümer des Kalenders als Ersteller).
     * [partnerEmail] = eingestellte bzw. erkannte Adresse der Partnerin (null = unbekannt), [partnerName] ihr Anzeigename.
     */
    fun tag(
        organizer: String?,
        calendarOwner: String?,
        attendees: List<Attendee>,
        mike: Set<String>,
        partnerEmail: String?,
        partnerName: String,
    ): String {
        val creator = norm(organizer).ifEmpty { norm(calendarOwner) }
        val partner = norm(partnerEmail).ifEmpty { null }
        val emails = attendees.map { norm(it.email) }.toSet()
        return when {
            // leer oder keine Adresse („My calendar“, „Local“ …) = Kalender auf Mikes Gerät
            creator.isEmpty() || '@' !in creator || creator in mike ->
                if (partner != null && partner in emails) "$MIKE_NAME + $partnerName" else MIKE_NAME
            partner != null && creator == partner ->
                if (emails.any { it in mike }) "$partnerName + $MIKE_NAME" else partnerName
            isGroupAddress(creator) -> ""
            else -> shortName(creator, attendees.firstOrNull { norm(it.email) == creator }?.name)
        }
    }
}
