package fr.xgimi.translator

import android.view.KeyEvent
import java.text.Normalizer

object Prefs {
    const val NAME = "xgimi_prefs"
    const val ENABLED = "enabled"        // traduction active (modifiable depuis l'app ou la télécommande)
    const val TOGGLE_KEY = "toggle_key"  // touche télécommande (0 = aucune)
    const val LEARN_UNTIL = "learn_until" // apprentissage de touche par le service jusqu'à cet instant (ms epoch)
    const val OCR = "ocr"                // OCR de secours par capture d'écran
    const val MODEL_OK = "model_ok"      // dernier état connu : modèle zh->fr téléchargé (indicatif)
}

fun hasCjk(s: String): Boolean {
    for (c in s) if (c in '\u4E00'..'\u9FFF') return true
    return false
}

/** Clé canonique : NFKC (pleine chasse -> ASCII), espaces réduits, rognage. Utilisée pour glossaire ET mémoire. */
fun norm(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFKC).replace(Regex("\\s+"), " ").trim()

/** Touches qu'on ne propose / n'intercepte jamais comme bascule (navigation, volume, alimentation). */
fun isReservedKey(code: Int): Boolean = when (code) {
    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER,
    KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE -> true
    else -> false
}
