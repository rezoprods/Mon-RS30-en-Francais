package fr.xgimi.translator

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Rectangle indépendant d'Android (testable en JVM). Coordonnées en pixels ÉCRAN. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** Zone finale de l'overlay en pixels ÉCRAN (le décalage de la fenêtre est soustrait par l'appelant). */
data class Placed(val left: Int, val top: Int, val width: Int, val height: Int)

/**
 * Conversion capture -> écran : mise à l'échelle indépendante en X et Y.
 * Si la capture a déjà (à 1 % près) la taille de l'écran, le rectangle est renvoyé tel quel.
 */
fun scaleBox(b: Box, bmpW: Int, bmpH: Int, screenW: Int, screenH: Int): Box {
    if (bmpW <= 0 || bmpH <= 0 || screenW <= 0 || screenH <= 0) return b
    val sx = screenW / bmpW.toFloat()
    val sy = screenH / bmpH.toFloat()
    if (abs(sx - 1f) <= 0.01f && abs(sy - 1f) <= 0.01f) return b
    return Box((b.left * sx).toInt(), (b.top * sy).toInt(), (b.right * sx).toInt(), (b.bottom * sy).toInt())
}

/**
 * Borne [r] à l'écran sw x sh, puis élargit vers la droite (le français est plus long) sans
 * recouvrir le prochain élément de la même ligne. Retourne null si la zone est hors écran ou trop petite.
 */
fun fitBox(r: Box, textLen: Int, sw: Int, sh: Int, others: List<Box>, minW: Int = 8, minH: Int = 10): Placed? {
    if (sw <= 1 || sh <= 1) return null
    val left = r.left.coerceIn(0, sw - 1)
    val top = r.top.coerceIn(0, sh - 1)
    val right = r.right.coerceIn(left + 1, sw)
    val bottom = r.bottom.coerceIn(top + 1, sh)
    val h = bottom - top
    val w0 = right - left
    if (w0 < minW || h < minH) return null

    var limit = sw
    for (o in others) {
        if (o == r) continue
        val sameRow = o.top < bottom && o.bottom > top
        if (sameRow && o.left >= right && o.left - 2 < limit) limit = o.left - 2
    }
    val wanted = (textLen * h * 0.5f).toInt()
    val w = max(w0, min(limit - left, wanted)).coerceAtMost(sw - left)
    return Placed(left, top, w, h)
}
