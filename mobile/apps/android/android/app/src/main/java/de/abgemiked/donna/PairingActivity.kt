package com.yourcompany.donna

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * DONNA-103: Einmaliger Pairing-Screen.
 *
 * Wird beim ersten App-Start gezeigt wenn noch kein Token gespeichert ist.
 * Mike gibt seinen 6-stelligen Google-Authenticator-Code ein.
 * Bei Erfolg: Token in TokenStore speichern → zurück zu MainActivity.
 */
class PairingActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, PairingActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Batterie-Exemption VOR TOTP-Request — Samsung FreecessHandler friert
        // Netzwerk-Coroutines ohne Exemption nach kurzer Zeit ein (result:12)
        requestBatteryOptimizationExemption()

        // Minimales UI — kein Layout-XML nötig
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(64, 64, 64, 64)
        }

        val title = TextView(this).apply {
            text = "Donna verbinden"
            textSize = 24f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }

        val subtitle = TextView(this).apply {
            text = "6-stelligen Code aus Google Authenticator eingeben"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }

        val codeInput = EditText(this).apply {
            hint = "123456"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            maxLines = 1
            textSize = 28f
            gravity = Gravity.CENTER
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
        }

        val connectButton = Button(this).apply {
            text = "Verbinden"
            setPadding(0, 24, 0, 0)
        }

        val statusText = TextView(this).apply {
            text = ""
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 0)
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(codeInput)
        root.addView(connectButton)
        root.addView(statusText)
        setContentView(root)

        connectButton.setOnClickListener {
            val code = codeInput.text.toString().trim()
            if (code.length != 6 || !code.all { it.isDigit() }) {
                statusText.text = "Bitte 6-stelligen Code eingeben"
                return@setOnClickListener
            }

            connectButton.isEnabled = false
            statusText.text = "Verbinde …"

            // Tastatur ausblenden
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(codeInput.windowToken, 0)

            scope.launch {
                val result = doPair(code)
                if (result.isSuccess) {
                    TokenStore.saveToken(this@PairingActivity, result.getOrThrow())
                    statusText.text = "Verbunden!"
                    // MainActivity neu starten (CLEAR_TASK) damit Permissions
                    // sofort nach dem Pairing angefragt werden
                    val intent = Intent(this@PairingActivity, MainActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                    finish()
                } else {
                    val msg = result.exceptionOrNull()?.message ?: "Unbekannter Fehler"
                    statusText.text = "Fehler: $msg"
                    connectButton.isEnabled = true
                }
            }
        }
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return

        AlertDialog.Builder(this)
            .setTitle("Hintergrundverbindung benötigt")
            .setMessage(
                "Damit Donna zuverlässig erreichbar ist (auch beim Verbinden), " +
                "muss die Akku-Optimierung deaktiviert werden.\n\n" +
                "Bitte 'Zulassen' im nächsten Dialog waehlen."
            )
            .setPositiveButton("Weiter") { _, _ ->
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                    )
                } catch (_: Exception) {}
            }
            .setNegativeButton("Später") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private suspend fun doPair(code: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val apiBase = BuildConfig.DONNA_API_URL
            val url = URL("$apiBase/setup/pair")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 10_000
                readTimeout = 15_000
                doOutput = true
            }

            val body = JSONObject().apply { put("totp", code) }.toString()
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                val errorBody = try {
                    conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $responseCode"
                } catch (_: Exception) {
                    "HTTP $responseCode"
                }
                val detail = try {
                    JSONObject(errorBody).optString("detail", "HTTP $responseCode")
                } catch (_: Exception) {
                    "HTTP $responseCode"
                }
                return@withContext Result.failure(Exception(detail))
            }

            val responseBody = conn.inputStream.bufferedReader().readText()
            val token = JSONObject(responseBody).getString("token")
            Result.success(token)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
