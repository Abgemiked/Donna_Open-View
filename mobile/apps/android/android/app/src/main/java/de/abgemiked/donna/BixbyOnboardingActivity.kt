package com.yourcompany.donna

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*

/**
 * BixbyOnboardingActivity — DONNA-126: Bixby Quick Commands Onboarding.
 *
 * Zeigt eine Schritt-für-Schritt-Anleitung wie der User Donna als Bixby Quick Command
 * einrichten kann. Optional: "Öffne Einstellungen"-Button startet Bixby Quick Commands direkt.
 *
 * Wird beim ersten App-Start einmalig angezeigt (SharedPreferences-Flag: bixby_onboarding_shown).
 * Graceful: kein Crash auf Nicht-Samsung-Geräten wenn Intent nicht gefunden.
 */
class BixbyOnboardingActivity : Activity() {

    companion object {
        private const val TAG = "BixbyOnboarding"
        private const val PREF_NAME = "donna_prefs"
        private const val PREF_KEY = "bixby_onboarding_shown"
        private const val BIXBY_QUICK_COMMANDS_ACTION = "com.samsung.android.bixby.action.OPEN_QUICK_COMMANDS"

        /**
         * Prüft ob das Onboarding bereits angezeigt wurde.
         */
        fun wasShown(context: Context): Boolean {
            return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_KEY, false)
        }

        /**
         * Startet das Onboarding wenn es noch nicht gezeigt wurde.
         * Aus MainActivity aufzurufen.
         */
        fun showIfNeeded(context: Context) {
            if (!wasShown(context)) {
                val intent = Intent(context, BixbyOnboardingActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        markShown()
        buildUI()
    }

    // ── UI ───────────────────────────────────────────────────────────────────

    private fun buildUI() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 64, 48, 48)
        }
        scroll.addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        // Titel
        root.addView(TextView(this).apply {
            text = "Donna per Bixby aktivieren"
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        })

        // Beschreibung
        root.addView(TextView(this).apply {
            text = "Mit einem Bixby Quick Command kannst du Donna per Sprachbefehl öffnen — " +
                   "z.B. \"Hey Bixby, öffne Donna\"."
            textSize = 15f
            setPadding(0, 0, 0, 32)
        })

        // Schritt-für-Schritt-Anleitung
        val steps = listOf(
            "1. Bixby öffnen (Side-Key lang drücken)",
            "2. Menü (☰) antippen → Quick Commands",
            "3. \"+\" (Neuer Command) antippen",
            "4. Sprachphrase eingeben: z.B. \"Donna öffnen\"",
            "5. Aktion: App starten → Donna auswählen",
            "6. Speichern — fertig!",
        )
        steps.forEach { step ->
            root.addView(TextView(this).apply {
                text = step
                textSize = 14f
                setPadding(16, 8, 16, 8)
            })
        }

        // Spacer
        root.addView(Space(this).apply {
            minimumHeight = 32
        })

        // Bixby-Einstellungen öffnen — nur anzeigen wenn Intent verfügbar
        if (isBixbyQuickCommandsAvailable()) {
            root.addView(Button(this).apply {
                text = "Bixby Quick Commands öffnen"
                setOnClickListener { openBixbyQuickCommands() }
            })
            root.addView(Space(this).apply {
                minimumHeight = 16
            })
        }

        // Schließen
        root.addView(Button(this).apply {
            text = "Verstanden — Schließen"
            setOnClickListener { finish() }
        })

        setContentView(scroll)
    }

    // ── Bixby Intent ─────────────────────────────────────────────────────────

    /**
     * Öffnet Bixby Quick Commands direkt via Intent.
     * Graceful: kein Crash wenn Intent nicht gefunden (Nicht-Samsung-Gerät).
     */
    private fun isBixbyQuickCommandsAvailable(): Boolean {
        val intent = Intent(BIXBY_QUICK_COMMANDS_ACTION)
        return packageManager.resolveActivity(intent, 0) != null
    }

    private fun openBixbyQuickCommands() {
        try {
            val intent = Intent(BIXBY_QUICK_COMMANDS_ACTION)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            Log.i(TAG, "Bixby Quick Commands Intent gesendet")
        } catch (e: ActivityNotFoundException) {
            Log.d(TAG, "Bixby Quick Commands nicht verfügbar (kein Samsung-Gerät)")
            Toast.makeText(
                this,
                "Bixby Quick Commands nicht verfügbar auf diesem Gerät.",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Log.w(TAG, "Bixby Intent fehlgeschlagen: ${e.message}")
            Toast.makeText(this, "Bixby konnte nicht geöffnet werden.", Toast.LENGTH_SHORT).show()
        }
    }

    // ── SharedPreferences ────────────────────────────────────────────────────

    private fun markShown() {
        getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_KEY, true)
            .apply()
    }
}
