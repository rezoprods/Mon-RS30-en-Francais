package fr.xgimi.translator

import kotlin.math.max
import kotlin.math.min

/**
 * Politique de capture/OCR, sans dépendance Android (testable en JVM) :
 *  - une seule capture à la fois (Busy) ; chien de garde si le callback ne vient jamais ;
 *  - délai exponentiel après échec ou résultat vide ;
 *  - après [maxFailures] échecs consécutifs : suspension jusqu'à un changement de fenêtre,
 *    ou [suspendMs] ; puis UNE tentative avant nouvelle suspension (pas de boucle infinie) ;
 *  - jeton : un callback périmé est ignoré (finish renvoie false).
 * Le temps est passé en paramètre (millisecondes monotones).
 */
class OcrPolicy(
    private val baseDelayMs: Long = 1500L,
    private val maxDelayMs: Long = 24_000L,
    private val timeoutMs: Long = 10_000L,
    private val maxFailures: Int = 6,
    private val suspendMs: Long = 60_000L
) {
    sealed class Decision {
        data class Start(val token: Int) : Decision()
        data class Wait(val ms: Long) : Decision()
        object Busy : Decision()
        object Suspended : Decision()
    }

    enum class Outcome { SUCCESS, EMPTY, ERROR, TOO_SHORT }

    var busy = false
        private set
    var failures = 0
        private set
    var suspended = false
        private set
    private var token = 0
    private var startedAt = 0L
    private var lastStartAt = 0L
    private var hasStarted = false
    private var suspendedAt = 0L

    /** Délai du chien de garde (ms) : au-delà, un callback de capture/OCR absent est traité comme un échec. */
    fun watchdogMs(): Long = timeoutMs

    /** Temps restant avant la fin de la suspension (0 si non suspendue) : permet de replanifier la reprise. */
    fun suspendRemainingMs(now: Long): Long =
        if (suspended) max(0L, suspendMs - (now - suspendedAt)) else 0L

    fun backoffMs(): Long = min(baseDelayMs shl min(failures, 4), maxDelayMs)

    fun tryStart(now: Long): Decision {
        if (suspended) {
            if (now - suspendedAt < suspendMs) return Decision.Suspended
            suspended = false
            failures = maxFailures - 1 // une seule tentative avant nouvelle suspension
        }
        if (busy) {
            if (now - startedAt < timeoutMs) return Decision.Busy
            token++ // le callback tardif sera ignoré
            busy = false
            registerFailure(now)
            if (suspended) return Decision.Suspended
        }
        if (hasStarted) {
            val wait = backoffMs() - (now - lastStartAt)
            if (wait > 0) return Decision.Wait(wait)
        }
        busy = true
        startedAt = now
        lastStartAt = now
        hasStarted = true
        token++
        return Decision.Start(token)
    }

    fun isCurrent(t: Int): Boolean = busy && t == token

    /** @return false si le jeton est périmé (résultat à ignorer). */
    fun finish(t: Int, outcome: Outcome, now: Long): Boolean {
        if (!isCurrent(t)) return false
        busy = false
        when (outcome) {
            Outcome.SUCCESS -> failures = 0
            Outcome.EMPTY, Outcome.ERROR -> registerFailure(now)
            Outcome.TOO_SHORT -> { /* simple report, pas un échec */ }
        }
        return true
    }

    /** Nouvelle fenêtre = nouvelle chance. */
    fun onWindowChanged() {
        failures = 0
        suspended = false
    }

    fun reset() {
        token++
        busy = false
        failures = 0
        suspended = false
        hasStarted = false
    }

    private fun registerFailure(now: Long) {
        failures++
        if (failures >= maxFailures && !suspended) {
            suspended = true
            suspendedAt = now
        }
    }
}
