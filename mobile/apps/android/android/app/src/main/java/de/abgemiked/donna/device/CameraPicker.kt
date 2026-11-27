package com.yourcompany.donna.device

/** Eine App, die "Kamera öffnen" (Foto/Video) kann. [system] = vorinstallierte System-App (auch per Update aktualisiert). */
data class CameraCandidate(val packageName: String, val system: Boolean)

/**
 * Wahl der Standard-Kamera ohne Auswahldialog ("Vorgang abschließen mit"). Nie Drittanbieter-Apps (z. B. Snapchat,
 * die dieselbe Kamera-Aktion anbieten): nur System-Apps. Der vom System aufgelöste Standard gewinnt, wenn er eine
 * System-App ist; sonst die bekannte Hersteller-Kamera, sonst die erste System-App in stabiler Reihenfolge.
 */
object CameraPicker {
    private val PREFERRED = listOf("com.sec.android.app.camera", "com.google.android.GoogleCamera", "com.android.camera2", "com.android.camera")

    fun choose(candidates: List<CameraCandidate>, systemDefault: String?): String? {
        val sys = candidates.filter { it.system }.map { it.packageName }.distinct()
        if (sys.isEmpty()) return null
        if (systemDefault != null && systemDefault in sys) return systemDefault
        return PREFERRED.firstOrNull { it in sys } ?: sys.sorted().first()
    }
}
