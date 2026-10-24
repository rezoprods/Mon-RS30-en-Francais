package fr.xgimi.translator

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * Enveloppe Android de [TranslationCore] : charge le glossaire (assets/glossary.json) et la mémoire
 * (files/translations.json), enregistre de façon ATOMIQUE (fichier temporaire + renommage) car un projecteur
 * est souvent débranché sans arrêt propre. Un fichier corrompu est mis de côté, jamais bloquant.
 */
object TranslationStore {
    private val core = TranslationCore()
    private var file: File? = null
    private var loaded = false
    private val handler = Handler(Looper.getMainLooper())
    private val saveTask = Runnable { flush() }

    @Synchronized
    fun init(ctx: Context) {
        if (loaded) return
        val app = ctx.applicationContext
        val f = File(app.filesDir, "translations.json")
        file = f
        try {
            val txt = app.assets.open("glossary.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            core.loadGlossary(txt)
        } catch (e: Exception) {
            Diag.storeError = "glossaire illisible : ${e.message}"
        }
        if (f.exists()) {
            try {
                core.loadCache(f.readText(Charsets.UTF_8))
            } catch (e: Exception) {
                Diag.storeError = "mémoire corrompue, mise de côté : ${e.message}"
                f.renameTo(File(f.parentFile, "translations.corrupt.json"))
            }
        }
        loaded = true
    }

    fun lookup(text: String): TranslationCore.Result? = core.lookup(text)

    fun put(text: String, translation: String): Boolean {
        val ok = core.put(text, translation)
        if (ok) {
            handler.removeCallbacks(saveTask)
            handler.postDelayed(saveTask, 3000)
        }
        return ok
    }

    fun size(): Int = core.size()
    fun glossarySize(): Int = core.glossarySize()

    fun clear() {
        handler.removeCallbacks(saveTask)
        core.clear()
        flush()
    }

    @Synchronized
    fun flush() {
        val f = file ?: return
        try {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(core.toJson(), Charsets.UTF_8)
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        } catch (e: Exception) {
            Diag.storeError = "écriture mémoire impossible : ${e.message}"
        }
    }
}
