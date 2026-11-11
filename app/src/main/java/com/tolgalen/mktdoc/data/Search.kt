package com.tolgalen.mktdoc.data

import org.json.JSONObject
import java.io.File

/** A page found by the native search. */
data class SearchHit(val label: String, val path: String, val title: String, val crumbs: String, val score: Int)

/** Parsed `assets/search-index.js` of one downloaded version. */
class SearchIndex(val titles: List<String>, val crumbs: List<String>, val urls: List<String>, private val postings: Map<String, String>) {
    private val cache = HashMap<String, IntArray>()

    private fun posting(token: String): IntArray = cache.getOrPut(token) {
        val s = postings[token] ?: return@getOrPut IntArray(0)
        if (s.isEmpty()) IntArray(0) else s.split(',').map { it.toInt(36) }.toIntArray()
    }

    /** Pages containing every query term (a term matches the words that start with it). Title matches rank first. */
    fun search(query: String, label: String, limit: Int): List<SearchHit> {
        val terms = TOKEN.findAll(query.lowercase()).map { it.value }.toList().distinct()
        if (terms.isEmpty()) return emptyList()
        var result: Set<Int>? = null
        for (t in terms) {
            val docs = HashSet<Int>()
            for (k in postings.keys) if (k.startsWith(t)) posting(k).forEach { docs.add(it) }
            result = if (result == null) docs else result.intersect(docs)
            if (result.isEmpty()) return emptyList()
        }
        return result!!.map { i ->
            val title = titles[i].lowercase()
            var score = 0
            for (t in terms) if (title.contains(t)) score += 10
            if (title == query.trim().lowercase()) score += 50
            SearchHit(label, urls[i], titles[i], crumbs[i], score)
        }.sortedWith(compareByDescending<SearchHit> { it.score }.thenBy { it.title.length }).take(limit)
    }

    companion object {
        val TOKEN = Regex("[\\p{L}\\p{N}]{2,}")

        fun load(f: File): SearchIndex? = try {
            val text = f.readText().removePrefix("window.MK_INDEX=").trimEnd().removeSuffix(";")
            val o = JSONObject(text)
            fun list(k: String) = o.getJSONArray(k).let { a -> List(a.length()) { a.getString(it) } }
            val inv = o.getJSONObject("i")
            val map = HashMap<String, String>(inv.length() * 2)
            for (k in inv.keys()) map[k] = inv.getString(k)
            SearchIndex(list("t"), list("c"), list("u"), map)
        } catch (_: Exception) { null }
    }
}
