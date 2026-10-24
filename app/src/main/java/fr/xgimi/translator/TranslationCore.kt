package fr.xgimi.translator

import org.json.JSONObject
import kotlin.math.max

/**
 * Logique de la mémoire de traduction, sans dépendance Android (testable en JVM).
 * Priorité : glossaire du guide (menus connus) > mémoire (traductions automatiques) > substitution partielle.
 * Toutes les clés passent par [norm] (une seule forme canonique, pas de doublons ni de mélange).
 */
class TranslationCore(private val maxEntries: Int = 5000) {
    enum class Kind { GLOSSARY, PARTIAL, MACHINE }
    data class Result(val text: String, val kind: Kind)

    private val glossary = LinkedHashMap<String, String>()
    private var sortedKeys: List<String> = emptyList()
    private val cache = LinkedHashMap<String, String>()

    @Synchronized
    fun loadGlossary(json: String): Int {
        val o = JSONObject(json)
        glossary.clear()
        for (k in o.keys()) {
            val v = o.getString(k).trim()
            val nk = norm(k)
            if (v.isNotEmpty() && hasCjk(nk)) glossary[nk] = v
        }
        sortedKeys = glossary.keys.sortedByDescending { it.length }
        return glossary.size
    }

    /** Accepte le format {"version":1,"entries":{...}} et l'ancien format (objet plat). Lève JSONException si corrompu. */
    @Synchronized
    fun loadCache(json: String): Int {
        val o = JSONObject(json)
        val src = if (o.has("entries")) o.getJSONObject("entries") else o
        val loaded = LinkedHashMap<String, String>()
        for (k in src.keys()) {
            if (src === o && k == "version") continue
            val v = src.optString(k, "")
            if (isAcceptable(k, v)) loaded[norm(k)] = v.trim()
        }
        cache.clear()
        cache.putAll(loaded)
        return cache.size
    }

    @Synchronized
    fun toJson(): String =
        JSONObject().put("version", 1).put("entries", JSONObject(cache as Map<*, *>)).toString()
    @Synchronized
    fun lookup(text: String): Result? {
        val k = norm(text)
        if (k.isEmpty()) return null
        glossary[k]?.let { return Result(it, Kind.GLOSSARY) }
        cache[k]?.let { return Result(it, Kind.MACHINE) }
        var r = k
        for (key in sortedKeys) {
            if (r.contains(key)) r = r.replace(key, glossary[key] ?: key)
        }
        return if (r != k && !hasCjk(r)) Result(r, Kind.PARTIAL) else null
    }

    /** @return false si la traduction est rejetée (vide, encore du chinois, identique, démesurée). */
    @Synchronized
    fun put(text: String, translation: String): Boolean {
        val k = norm(text)
        if (!isAcceptable(k, translation)) return false
        if (!cache.containsKey(k) && cache.size >= maxEntries) {
            cache.remove(cache.keys.iterator().next()) // évince la plus ancienne
        }
        cache[k] = translation.trim()
        return true
    }

    @Synchronized fun size(): Int = cache.size
    @Synchronized fun glossarySize(): Int = glossary.size
    @Synchronized fun clear() = cache.clear()

    companion object {
        fun isAcceptable(src: String, out: String): Boolean {
            val t = out.trim()
            if (!hasCjk(src)) return false
            if (t.isEmpty() || hasCjk(t)) return false
            if (norm(t) == norm(src)) return false
            return t.length <= max(200, src.length * 8)
        }
    }
}
