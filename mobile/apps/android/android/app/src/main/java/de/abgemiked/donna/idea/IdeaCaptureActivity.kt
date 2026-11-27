package com.yourcompany.donna.idea

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.yourcompany.donna.R
import com.yourcompany.donna.ThemeChoice
import com.yourcompany.donna.TtsEngines
import com.yourcompany.donna.widget.DonnaEntries

/**
 * #233: Ideen-Schnellerfassung (Ideen-Widget, Shortcut „Idee erfassen“).
 * Erfassen (Text oder Diktat mit Donnas eigener Aufnahme [IdeaDictation], #243 — kein Google-Dialog; Projekt aus
 * fester Liste) → Vorschau
 * „Als Idee speichern?“ → Mikes Tipp auf „Speichern“ ist die Bestaetigung. Ziel ist nur die GitHub-donna-inbox
 * (kein Gedaechtnis-Eintrag). Ohne Netz wird die Idee gemerkt und spaeter mit derselben Nonce nachgesendet.
 */
class IdeaCaptureActivity : AppCompatActivity() {
    private lateinit var textField: EditText
    private lateinit var projektSpinner: Spinner
    private lateinit var editGroup: View
    private lateinit var previewGroup: View
    private lateinit var previewText: TextView
    private lateinit var previewMeta: TextView
    private lateinit var status: TextView
    private lateinit var saveButton: Button
    private lateinit var backButton: Button

    private var kanal: String = IdeaCaptureLogic.KANAL_WIDGET
    /** Eine Nonce je Idee (Schutz gegen Doppelanlage); nach „Ändern“ gibt es eine neue, s. [showEdit]. */
    private var nonce: String = IdeaCaptureLogic.newNonce()
    private var saving = false

    private lateinit var dictateButton: ImageButton
    private lateinit var listenStatus: TextView

