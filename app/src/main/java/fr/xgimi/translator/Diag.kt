package xgimi.translator

import android.util.Log

object Diag {
    private val diagnosticLogs = mutableListOf<String>()

    fun log(message: String) {
        // Ajout au début de la liste de manière compatible
        diagnosticLogs.add(0, message)
        Log.d("XGIMI_Translator", message)
    }

    fun getAllLogs(): List<String> {
        return diagnosticLogs
    }
}
