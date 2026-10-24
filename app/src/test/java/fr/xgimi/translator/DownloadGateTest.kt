package fr.xgimi.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadGateTest {
    private var now = 1_000L
    private fun gate() = DownloadGate({ now }, 30_000L)

    @Test fun demandesSimultanees_unSeulStart_tousNotifies() {
        val g = gate()
        var starts = 0
        val results = ArrayList<Boolean>()
        repeat(3) { g.request(false, { ok, _ -> results.add(ok) }) { starts++ } }
        assertEquals(1, starts)
        assertEquals(DownloadGate.State.DOWNLOADING, g.state)
        g.complete(true, null)
        assertEquals(listOf(true, true, true), results)
        assertEquals(DownloadGate.State.READY, g.state)
    }

    @Test fun pasDePretAvantLeSucces() {
        val g = gate()
        g.request(false, { _, _ -> }) { }
        assertFalse(g.state == DownloadGate.State.READY)
    }

    @Test fun dejaPret_repondImmediatement_sansRelancer() {
        val g = gate()
        g.request(false, { _, _ -> }) { }
        g.complete(true, null)
        var starts = 0
        var got: Boolean? = null
        g.request(false, { ok, _ -> got = ok }) { starts++ }
        assertEquals(0, starts)
        assertEquals(true, got)
    }

    @Test fun echec_notifieErreur_etEtatFailed() {
        val g = gate()
        var err: String? = null
        g.request(false, { _, e -> err = e }) { }
        g.complete(false, "réseau")
        assertEquals("réseau", err)
        assertEquals(DownloadGate.State.FAILED, g.state)
        assertEquals("réseau", g.lastError)
    }

    @Test fun apresEchec_relanceAutoEspacee_maisForceOutrepasse() {
        val g = gate()
        g.request(false, { _, _ -> }) { }
        g.complete(false, "x")
        var starts = 0
        var immediate: Boolean? = null
        g.request(false, { ok, _ -> immediate = ok }) { starts++ }
        assertEquals(0, starts)           // trop tôt
        assertEquals(false, immediate)    // réponse immédiate négative
        g.request(true, { _, _ -> }) { starts++ }
        assertEquals(1, starts)           // force
        g.complete(true, null)
        assertEquals(DownloadGate.State.READY, g.state)
    }

    @Test fun apresLeDelai_relanceAutomatiquePossible() {
        val g = gate()
        g.request(false, { _, _ -> }) { }
        g.complete(false, "x")
        now += 30_001
        var starts = 0
        g.request(false, { _, _ -> }) { starts++ }
        assertEquals(1, starts)
        assertEquals(2, g.attempts)
    }

    @Test fun startQuiLeveUneException_estUnEchecNotifie() {
        val g = gate()
        var ok: Boolean? = null
        var err: String? = null
        g.request(false, { o, e -> ok = o; err = e }) { throw IllegalStateException("boom") }
        assertEquals(false, ok)
        assertEquals("boom", err)
        assertEquals(DownloadGate.State.FAILED, g.state)
    }

    @Test fun callbackDefaillant_nebloquePasLesAutres() {
        val g = gate()
        var second = false
        g.request(false, { _, _ -> throw RuntimeException("cb1") }) { }
        g.request(false, { _, _ -> second = true }) { }
        g.complete(true, null)
        assertTrue(second)
    }

    @Test fun completionSansTelechargement_ignoree() {
        val g = gate()
        g.complete(true, null)
        assertEquals(DownloadGate.State.ABSENT, g.state)
        assertNull(g.lastError)
    }

    @Test fun doubleCompletion_ignoree() {
        val g = gate()
        var calls = 0
        g.request(false, { _, _ -> calls++ }) { }
        g.complete(true, null)
        g.complete(false, "tard")
        assertEquals(1, calls)
        assertEquals(DownloadGate.State.READY, g.state)
    }
}
