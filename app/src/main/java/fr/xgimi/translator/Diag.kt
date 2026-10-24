package xgimi.translator

import android.util.Log

object Diag {
    private val diagnosticLogs = mutableListOf<String>()

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
