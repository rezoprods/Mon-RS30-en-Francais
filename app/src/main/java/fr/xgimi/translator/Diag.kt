package fr.xgimi.translator

import android.util.Log

object Diag {
    private val diagnosticLogs = mutableListOf<String>()

    // Variables globales requises par les services et activités
    var recentKeys: Any? = null
    var ocrStatus: Any? = null
    var scanSource: Any? = null
    var scanItems: Any? = null
    var overlayError: Any? = null
    var storeError: Any? = null
    var recordKey: Any? = null

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
