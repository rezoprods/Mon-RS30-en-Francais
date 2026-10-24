package fr.xgimi.translator

import android.util.Log

object Diag {
    private val diagnosticLogs = mutableListOf<String>()

    // Bons types pour éviter les erreurs d'appel
    var recentKeys: MutableList<String> = mutableListOf()
    var ocrStatus: String = ""
    var scanSource: String = ""
    var scanItems: MutableList<String> = mutableListOf()
    var overlayError: String = ""
    var storeError: String = ""
    var recordKey: String = ""

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
