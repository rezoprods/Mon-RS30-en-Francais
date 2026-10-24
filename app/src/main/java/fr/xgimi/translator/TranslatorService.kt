package fr.xgimi.translator

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

/**
 * Service d'accessibilité :
 *  1) lit le texte chinois exposé par les fenêtres (priorité, sans OCR) ;
 *  2) sinon, capture l'écran (API 30+) et fait l'OCR chinois embarqué (ML Kit), via [OcrPolicy] ;
 *  3) traduit (glossaire > mémoire > ML Kit) et dessine le français par-dessus (TYPE_ACCESSIBILITY_OVERLAY) ;
 *  4) activation/désactivation : touche de télécommande (apprise par le service) OU bouton de l'app.
 *
 * CONVERSION DE COORDONNÉES (méthode retenue)
 *  - Nœuds d'accessibilité : getBoundsInScreen = pixels écran.
 *  - OCR : pixels de la capture -> pixels écran par scaleBox (échelle X/Y = taille écran / taille capture).
 *  - Overlay : fenêtre MATCH_PARENT, LAYOUT_IN_SCREEN|NO_LIMITS, gravité haut-gauche. Son origine réelle est
 *    mesurée avec getLocationOnScreen (offX, offY) et soustraite des marges ; si elle diffère, un redessin
 *    est fait une fois. Taille d'écran = currentWindowMetrics.bounds (API 30+) sinon displayMetrics.
 *  - Chaque zone est bornée à l'écran par fitBox avant création de la vue.
 * Toutes les opérations s'exécutent sur le thread principal (callbacks ML Kit et capture inclus).
 */
class TranslatorService : AccessibilityService() {
    private data class Item(val text: String, val rect: Rect)