    /** #243: Donnas eigene Aufnahme (SpeechRecognizer + Nachhören #226) statt des Google-Diktat-Dialogs. */
    private val dictation by lazy {
        IdeaDictation(this, object : IdeaDictation.Listener {
            override fun onListening(active: Boolean) {
                dictateButton.setImageResource(if (active) R.drawable.ic_donna_stop else R.drawable.ic_donna_mic)
                dictateButton.contentDescription = getString(if (active) R.string.idea_dictate_stop else R.string.idea_dictate)
                if (active) {
                    hideKeyboard()
                    listenStatus.setText(R.string.idea_listening)
                    listenStatus.visibility = View.VISIBLE
                } else {
                    // Ende ohne Ergebnis/Meldung („Fertig“ ohne Sprache, Abbruch) darf nicht weiter „hört zu“ zeigen;
                    // eine Fehlermeldung (onError) folgt ggf. direkt danach und blendet sich wieder ein
                    listenStatus.visibility = View.GONE
                }
            }

            override fun onPartial(text: String) {
                // lange Diktate: das Ende zeigen (dort spricht Mike gerade)
                if (text.isNotBlank()) listenStatus.text = if (text.length > 120) "…" + text.takeLast(120) else text
            }

            override fun onFinal(text: String) {
                listenStatus.visibility = View.GONE
                val next = IdeaCaptureLogic.appendDictation(textField.text.toString(), text)
                textField.setText(next)
                textField.setSelection(next.length)
            }

            override fun onError(message: String) {
                listenStatus.text = message
                listenStatus.visibility = View.VISIBLE
            }
        })
    }

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) dictation.start() else dictationError(IdeaDictationLogic.TEXT_PERMISSION)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences(TtsEngines.PREFS, MODE_PRIVATE)
        delegate.localNightMode = when (ThemeChoice.nightOverride(ThemeChoice.read(prefs))) {
            true -> AppCompatDelegate.MODE_NIGHT_YES
            false -> AppCompatDelegate.MODE_NIGHT_NO
            null -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_idea_capture)
        kanal = IdeaCaptureLogic.kanalOrDefault(intent?.getStringExtra(DonnaEntries.EXTRA_KANAL))
        savedInstanceState?.getString(STATE_NONCE)?.takeIf { IdeaCaptureLogic.isValidNonce(it) }?.let { nonce = it }

        textField = findViewById(R.id.idea_text)
        projektSpinner = findViewById(R.id.idea_projekt)
        editGroup = findViewById(R.id.idea_edit_group)
        previewGroup = findViewById(R.id.idea_preview_group)
        previewText = findViewById<TextView>(R.id.idea_preview_text).apply {
            movementMethod = android.text.method.ScrollingMovementMethod()
        }
        previewMeta = findViewById(R.id.idea_preview_meta)
        status = findViewById(R.id.idea_status)
        saveButton = findViewById(R.id.idea_save)
        backButton = findViewById(R.id.idea_back)

        val slugs = IdeaCaptureLogic.PROJEKTE
        projektSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, slugs.map { IdeaCaptureLogic.label(it) },
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        projektSpinner.setSelection(slugs.indexOf(IdeaCaptureLogic.initialProjekt(prefs.getString(PREF_LAST_PROJEKT, null))))

        dictateButton = findViewById(R.id.idea_dictate)
        listenStatus = findViewById(R.id.idea_listen_status)
        dictateButton.setOnClickListener { if (dictation.active) dictation.stop() else startDictation() }
        findViewById<Button>(R.id.idea_cancel).setOnClickListener { finish() }
        findViewById<Button>(R.id.idea_next).setOnClickListener { showPreview() }
        backButton.setOnClickListener { showEdit() }
        saveButton.setOnClickListener { save() }

        // Nach Drehung: der Feldtext kommt erst in onRestoreInstanceState zurück → Vorschau danach aufbauen
        when {
            savedInstanceState?.getBoolean(STATE_PREVIEW) == true -> textField.post { showPreview() }
            // #243: Widget-Knopf „Sprechen“ → direkt diktieren (nicht nach Drehung erneut starten)
            savedInstanceState == null && DonnaEntries.isVoiceStart(intent?.getStringExtra(DonnaEntries.EXTRA_START_MODE)) ->
                textField.post { startDictation() }
            else -> focusText()
        }
        // Netz ist offenbar da, wenn Mike erfasst: gemerkte Ideen nachsenden
        IdeaInbox.flushPending(this)
    }

    /** singleTask: erneuter Aufruf aus dem Widget, während die Erfassung schon offen ist. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (previewGroup.visibility == View.VISIBLE) return
        if (DonnaEntries.isVoiceStart(intent.getStringExtra(DonnaEntries.EXTRA_START_MODE))) startDictation()
        else if (!dictation.active) focusText()
    }

    override fun onStop() {
        // Aufnahme nie im Hintergrund weiterlaufen lassen
        dictation.cancel()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_NONCE, nonce)
        outState.putBoolean(STATE_PREVIEW, previewGroup.visibility == View.VISIBLE)
    }

    private fun selectedProjekt(): String =
        IdeaCaptureLogic.PROJEKTE.getOrElse(projektSpinner.selectedItemPosition) { IdeaCaptureLogic.DEFAULT_PROJEKT }

    private fun focusText() {
        textField.requestFocus()
        textField.post {
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(textField, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(textField.windowToken, 0)
    }

    private fun startDictation() {
        if (dictation.active || saving) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            dictation.start()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun dictationError(message: String) {
        listenStatus.text = message
        listenStatus.visibility = View.VISIBLE
    }

    private fun showPreview() {
        dictation.cancel()
        listenStatus.visibility = View.GONE
        val text = IdeaCaptureLogic.cleanText(textField.text.toString())
        if (text == null) {
            textField.error = getString(R.string.idea_empty)
            focusText()
            return
        }
        hideKeyboard()
        previewText.text = text
        previewMeta.text = getString(R.string.idea_preview_meta, IdeaCaptureLogic.label(selectedProjekt()))
        status.visibility = View.GONE
        editGroup.visibility = View.GONE
        previewGroup.visibility = View.VISIBLE
        findViewById<TextView>(R.id.idea_heading).setText(R.string.idea_preview_heading)
        setBusy(false)
        saveButton.requestFocus()
    }

    private fun showEdit() {
        if (saving) return
        previewGroup.visibility = View.GONE
        editGroup.visibility = View.VISIBLE
        findViewById<TextView>(R.id.idea_heading).setText(R.string.idea_heading)
        // Geaenderter Text = neue Idee: neue Nonce (die alte war ggf. schon abgelehnt)
        nonce = IdeaCaptureLogic.newNonce()
        focusText()
    }

    private fun setBusy(busy: Boolean) {
        saving = busy
        saveButton.isEnabled = !busy
        backButton.isEnabled = !busy
    }

    private fun save() {
        if (saving) return
        val text = IdeaCaptureLogic.cleanText(textField.text.toString()) ?: return showEdit()
        val projekt = selectedProjekt()
        getSharedPreferences(TtsEngines.PREFS, MODE_PRIVATE).edit().putString(PREF_LAST_PROJEKT, projekt).apply()
        setBusy(true)
        status.visibility = View.VISIBLE
        status.setText(R.string.idea_saving)
        IdeaInbox.submit(this, PendingIdea(nonce, text, projekt, kanal)) { result ->
            if (isFinishing || isDestroyed) return@submit
            when (result) {
                is SubmitResult.Saved -> {
                    val msg = if (result.number != null) getString(R.string.idea_saved_nr, result.number)
                    else getString(R.string.idea_saved)
                    Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
                    finish()
                }
                SubmitResult.Queued -> {
                    Toast.makeText(applicationContext, R.string.idea_queued, Toast.LENGTH_LONG).show()
                    finish()
                }
                SubmitResult.Rejected -> {
                    setBusy(false)
                    status.setText(R.string.idea_rejected)
                }
                SubmitResult.QueueFull -> {
                    setBusy(false)
                    status.setText(R.string.idea_queue_full)
                }
            }
        }
    }

    companion object {
        private const val PREF_LAST_PROJEKT = "idea_capture_last_projekt"
        private const val STATE_NONCE = "idea_nonce"
        private const val STATE_PREVIEW = "idea_preview"
    }
}
