package fr.xgimi.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryTest {
    private val sw = 1920
    private val sh = 1080

    @Test fun rectangleDansEcran_resteIdentique() {
        val p = fitBox(Box(100, 100, 300, 140), 5, sw, sh, emptyList())!!
        assertEquals(Placed(100, 100, 200, 40), p)
    }

    @Test fun rectangleHorsEcran_estRejete() {
        assertNull(fitBox(Box(2000, 100, 2200, 140), 5, sw, sh, emptyList()))
        assertNull(fitBox(Box(100, 1200, 300, 1240), 5, sw, sh, emptyList()))
        assertNull(fitBox(Box(-500, 100, -10, 140), 5, sw, sh, emptyList()))
    }

    @Test fun rectangleDebordant_estBorneAEcran() {
        val p = fitBox(Box(1800, 1000, 2100, 1200), 3, sw, sh, emptyList())!!
        assertTrue(p.left + p.width <= sw)
        assertTrue(p.top + p.height <= sh)
    }

    @Test fun tropPetit_estRejete() {
        assertNull(fitBox(Box(10, 10, 14, 40), 3, sw, sh, emptyList()))
        assertNull(fitBox(Box(10, 10, 200, 15), 3, sw, sh, emptyList()))
    }

    @Test fun elargissement_nePasRecouvrirLeVoisinDeLaMemeLigne() {
        val a = Box(100, 100, 200, 140)
        val b = Box(300, 105, 400, 145)
        val p = fitBox(a, 40, sw, sh, listOf(a, b))!!
        assertTrue("fin = ${p.left + p.width}", p.left + p.width <= b.left - 2)
    }

    @Test fun elargissement_ignoreLesElementsDAutresLignes() {
        val a = Box(100, 100, 200, 140)
        val below = Box(300, 400, 400, 440)
        val p = fitBox(a, 40, sw, sh, listOf(a, below))!!
        assertTrue(p.width > 100)
    }

    @Test fun elargissement_borneAEcran() {
        val a = Box(1800, 100, 1900, 140)
        val p = fitBox(a, 500, sw, sh, listOf(a))!!
        assertTrue(p.left + p.width <= sw)
    }

    @Test fun neReduitJamaisSousLaLargeurOriginale() {
        val a = Box(100, 100, 250, 140)
        val b = Box(255, 100, 400, 140) // voisin très proche
        val p = fitBox(a, 2, sw, sh, listOf(a, b))!!
        assertEquals(150, p.width)
    }

    @Test fun scaleBox_memeTailleInchange() {
        val b = Box(10, 20, 30, 40)
        assertEquals(b, scaleBox(b, 1920, 1080, 1920, 1080))
    }

    @Test fun scaleBox_miseAEchelleXY() {
        assertEquals(Box(20, 40, 60, 80), scaleBox(Box(10, 20, 30, 40), 960, 540, 1920, 1080))
    }

    @Test fun scaleBox_dimensionsInvalides_renvoieLOriginal() {
        val b = Box(1, 2, 3, 4)
        assertEquals(b, scaleBox(b, 0, 100, 1920, 1080))
        assertNotNull(scaleBox(b, 100, 100, 0, 0))
    }
}
