package fr.xgimi.translator

import android.os.SystemClock
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

/**
 * Traduction chinois -> français sur l'appareil (ML Kit Translate). Modèles téléchargés une seule fois.
 * Note : la reconnaissance OCR (text-recognition-chinese) est EMBARQUÉE dans l'APK : aucun téléchargement,
 * donc aucun état de modèle à gérer côté OCR ; seul le modèle de traduction passe par [DownloadGate].
 */
object FrTranslator {
    private var client: com.google.mlkit.nl.translate.Translator? = null
    private val gate = DownloadGate({ SystemClock.uptimeMillis() })

    val ready: Boolean get() = gate.state == DownloadGate.State.READY
    val state: DownloadGate.State get() = gate.state
    val lastError: String? get() = gate.lastError
    val attempts: Int get() = gate.attempts

    private fun client(): com.google.mlkit.nl.translate.Translator {
        return client ?: Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.CHINESE)
                .setTargetLanguage(TranslateLanguage.FRENCH)
                .build()
        ).also { client = it }
    }

    /** Appels simultanés = un seul téléchargement, tous les callbacks notifiés. force = ignore le délai de relance. */
    fun ensureModel(force: Boolean = false, onDone: (Boolean, String?) -> Unit) {
        gate.request(force, onDone) {
            client().downloadModelIfNeeded(DownloadConditions.Builder().build())
                .addOnSuccessListener { gate.complete(true, null) }
                .addOnFailureListener { gate.complete(false, it.message ?: it.javaClass.simpleName) }
                .addOnCanceledListener { gate.complete(false, "téléchargement annulé") }
        }
    }

    /** Le callback est appelé exactement une fois ; null = échec (jamais de faux succès). */
    fun translate(text: String, cb: (String?) -> Unit) {
        if (!ready) { cb(null); return }
        var called = false
        val once: (String?) -> Unit = { r -> if (!called) { called = true; cb(r) } }
        try {
            client().translate(text)
                .addOnSuccessListener { once(it) }
                .addOnFailureListener { once(null) }
                .addOnCanceledListener { once(null) }
        } catch (e: Throwable) {
            once(null)
        }
    }
}
