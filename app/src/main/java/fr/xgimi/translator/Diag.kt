package fr.xgimi.translator

import android.util.Log
import android.view.KeyEvent

object Diag {
    private val diagnosticLogs = mutableListOf<String>()
    private val recentKeyCodes = mutableListOf<Int>()

    // Variables de statut : certaines valeurs sont hétérogènes selon le diagnostic.
    var ocrStatus: Any? = 0
    var scanSource: Any? = 0
    var scanItems: Any? = 0
    var overlayError: Any? = null
    var storeError: Any? = null

    /** Enregistre les dernières touches reçues par le service d'accessibilité. */
    @Synchronized
    fun recordKey(keyCode: Int) {
        recentKeyCodes.add(keyCode)
        while (recentKeyCodes.size > 20) recentKeyCodes.removeAt(0)
    }

    /** Retourne les touches récentes sous forme lisible pour le panneau de diagnostic. */
    @Synchronized
    fun recentKeys(): List<String> =
        recentKeyCodes.map { KeyEvent.keyCodeToString(it) }

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
