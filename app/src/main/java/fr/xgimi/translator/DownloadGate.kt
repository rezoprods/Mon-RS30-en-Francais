package fr.xgimi.translator

/**
 * Porte de téléchargement d'un modèle, sans dépendance Android (testable en JVM) :
 * états ABSENT / DOWNLOADING / READY / FAILED ; demandes simultanées = UN SEUL start() et tous les
 * callbacks notifiés ; READY seulement après complete(true) ; relance auto espacée après échec.
 */
class DownloadGate(
    private val clock: () -> Long,
    private val retryDelayMs: Long = 30_000L
) {
    enum class State { ABSENT, DOWNLOADING, READY, FAILED }

    var state = State.ABSENT
        private set
    var lastError: String? = null
        private set
    var attempts = 0
        private set
    private var lastFailAt = 0L
    private val waiters = ArrayList<(Boolean, String?) -> Unit>()

    /**
     * @param start lance le téléchargement réel ; doit finir par appeler [complete]. Une exception = échec.
     * Les callbacks sont appelés hors verrou.
     */
    fun request(force: Boolean, onDone: (Boolean, String?) -> Unit, start: () -> Unit) {
        var immediate: Pair<Boolean, String?>? = null
        var begin = false
        synchronized(this) {
            if (state == State.READY) {
                immediate = true to null
            } else if (!force && state == State.FAILED && clock() - lastFailAt < retryDelayMs) {
                immediate = false to lastError
            } else {
                waiters.add(onDone)
                if (state != State.DOWNLOADING) {
                    state = State.DOWNLOADING
                    attempts++
                    begin = true
                }
            }
        }
        val imm = immediate
        if (imm != null) {
            onDone(imm.first, imm.second)
            return
        }
        if (begin) {
            try {
                start()
            } catch (e: Throwable) {
                complete(false, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Ignoré si aucun téléchargement n'est en cours (complétion tardive ou doublon). */
    fun complete(ok: Boolean, error: String?) {
        val callbacks: List<(Boolean, String?) -> Unit>
        synchronized(this) {
            if (state != State.DOWNLOADING) return
            if (ok) {
                state = State.READY
                lastError = null
            } else {
                state = State.FAILED
                lastError = error
                lastFailAt = clock()
            }
            callbacks = ArrayList(waiters)
            waiters.clear()
        }
        for (cb in callbacks) {
            try { cb(ok, error) } catch (e: Throwable) { /* un callback défaillant ne bloque pas les autres */ }
        }
    }
}
