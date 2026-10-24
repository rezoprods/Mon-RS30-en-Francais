package fr.xgimi.translator

import android.view.KeyEvent

/** Diagnostics en mémoire (affichés dans le panneau) pour les tests sur le projecteur. */
object Diag {
    @Volatile var scanSource = "—"
    @Volatile var scanItems = 0
    @Volatile var ocrStatus = "—"
    @Volatile var overlayError: String? = null
    @Volatile var storeError: String? = null
    private val keys = ArrayDeque<String>()

    @Synchronized
    fun recordKey(code: Int) {
        keys.addFirst(KeyEvent.keyCodeToString(code))
        while (keys.size > 8) keys.removeLast()
    }

    @Synchronized
    fun recentKeys(): List<String> = keys.toList()
}
