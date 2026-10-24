package fr.xgimi.translator

import android.util.Log

object Diag {
    private val diagnosticLogs = mutableListOf<String>()

    // Variables de statut (peuvent recevoir des entiers ou des objets)
    var ocrStatus: Any? = 0
    var scanSource: Any? = 0
    var scanItems: Any? = 0
    var overlayError: Any? = null
    var storeError: Any? = null
    var recordKey: Any? = null

    // Fonction recentKeys appelée par MainActivity
    fun recentKeys(vararg args: Any?) {
        // Fonction de journalisation des clés récentes
    }

    fun log(message: String) {
        try {
            diagnosticLogs.add(0, message)
        } catch (e: Exception) {
            diagnosticLogs.add(message)
        }
        Log.d("XGIMI_Translator", message)
    }

    fun getAllLogs(): List<String> {
        return diagnosticLogs
    }
}