    private companion object {
        const val PENDING_TIMEOUT_MS = 20_000L
        const val FAIL_RETRY_MS = 60_000L
        const val MAX_VIEWS = 150
        const val OVERLAY_RETRY_MS = 5_000L
        const val MAX_TRACKED = 500
    }

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences(Prefs.NAME, MODE_PRIVATE) }
    private var wm: WindowManager? = null
    private var overlay: FrameLayout? = null
    private var overlayAdded = false
    private var overlayRetryAt = 0L
    private var offX = 0 // origine réelle de la fenêtre overlay par rapport à l'écran
    private var offY = 0
    private var enabled = true
    private var destroyed = false
    private var scheduled = false
    private var lastSignature = ""
    private var dirty = false // évènements reçus pendant une capture

    private val policy = OcrPolicy()
    private var recognizer: TextRecognizer? = null

    private val pending = HashMap<String, Long>()
    private val failedAt = HashMap<String, Long>()

    private val scanTask = Runnable { scheduled = false; scan() }

    // Référence forte obligatoire (SharedPreferences ne garde qu'une référence faible)
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.ENABLED) applyEnabledPref()
    }

    // ---------- Cycle de vie ----------

    override fun onServiceConnected() {
        super.onServiceConnected()
        shutdown() // état propre même si le service est reconnecté
        destroyed = false
        scheduled = false
        dirty = false
        pending.clear()
        failedAt.clear()
        lastSignature = ""
        policy.reset()
        TranslationStore.init(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        enabled = prefs.getBoolean(Prefs.ENABLED, true)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        retryModel()
        scheduleScan(500)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    /** Interruption système : on abandonne l'analyse planifiée et on replanifie (récupération). */
    override fun onInterrupt() {
        if (destroyed) return
        handler.removeCallbacks(scanTask)
        scheduled = false
        scheduleScan(1000)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        lastSignature = ""
        scheduleScan(300)
    }

    /** Idempotent : libère handler, écouteur, overlay, OCR et enregistre la mémoire. */
    private fun shutdown() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        scheduled = false
        try { prefs.unregisterOnSharedPreferenceChangeListener(prefListener) } catch (e: Throwable) { }
        policy.reset() // invalide les callbacks de capture/OCR en vol
        removeOverlay()
        try { recognizer?.close() } catch (e: Throwable) { }
        recognizer = null
        TranslationStore.flush()
    }

    // ---------- Évènements ----------

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (destroyed || event == null) return
        if (event.packageName == packageName) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) policy.onWindowChanged()
        if (!enabled) return
        if (policy.busy) dirty = true
        scheduleScan(400)
    }

    /**
     * Toutes les touches sont journalisées (Diag.recentKeys) pour savoir ce que GMUI transmet réellement.
     * Seule la touche de bascule apprise (ou la touche à apprendre) est consommée ; le reste est rendu au système.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) Diag.recordKey(event.keyCode)
        val code = event.keyCode
        if (isReservedKey(code)) return false

        if (prefs.getLong(Prefs.LEARN_UNTIL, 0L) > System.currentTimeMillis()) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                prefs.edit().putInt(Prefs.TOGGLE_KEY, code).putLong(Prefs.LEARN_UNTIL, 0L).apply()
                Toast.makeText(this, "Touche enregistrée : " + KeyEvent.keyCodeToString(code), Toast.LENGTH_SHORT).show()
            }
            return true
        }
        val key = prefs.getInt(Prefs.TOGGLE_KEY, 0)
        if (key != 0 && code == key) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) toggle()
            return true
        }
        return false
    }

    private fun toggle() {
        val now = !enabled
        prefs.edit().putBoolean(Prefs.ENABLED, now).apply() // déclenche prefListener -> applyEnabledPref
        Toast.makeText(this, if (now) "Traduction FR : activée" else "Traduction FR : désactivée", Toast.LENGTH_SHORT).show()
    }

    private fun applyEnabledPref() {
        if (destroyed) return
        val v = prefs.getBoolean(Prefs.ENABLED, true)
        if (v == enabled) return
        enabled = v
        if (enabled) { policy.onWindowChanged(); scheduleScan(100) } else clearOverlay()
    }

    private fun scheduleScan(delayMs: Long) {
        if (destroyed || scheduled) return
        scheduled = true
        handler.postDelayed(scanTask, delayMs)
    }

    private fun retryModel() {
        FrTranslator.ensureModel { ok, _ ->
            if (destroyed) return@ensureModel
            if (ok) {
                prefs.edit().putBoolean(Prefs.MODEL_OK, true).apply()
                failedAt.clear()
                scheduleScan(100)
            }
        }
    }

    private fun screenSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm?.currentWindowMetrics?.bounds
            if (b != null && b.width() > 0 && b.height() > 0) return b.width() to b.height()
        }
        val dm = resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    // ---------- Analyse ----------

    private fun scan() {
        if (destroyed) return
        if (!enabled) { clearOverlay(); return }
        val items = collectItems()
        if (items.isNotEmpty()) {
            Diag.scanSource = "accessibilité"
            Diag.scanItems = items.size
            render(items)
            return
        }
        val ocrWanted = prefs.getBoolean(Prefs.OCR, true)
        if (ocrWanted && Build.VERSION.SDK_INT >= 30) {
            ocr()
        } else {
            Diag.ocrStatus = if (!ocrWanted) "désactivé" else "indisponible (Android < 11)"
            Diag.scanSource = "aucun texte chinois accessible"
            Diag.scanItems = 0
            clearOverlay()
        }
    }

    private fun collectItems(): List<Item> {
        val out = ArrayList<Item>()
        val roots = ArrayList<AccessibilityNodeInfo>()
        try {
            for (w in windows) {
                if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
                w.root?.let { roots.add(it) }
            }
        } catch (e: Throwable) {
        }
        if (roots.isEmpty()) rootInActiveWindow?.let { roots.add(it) }
        for (r in roots) walk(r, out, 0)
        return out
    }

    private fun walk(n: AccessibilityNodeInfo, out: MutableList<Item>, depth: Int) {
        if (depth > 40 || out.size > 300) return
        if (n.isVisibleToUser) {
            val t = (n.text ?: n.contentDescription)?.toString()
            if (t != null && hasCjk(t)) {
                val r = Rect()
                n.getBoundsInScreen(r)
                // ignore les doublons parent/enfant (même texte, zones qui se recouvrent)
                if (r.width() >= 8 && r.height() >= 8 && out.none { it.text == t && Rect.intersects(it.rect, r) }) {
                    out.add(Item(t, r))
                }
            }
        }
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            walk(c, out, depth + 1)
        }
    }

    // ---------- OCR de secours : capture (API 30+) puis reconnaissance, pilotés par OcrPolicy ----------

    @SuppressLint("NewApi") // appelé uniquement si SDK_INT >= 30 (voir scan())
    private fun ocr() {
        when (val d = policy.tryStart(SystemClock.uptimeMillis())) {
            is OcrPolicy.Decision.Wait -> {
                overlay?.visibility = View.VISIBLE
                scheduleScan(d.ms)
            }
            OcrPolicy.Decision.Busy -> dirty = true
            OcrPolicy.Decision.Suspended -> {
                overlay?.visibility = View.VISIBLE
                Diag.ocrStatus = "suspendu après ${policy.failures} échecs/vides (reprise au changement de fenêtre ou après 60 s)"
                // sans évènement d'accessibilité (écran statique), rien ne relancerait l'analyse : on planifie la reprise
                scheduleScan(policy.suspendRemainingMs(SystemClock.uptimeMillis()) + 200)
            }
            is OcrPolicy.Decision.Start -> startCapture(d.token)
        }
    }

    @SuppressLint("NewApi")
    private fun startCapture(token: Int) {
        dirty = false
        Diag.ocrStatus = "capture en cours"
        overlay?.visibility = View.INVISIBLE // pour ne pas OCRiser notre propre français
        // chien de garde actif : si ni onSuccess ni onFailure n'arrive, le cycle est clos et l'overlay réaffiché
        handler.postDelayed({
            if (!destroyed && policy.isCurrent(token)) {
                endOcr(token, OcrPolicy.Outcome.ERROR, "délai de capture/OCR dépassé")
            }
        }, policy.watchdogMs() + 500)
        handler.postDelayed({
            if (destroyed || !policy.isCurrent(token)) return@postDelayed
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                            val hb = result.hardwareBuffer
                            var copy: Bitmap? = null
                            try {
                                val hw = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                                copy = hw?.copy(Bitmap.Config.ARGB_8888, false)
                                hw?.recycle() // le bitmap matériel n'est plus nécessaire une fois la copie faite
                            } catch (e: Throwable) {
                                copy = null
                            } finally {
                                hb.close() // toujours libéré
                            }
                            val bmp = copy
                            if (destroyed || !policy.isCurrent(token)) { bmp?.recycle(); return }
                            if (bmp == null) { endOcr(token, OcrPolicy.Outcome.ERROR, "capture illisible"); return }
                            recognizeBitmap(bmp, token)
                        }

                        override fun onFailure(errorCode: Int) {
                            if (destroyed || !policy.isCurrent(token)) return
                            val tooShort = errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT
                            endOcr(token, if (tooShort) OcrPolicy.Outcome.TOO_SHORT else OcrPolicy.Outcome.ERROR,
                                "échec de capture (code $errorCode)")
                        }
                    })
            } catch (e: Throwable) {
                endOcr(token, OcrPolicy.Outcome.ERROR, "exception capture : ${e.javaClass.simpleName}")
            }
        }, 80)
    }

    private fun recognizeBitmap(bmp: Bitmap, token: Int) {
        val rec = recognizer
            ?: TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()).also { recognizer = it }
        val bw = bmp.width
        val bh = bmp.height
        try {
            rec.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { txt ->
                    bmp.recycle()
                    if (destroyed || !policy.isCurrent(token)) return@addOnSuccessListener
                    val (sw, sh) = screenSize()
                    val items = ArrayList<Item>()
                    for (b in txt.textBlocks) for (l in b.lines) {
                        val box = l.boundingBox ?: continue
                        if (!hasCjk(l.text)) continue
                        val s = scaleBox(Box(box.left, box.top, box.right, box.bottom), bw, bh, sw, sh)
                        items.add(Item(l.text, Rect(s.left, s.top, s.right, s.bottom)))
                    }
                    endOcr(token, if (items.isEmpty()) OcrPolicy.Outcome.EMPTY else OcrPolicy.Outcome.SUCCESS,
                        if (items.isEmpty()) "aucun texte chinois reconnu" else "${items.size} lignes reconnues")
                    Diag.scanSource = "OCR"
                    Diag.scanItems = items.size
                    if (items.isEmpty()) clearOverlay() else render(items)
                }
                .addOnFailureListener { e ->
                    bmp.recycle()
                    if (destroyed || !policy.isCurrent(token)) return@addOnFailureListener
                    endOcr(token, OcrPolicy.Outcome.ERROR, "OCR en erreur : ${e.message}")
                }
        } catch (e: Throwable) {
            bmp.recycle()
            endOcr(token, OcrPolicy.Outcome.ERROR, "exception OCR : ${e.javaClass.simpleName}")
        }
    }

    /** Termine un cycle de capture/OCR : toujours réafficher l'overlay, mettre à jour la politique, replanifier. */
    private fun endOcr(token: Int, outcome: OcrPolicy.Outcome, detail: String) {
        if (!policy.finish(token, outcome, SystemClock.uptimeMillis())) return
        overlay?.visibility = View.VISIBLE
        Diag.ocrStatus = detail
        if (outcome == OcrPolicy.Outcome.TOO_SHORT) scheduleScan(1200)
        else if (dirty) { dirty = false; scheduleScan(300) }
    }

    // ---------- Traduction + affichage ----------

    private fun request(text: String) {
        val k = norm(text)
        val now = SystemClock.uptimeMillis()
        val iter = pending.entries.iterator() // purge des demandes restées sans réponse
        while (iter.hasNext()) { if (now - iter.next().value > PENDING_TIMEOUT_MS) iter.remove() }
        // bornage mémoire : purge des échecs expirés, plafond de suivi
        val fit = failedAt.entries.iterator()
        while (fit.hasNext()) { if (now - fit.next().value > FAIL_RETRY_MS) fit.remove() }
        if (failedAt.size > MAX_TRACKED) failedAt.clear()
        val fa = failedAt[k]
        if (fa != null && now - fa < FAIL_RETRY_MS) return
        if (pending.containsKey(k)) return
        if (!FrTranslator.ready) {
            failedAt[k] = now
            retryModel()
            return
        }
        pending[k] = now
        FrTranslator.translate(k) { res ->
            handler.post {
                if (destroyed) return@post
                pending.remove(k)
                // put() rejette vide / encore du chinois / identique : jamais de faux succès en mémoire
                if (res != null && TranslationStore.put(k, res)) {
                    scheduleScan(100)
                } else {
                    failedAt[k] = SystemClock.uptimeMillis()
                }
            }
        }
    }

    private fun render(rawItems: List<Item>) {
        if (destroyed) return
        val (sw, sh) = screenSize()
        val maxArea = 0.4 * sw * sh
        // écarte les grands conteneurs (ne pas masquer de larges zones utiles)
        val items = rawItems.filter { it.rect.width().toLong() * it.rect.height() <= maxArea }

        val known = ArrayList<Pair<Box, TranslationCore.Result>>()
        for (item in items) {
            val tr = TranslationStore.lookup(item.text)
            val r = item.rect
            if (tr != null) known.add(Box(r.left, r.top, r.right, r.bottom) to tr) else request(item.text)
        }
        if (known.size > MAX_VIEWS) known.subList(MAX_VIEWS, known.size).clear()
        val boxes = known.map { it.first }
        val layouts = ArrayList<Pair<Placed, TranslationCore.Result>>()
        for ((box, tr) in known) {
            val p = fitBox(box, tr.text.length, sw, sh, boxes) ?: continue // hors écran / trop petit : ignoré
            layouts.add(p to tr)
        }
        if (layouts.isEmpty()) { clearOverlay(); return }

        val sig = "$sw x $sh|$offX,$offY|" + layouts.joinToString("|") {
            "${it.first.left},${it.first.top},${it.first.width},${it.first.height}=${it.second.kind}:${it.second.text}"
        }
        if (sig == lastSignature && overlayAdded) return

        val root = ensureOverlay() ?: return
        lastSignature = sig
        root.removeAllViews()
        for ((p, tr) in layouts) {
            val color = when (tr.kind) {
                TranslationCore.Kind.GLOSSARY -> Color.WHITE                 // menu connu (glossaire)
                TranslationCore.Kind.MACHINE -> Color.rgb(255, 224, 140)     // traduction automatique
                TranslationCore.Kind.PARTIAL -> Color.rgb(170, 215, 255)     // assemblage de termes du glossaire
            }
            val tv = TextView(this).apply {
                text = tr.text
                setTextColor(color)
                setBackgroundColor(Color.argb(240, 20, 12, 40))
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setPadding(6, 0, 6, 0)
                setAutoSizeTextTypeUniformWithConfiguration(8, 48, 1, TypedValue.COMPLEX_UNIT_SP)
            }
            val lp = FrameLayout.LayoutParams(p.width, p.height)
            lp.leftMargin = p.left - offX
            lp.topMargin = p.top - offY
            root.addView(tv, lp)
        }
        root.visibility = View.VISIBLE
        // l'origine réelle de la fenêtre n'est connue qu'après sa mise en page : si elle diffère du décalage
        // utilisé, on redessine une fois avec le bon décalage
        root.post {
            if (destroyed || overlay !== root) return@post
            val l = IntArray(2)
            root.getLocationOnScreen(l)
            if (l[0] != offX || l[1] != offY) {
                offX = l[0]
                offY = l[1]
                lastSignature = ""
                scheduleScan(50)
            }
        }
    }

    /** Un seul overlay à la fois ; erreurs WindowManager capturées, nouvelle tentative espacée de 5 s. */
    private fun ensureOverlay(): FrameLayout? {
        if (overlayAdded) overlay?.let { return it }
        val now = SystemClock.uptimeMillis()
        if (now < overlayRetryAt) return null
        removeOverlay() // état incohérent éventuel
        return try {
            val v = FrameLayout(this)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) {
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            wm?.addView(v, lp)
            overlay = v
            overlayAdded = true
            Diag.overlayError = null
            v
        } catch (e: Throwable) {
            Diag.overlayError = e.javaClass.simpleName + ": " + e.message
            overlayRetryAt = now + OVERLAY_RETRY_MS
            null
        }
    }

    private fun removeOverlay() {
        val v = overlay
        overlay = null
        overlayAdded = false
        lastSignature = ""
        if (v != null) {
            try { wm?.removeView(v) } catch (e: Throwable) { }
        }
    }

    private fun clearOverlay() {
        overlay?.removeAllViews()
        lastSignature = ""
    }
}
