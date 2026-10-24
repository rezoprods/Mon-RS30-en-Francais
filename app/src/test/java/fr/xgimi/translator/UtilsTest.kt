package fr.xgimi.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UtilsTest {
    @Test fun hasCjk_detecteLeChinois() {
        assertTrue(hasCjk("图像模式"))
        assertTrue(hasCjk("Wi-Fi 网络"))
        assertFalse(hasCjk("Mode d'image"))
        assertFalse(hasCjk(""))
    }

    @Test fun norm_reduitLesEspacesEtRogne() {
        assertEquals("图像 模式", norm("  图像   模式\n"))
    }

    @Test fun norm_pleineChasseDevientAscii() {
        assertEquals("Wi-Fi", norm("Ｗｉ－Ｆｉ"))
    }

    @Test fun norm_memeCleQuelleQueSoitLaMiseEnForme() {
        assertEquals(norm("图像模式"), norm(" 图像模式 "))
    }
}
