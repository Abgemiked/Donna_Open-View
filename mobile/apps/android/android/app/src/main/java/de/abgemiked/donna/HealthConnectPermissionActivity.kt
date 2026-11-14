package com.yourcompany.donna

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController

/**
 * HealthConnectPermissionActivity — DONNA-120: Pflicht-Activity für Health Connect
 *
 * Laut Health Connect Dokumentation muss eine Activity mit
 * `androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE` registriert sein.
 * Diese wird von Health Connect aufgerufen wenn der User Permissions widerruft
 * oder die App zum ersten Mal auf Health-Daten zugreift.
 *
 * Die Activity startet den Permission-Request-Flow für Health Connect
 * und schließt sich danach automatisch — kein eigenes UI nötig.
 */
class HealthConnectPermissionActivity : ComponentActivity() {

    private val requestPermissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { grantedPermissions ->
        Log.i(TAG, "Health Connect Permissions erteilt: $grantedPermissions")
        // Nach Permission-Ergebnis: Sync starten wenn alle Permissions erteilt
        if (grantedPermissions.containsAll(HealthConnectManager.REQUIRED_PERMISSIONS)) {
            HealthConnectManager.syncIfNeeded(this)
        } else {
            Log.w(TAG, "Nicht alle Health Connect Permissions erteilt — Sync übersprungen")
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!HealthConnectManager.isAvailable(this)) {
            Log.w(TAG, "Health Connect nicht verfügbar — Activity beendet")
            finish()
            return
        }

        // Permission-Request starten
        requestPermissions.launch(HealthConnectManager.REQUIRED_PERMISSIONS)
    }

    companion object {
        private const val TAG = "HealthConnectPermAct"
    }
}
