package fr.xgimi.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrPolicyTest {
    private fun OcrPolicy.start(now: Long): Int =
        (tryStart(now) as OcrPolicy.Decision.Start).token

    @Test fun premiereCapture_demarreImmediatement() {
        val p = OcrPolicy()
        assertTrue(p.tryStart(0) is OcrPolicy.Decision.Start)
        assertTrue(p.busy)
    }

    @Test fun capturesConcurrentes_refusees() {
        val p = OcrPolicy()
        p.start(0)
        assertEquals(OcrPolicy.Decision.Busy, p.tryStart(100))
        assertEquals(OcrPolicy.Decision.Busy, p.tryStart(9_999))
    }

    @Test fun succes_remetLesEchecsAZero_etRespecteLeDelaiDeBase() {
        val p = OcrPolicy()
        val t = p.start(0)
        assertTrue(p.finish(t, OcrPolicy.Outcome.SUCCESS, 100))
        assertEquals(0, p.failures)
        val d = p.tryStart(500) as OcrPolicy.Decision.Wait
        assertEquals(1000L, d.ms) // 1500 - 500
        assertTrue(p.tryStart(1500) is OcrPolicy.Decision.Start)
    }

    @Test fun echecs_delaiExponentielPlafonne() {
        val p = OcrPolicy()
        assertEquals(1500L, p.backoffMs())
        var now = 0L
        val expected = listOf(3000L, 6000L, 12000L, 24000L)
        for (e in expected) {
            val t = p.start(now)
            p.finish(t, OcrPolicy.Outcome.ERROR, now)
            assertEquals(e, p.backoffMs())
            now += 30_000
        }
    }

    @Test fun apresMaxEchecs_suspension_pasDeBoucleInfinie() {
        val p = OcrPolicy()
        var now = 0L
        repeat(6) {
            val t = p.start(now)
            p.finish(t, OcrPolicy.Outcome.EMPTY, now)
            now += 30_000
        }
        assertTrue(p.suspended)
        assertEquals(OcrPolicy.Decision.Suspended, p.tryStart(now))
    }

    @Test fun changementDeFenetre_leveLaSuspension() {
        val p = OcrPolicy()
        var now = 0L
        repeat(6) { val t = p.start(now); p.finish(t, OcrPolicy.Outcome.ERROR, now); now += 30_000 }
        p.onWindowChanged()
        assertFalse(p.suspended)
        assertEquals(0, p.failures)
        assertTrue(p.tryStart(now) is OcrPolicy.Decision.Start)
    }

    @Test fun suspension_expireApres60s_puisUneSeuleTentative() {
        val p = OcrPolicy()
        var now = 0L
        repeat(6) { val t = p.start(now); p.finish(t, OcrPolicy.Outcome.ERROR, now); now += 30_000 }
        val suspendedAt = now - 30_000 // instant du 6e échec
        assertEquals(OcrPolicy.Decision.Suspended, p.tryStart(suspendedAt + 59_000))
        val later = suspendedAt + 61_000
        val t = p.start(later) // une seule tentative
        p.finish(t, OcrPolicy.Outcome.ERROR, later)
        assertTrue(p.suspended) // re-suspendu aussitôt
    }

    @Test fun callbackJamaisRecu_chienDeGarde_libereEtIgnoreLeCallbackTardif() {
        val p = OcrPolicy()
        val old = p.start(0)
        val d = p.tryStart(10_001)
        assertTrue(d is OcrPolicy.Decision.Wait || d is OcrPolicy.Decision.Start)
        assertFalse(p.finish(old, OcrPolicy.Outcome.SUCCESS, 10_100)) // périmé
        assertEquals(1, p.failures) // le délai dépassé compte comme un échec
    }

    @Test fun jetonPerime_ignore() {
        val p = OcrPolicy()
        val t = p.start(0)
        p.reset()
        assertFalse(p.finish(t, OcrPolicy.Outcome.SUCCESS, 10))
        assertFalse(p.busy)
    }

    @Test fun intervalleTropCourt_nestPasUnEchec() {
        val p = OcrPolicy()
        val t = p.start(0)
        assertTrue(p.finish(t, OcrPolicy.Outcome.TOO_SHORT, 10))
        assertEquals(0, p.failures)
    }

    @Test fun finishSansCaptureEnCours_estRefuse() {
        val p = OcrPolicy()
        assertFalse(p.finish(1, OcrPolicy.Outcome.SUCCESS, 0))
    }

    @Test fun suspendRemaining_zeroSiNonSuspendue() {
        assertEquals(0L, OcrPolicy().suspendRemainingMs(1_000))
    }

    @Test fun suspendRemaining_decroitPuisVautZero() {
        val p = OcrPolicy(baseDelayMs = 0L, maxFailures = 1, suspendMs = 60_000L)
        val tk = p.start(0)
        assertTrue(p.finish(tk, OcrPolicy.Outcome.ERROR, 1_000))
        assertTrue(p.suspended)
        assertEquals(60_000L, p.suspendRemainingMs(1_000))
        assertEquals(30_000L, p.suspendRemainingMs(31_000))
        assertEquals(0L, p.suspendRemainingMs(61_000))
    }

    @Test fun watchdog_valeurParDefaut() {
        assertEquals(10_000L, OcrPolicy().watchdogMs())
    }
}
