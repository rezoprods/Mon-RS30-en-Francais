package fr.xgimi.translator

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Panneau de réglage et de diagnostic, utilisable entièrement à la télécommande (D-pad + OK). */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var btnOcr: Button
    private lateinit var btnEnabled: Button
    private var learning = false
    private var message = ""
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 2000) }
    }
    private val prefs by lazy { getSharedPreferences(Prefs.NAME, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TranslationStore.init(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(80, 48, 80, 48)
        }
        status = TextView(this).apply { textSize = 18f; setTextColor(Color.WHITE) }
        col.addView(status)
        col.addView(button("1. Activer le service (Accessibilité)") { openAccessibility() })
        col.addView(button("2. Télécharger / vérifier le modèle de traduction (internet)") { downloadModel() })
        btnEnabled = button("") { toggleEnabled() }
        col.addView(btnEnabled)
        col.addView(button("Apprendre la touche de bascule (appuyez ensuite sur la touche, 20 s)") { learnKey() })
        btnOcr = button("") { toggleOcr() }
        col.addView(btnOcr)
        col.addView(button("Vider la mémoire de traduction") {
            TranslationStore.clear(); message = "Mémoire vidée."; refresh()
        })
        col.addView(button("Ouvrir le guide des menus") { startActivity(Intent(this, GuideActivity::class.java)) })
        col.addView(TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(168, 156, 196))
            setPadding(0, 32, 0, 0)
            text = "Couleurs de l'overlay : blanc = menu connu (glossaire) ; jaune = traduction automatique ; bleu = assemblage de termes connus.\n\n" +
                "Si « Accessibilité » est absent des réglages du projecteur, via ADB :\n" +
                "adb shell settings put secure enabled_accessibility_services fr.xgimi.translator/fr.xgimi.translator.TranslatorService\n" +
                "adb shell settings put secure accessibility_enabled 1"
        })
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.rgb(13, 10, 23))
            addView(col)
        })
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun button(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 18f
        isAllCaps = false
        isFocusable = true
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 14 }
        setOnClickListener { onClick() }
    }

    private fun serviceActive(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return s?.contains("$packageName/") == true
    }

    private fun modelLabel(): String = when (FrTranslator.state) {
        DownloadGate.State.READY -> "prêt ✔"
        DownloadGate.State.DOWNLOADING -> "téléchargement en cours…"
        DownloadGate.State.FAILED -> "ÉCHEC : ${FrTranslator.lastError} (tentatives : ${FrTranslator.attempts}) — réessayez avec le bouton 2"
        DownloadGate.State.ABSENT ->
            if (prefs.getBoolean(Prefs.MODEL_OK, false)) "non revérifié depuis le démarrage (déjà téléchargé auparavant)"
            else "non téléchargé ✘"
    }

    private fun refresh() {
        val key = prefs.getInt(Prefs.TOGGLE_KEY, 0)
        val keyName = if (key == 0) "aucune" else KeyEvent.keyCodeToString(key)
        val on = prefs.getBoolean(Prefs.ENABLED, true)
        val ocr = prefs.getBoolean(Prefs.OCR, true)
        val keys = Diag.recentKeys()
        status.text = "XGIMI Traducteur FR 0.4.0\n\n" +
            "Service d'accessibilité : " + (if (serviceActive()) "ACTIF ✔" else "inactif ✘") + "\n" +
            "Traduction : " + (if (on) "activée" else "désactivée") + "\n" +
            "Modèle de traduction : ${modelLabel()}\n" +
            "OCR chinois : embarqué dans l'app (aucun téléchargement)\n" +
            "Capture/OCR : ${Diag.ocrStatus}\n" +
            "Dernière analyse : ${Diag.scanSource} (${Diag.scanItems} textes)\n" +
            "Overlay : " + (Diag.overlayError ?: "OK") + "\n" +
            "Mémoire : ${TranslationStore.size()} traductions (+ ${TranslationStore.glossarySize()} du glossaire)" +
            (Diag.storeError?.let { "\nMémoire — avertissement : $it" } ?: "") + "\n" +
            "Touche de bascule : $keyName\n" +
            "Touches reçues par le service : " + (if (keys.isEmpty()) "aucune (service inactif ou touches non transmises)" else keys.joinToString(", ")) + "\n" +
            (if (message.isNotEmpty()) "\n$message\n" else "")
        btnEnabled.text = "Traduction : " + (if (on) "ACTIVÉE (appuyer pour désactiver)" else "DÉSACTIVÉE (appuyer pour activer)")
        btnOcr.text = "OCR par capture d'écran (secours) : " + if (ocr) "OUI" else "NON"
    }

    private fun toggleEnabled() {
        prefs.edit().putBoolean(Prefs.ENABLED, !prefs.getBoolean(Prefs.ENABLED, true)).apply()
        refresh()
    }

    private fun toggleOcr() {
        prefs.edit().putBoolean(Prefs.OCR, !prefs.getBoolean(Prefs.OCR, true)).apply()
        refresh()
    }

    /** Le service (s'il est actif) capte la prochaine touche réellement transmise ; sinon l'activité prend le relais. */
    private fun learnKey() {
        prefs.edit().putLong(Prefs.LEARN_UNTIL, System.currentTimeMillis() + 20_000L).apply()
        learning = true
        message = "Appuyez maintenant sur la touche voulue (hors flèches/OK/Retour/volume)…"
        refresh()
    }

    private fun openAccessibility() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(this, "Réglages d'accessibilité indisponibles : utilisez ADB (voir ci-dessous)", Toast.LENGTH_LONG).show()
        }
    }

    private fun downloadModel() {
        message = "Téléchargement du modèle en cours…"
        refresh()
        FrTranslator.ensureModel(true) { ok, err ->
            runOnUiThread {
                prefs.edit().putBoolean(Prefs.MODEL_OK, ok).apply()
                message = if (ok) "Modèle prêt ✔" else "Échec du téléchargement : $err"
                refresh()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (learning && !isReservedKey(keyCode)) {
            prefs.edit().putInt(Prefs.TOGGLE_KEY, keyCode).putLong(Prefs.LEARN_UNTIL, 0L).apply()
            learning = false
            message = "Touche enregistrée : " + KeyEvent.keyCodeToString(keyCode)
            refresh()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
