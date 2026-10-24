package fr.xgimi.translator

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TranslationCoreTest {
    private val glossary = """{"图像模式":"Mode d'image","亮度":"Luminosité","设置":"Paramètres"}"""
    private fun core(max: Int = 5000) = TranslationCore(max).also { it.loadGlossary(glossary) }

    @Test fun glossaire_exact_estPrioritaire() {
        val c = core()
        c.put("图像模式", "Image mode (auto)") // une traduction automatique ne doit pas gagner
        val r = c.lookup("图像模式")!!
        assertEquals("Mode d'image", r.text)
        assertEquals(TranslationCore.Kind.GLOSSARY, r.kind)
    }

    @Test fun cles_memeTexteAvecEspacesDifferents_memeEntree() {
        val c = core()
        assertTrue(c.put("网络  设置", "Réseau et paramètres"))
        assertEquals("Réseau et paramètres", c.lookup(" 网络 设置 ")!!.text)
        assertEquals(1, c.size())
    }

    @Test fun cles_textesDifferents_nesMelangentPas() {
        val c = core()
        c.put("网络", "Réseau")
        c.put("声音", "Son")
        assertEquals("Réseau", c.lookup("网络")!!.text)
        assertEquals("Son", c.lookup("声音")!!.text)
    }

    @Test fun doublons_remplacentSansAugmenterLaTaille() {
        val c = core()
        c.put("网络", "Réseau")
        c.put("网络", "Réseau Wi-Fi")
        assertEquals(1, c.size())
        assertEquals("Réseau Wi-Fi", c.lookup("网络")!!.text)
    }

    @Test fun substitutionPartielle_seulementSiPlusDeChinois() {
        val c = core()
        val ok = c.lookup("亮度 设置")!!
        assertEquals("Luminosité Paramètres", ok.text)
        assertEquals(TranslationCore.Kind.PARTIAL, ok.kind)
        assertNull(c.lookup("亮度 未知词")) // reste du chinois -> inconnu
    }

    @Test fun inconnu_renvoieNull() {
        assertNull(core().lookup("完全未知"))
        assertNull(core().lookup("   "))
    }

    @Test fun traductionsRejetees() {
        val c = core()
        assertFalse(c.put("网络", ""))
        assertFalse(c.put("网络", "   "))
        assertFalse(c.put("网络", "网络"))          // identique
        assertFalse(c.put("网络", "Réseau 网络"))    // encore du chinois
        assertFalse(c.put("Network", "Réseau"))     // clé sans chinois
        assertFalse(c.put("网络", "x".repeat(500))) // démesurée
        assertEquals(0, c.size())
    }

    @Test fun eviction_ladPlusAncienneQuandPlein() {
        val c = core(2)
        c.put("一", "un"); c.put("二", "deux"); c.put("三", "trois")
        assertEquals(2, c.size())
        assertNull(c.lookup("一"))
        assertEquals("trois", c.lookup("三")!!.text)
    }

    @Test fun clear_efface() {
        val c = core()
        c.put("网络", "Réseau")
        c.clear()
        assertEquals(0, c.size())
        assertNull(c.lookup("网络"))
        assertEquals(3, c.glossarySize()) // le glossaire est conservé
    }

    @Test fun persistance_allerRetourJson() {
        val a = core()
        a.put("网络", "Réseau"); a.put("声音", "Son")
        val b = core()
        assertEquals(2, b.loadCache(a.toJson()))
        assertEquals("Réseau", b.lookup("网络")!!.text)
    }

    @Test fun ancienFormatPlat_estLu() {
        val c = core()
        assertEquals(1, c.loadCache("""{"网络":"Réseau"}"""))
        assertEquals("Réseau", c.lookup("网络")!!.text)
    }

    @Test fun chargement_ignoreLesEntreesInvalides() {
        val c = core()
        assertEquals(1, c.loadCache("""{"version":1,"entries":{"网络":"Réseau","声音":"声音","abc":"x","":"y"}}"""))
    }

    @Test fun jsonCorrompu_leveUneException_etNeModifiePasLeCache() {
        val c = core()
        c.put("网络", "Réseau")
        try { c.loadCache("{pas du json"); fail("exception attendue") } catch (e: JSONException) { }
        assertEquals(1, c.size())
    }

    @Test fun isAcceptable_cas() {
        assertTrue(TranslationCore.isAcceptable("网络", "Réseau"))
        assertFalse(TranslationCore.isAcceptable("网络", "网络"))
        assertFalse(TranslationCore.isAcceptable("Réseau", "Réseau"))
    }
}
