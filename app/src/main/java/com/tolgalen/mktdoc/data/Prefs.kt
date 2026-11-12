package com.tolgalen.mktdoc.data

import android.content.Context
import android.net.Uri
import com.tolgalen.mktdoc.pdf.ImageQuality
import com.tolgalen.mktdoc.pdf.PageBreaks
import com.tolgalen.mktdoc.pdf.PdfLayout
import com.tolgalen.mktdoc.pdf.PdfOptions
import com.tolgalen.mktdoc.pdf.TextSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A page of an offline copy: [path] is relative to the version's `site/` folder. */
data class PageRef(val label: String, val path: String, val title: String) {
    fun toJson() = org.json.JSONObject().put("l", label).put("p", path).put("t", title)

    companion object {
        fun fromJson(o: org.json.JSONObject) = PageRef(o.getString("l"), o.getString("p"), o.optString("t"))
    }
}

enum class ThemeMode { SYSTEM, DARK, NAVY, BLACK, LIGHT }

/** Color palette: mktdoc's own, base Material Design, or one generated from a seed color. */
enum class ThemePalette { MKTDOC, MATERIAL, OCEAN, FOREST, VIOLET, AMBER, ROSE, SLATE }

/** App settings (SharedPreferences). */
class Prefs(ctx: Context, name: String = "mktdoc") {
    private val sp = ctx.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(
        runCatching { ThemeMode.valueOf(sp.getString("theme", ThemeMode.DARK.name)!!) }.getOrDefault(ThemeMode.DARK))
    val theme: StateFlow<ThemeMode> = _theme
    fun setTheme(t: ThemeMode) { _theme.value = t; sp.edit().putString("theme", t.name).apply() }

    private val _palette = MutableStateFlow(
        runCatching { ThemePalette.valueOf(sp.getString("palette", ThemePalette.MKTDOC.name)!!) }.getOrDefault(ThemePalette.MKTDOC))
    val palette: StateFlow<ThemePalette> = _palette
    fun setPalette(p: ThemePalette) { _palette.value = p; sp.edit().putString("palette", p.name).apply() }

    private val _dynamic = MutableStateFlow(sp.getBoolean("dynamic_color", false))
    val dynamicColor: StateFlow<Boolean> = _dynamic
    fun setDynamicColor(v: Boolean) { _dynamic.value = v; sp.edit().putBoolean("dynamic_color", v).apply() }

    /** Text size when reading the offline documentation (% of the normal size). */
    private val _zoom = MutableStateFlow(sp.getInt("reader_zoom", 100).coerceIn(60, 220))
    val readerZoom: StateFlow<Int> = _zoom
    fun setReaderZoom(v: Int) { _zoom.value = v; sp.edit().putInt("reader_zoom", v).apply() }

    private val _periodic = MutableStateFlow(sp.getBoolean("periodic", true))
    val periodicCheck: StateFlow<Boolean> = _periodic
    fun setPeriodic(v: Boolean) { _periodic.value = v; sp.edit().putBoolean("periodic", v).apply() }

    /** Download only on unmetered connections (Wi-Fi). */
    private val _wifiOnly = MutableStateFlow(sp.getBoolean("wifi_only", false))
    val wifiOnly: StateFlow<Boolean> = _wifiOnly
    fun setWifiOnly(v: Boolean) { _wifiOnly.value = v; sp.edit().putBoolean("wifi_only", v).apply() }

    /** Notify when the website publishes a RouterOS version that was not in the list before. */
    private val _notifyNew = MutableStateFlow(sp.getBoolean("notify_new_versions", true))
    val notifyNewVersions: StateFlow<Boolean> = _notifyNew
    fun setNotifyNewVersions(v: Boolean) { _notifyNew.value = v; sp.edit().putBoolean("notify_new_versions", v).apply() }

    /** Notify when a newer release of the app is published on GitHub. */
    private val _notifyApp = MutableStateFlow(sp.getBoolean("notify_app_updates", true))
    val notifyAppUpdates: StateFlow<Boolean> = _notifyApp
    fun setNotifyAppUpdates(v: Boolean) { _notifyApp.value = v; sp.edit().putBoolean("notify_app_updates", v).apply() }

    /** Once a week, on Wi-Fi, look for changes in the downloaded copies (nothing is applied; the changes wait for review). */
    private val _bgContent = MutableStateFlow(sp.getBoolean("bg_content_check", false))
    val backgroundContentCheck: StateFlow<Boolean> = _bgContent
    fun setBackgroundContentCheck(v: Boolean) { _bgContent.value = v; sp.edit().putBoolean("bg_content_check", v).apply() }

    var lastNotifiedAppVersion: String?
        get() = sp.getString("last_notified_app", null)
        set(v) { sp.edit().putString("last_notified_app", v).apply() }

    /**
     * Remembers the RouterOS versions seen on the website and returns the ones that are new. The first call only records
     * them (nothing to compare with), so installing the app does not announce every existing version.
     */
    fun registerOnlineVersions(labels: Collection<String>): List<String> {
        val seen = sp.getStringSet("seen_versions", null)
        sp.edit().putStringSet("seen_versions", labels.toSet() + (seen ?: emptySet())).apply()
        return if (seen == null) emptyList() else labels.filter { it !in seen }
    }

    private val _rps = MutableStateFlow(sp.getInt("rps", 10))
    val maxRps: StateFlow<Int> = _rps
    fun setMaxRps(v: Int) { _rps.value = v; sp.edit().putInt("rps", v).apply() }

    private inline fun <reified E : Enum<E>> enumOf(key: String, def: E): E =
        runCatching { enumValueOf<E>(sp.getString(key, def.name)!!) }.getOrDefault(def)

    fun pdfOptions(lang: String) = PdfOptions(
        layout = enumOf("pdf_layout", PdfLayout.SINGLE),
        coverTitle = sp.getString("pdf_title", "") ?: "",
        coverImage = sp.getString("pdf_image", null)?.let { Uri.parse(it) },
        imageQuality = enumOf("pdf_quality", ImageQuality.BALANCED),
        textSize = enumOf("pdf_text", TextSize.NORMAL),
        breaks = enumOf("pdf_breaks", PageBreaks.CONTINUOUS),
        lang = lang,
    )

    fun savePdfOptions(o: PdfOptions) {
        sp.edit().putString("pdf_layout", o.layout.name).putString("pdf_title", o.coverTitle)
            .putString("pdf_image", o.coverImage?.toString()).putString("pdf_quality", o.imageQuality.name)
            .putString("pdf_text", o.textSize.name).putString("pdf_breaks", o.breaks.name).apply()
    }

    // ------------------------------------------------------------------ scroll position of the pages read
    private val scroll = LinkedHashMap<String, Float>().also { m ->
        runCatching {
            val o = org.json.JSONObject(sp.getString("scroll", "{}")!!)
            for (k in o.keys()) m[k] = o.getDouble(k).toFloat()
        }
    }
    private var scrollDirty = 0

    /** Fraction (0..1) of the page height the reader had scrolled to when it was last read, or null. */
    fun scrollOf(label: String, path: String): Float? = synchronized(scroll) { scroll["$label|$path"] }

    fun setScroll(label: String, path: String, fraction: Float) = synchronized(scroll) {
        val k = "$label|$path"
        scroll.remove(k)
        if (fraction > 0.02f) scroll[k] = fraction
        while (scroll.size > 300) scroll.remove(scroll.keys.first())
        if (++scrollDirty >= 3) { scrollDirty = 0; persistScroll() }
    }

    /** Writes the positions now (when the reader is left). */
    fun flushScroll() = synchronized(scroll) { scrollDirty = 0; persistScroll() }

    private fun persistScroll() {
        val o = org.json.JSONObject(); scroll.forEach { (k, v) -> o.put(k, v.toDouble()) }
        sp.edit().putString("scroll", o.toString()).apply()
    }

    // ------------------------------------------------------------------ bookmarks and reading position
    private fun readRefs(key: String): List<PageRef> = try {
        val a = org.json.JSONArray(sp.getString(key, "[]"))
        List(a.length()) { PageRef.fromJson(a.getJSONObject(it)) }
    } catch (_: Exception) { emptyList() }

    private fun writeRefs(key: String, v: List<PageRef>) =
        sp.edit().putString(key, org.json.JSONArray().also { a -> v.forEach { a.put(it.toJson()) } }.toString()).apply()

    private val _bookmarks = MutableStateFlow(readRefs("bookmarks"))
    val bookmarks: StateFlow<List<PageRef>> = _bookmarks

    fun isBookmarked(label: String, path: String) = _bookmarks.value.any { it.label == label && it.path == path }

    fun toggleBookmark(ref: PageRef) {
        val cur = _bookmarks.value
        _bookmarks.value = if (cur.any { it.label == ref.label && it.path == ref.path })
            cur.filterNot { it.label == ref.label && it.path == ref.path } else cur + ref
        writeRefs("bookmarks", _bookmarks.value)
    }

    /** Puts a bookmark back at [index] (undo of a removal). */
    fun insertBookmark(index: Int, ref: PageRef) {
        val cur = _bookmarks.value.filterNot { it.label == ref.label && it.path == ref.path }
        _bookmarks.value = cur.toMutableList().also { it.add(index.coerceIn(0, it.size), ref) }
        writeRefs("bookmarks", _bookmarks.value)
    }

    /** The bookmarks as a JSON document (to save them in a file). */
    fun bookmarksJson(): String = org.json.JSONObject().put("app", "mktdoc")
        .put("bookmarks", org.json.JSONArray().also { a -> _bookmarks.value.forEach { a.put(it.toJson()) } }).toString(2)

    /** Adds the bookmarks of a file made by [bookmarksJson] that are not there yet. Returns how many were added, or null if it is not such a file. */
    fun importBookmarks(text: String): Int? {
        val refs = try {
            val o = org.json.JSONObject(text)
            if (o.optString("app") != "mktdoc") return null
            val a = o.getJSONArray("bookmarks")
            List(a.length()) { PageRef.fromJson(a.getJSONObject(it)) }
        } catch (_: Exception) { return null }
        val cur = _bookmarks.value
        val add = refs.filter { r -> cur.none { it.label == r.label && it.path == r.path } }.distinctBy { it.label to it.path }
        if (add.isNotEmpty()) { _bookmarks.value = cur + add; writeRefs("bookmarks", _bookmarks.value) }
        return add.size
    }

    fun removeBookmarksOf(label: String) {
        _bookmarks.value = _bookmarks.value.filter { it.label != label }
        writeRefs("bookmarks", _bookmarks.value)
        if (_recent.value.any { it.label == label }) { _recent.value = _recent.value.filter { it.label != label }; writeRefs("recent", _recent.value) }
    }

    /** Last pages read, newest first. */
    private val _recent = MutableStateFlow(readRefs("recent"))
    val recent: StateFlow<List<PageRef>> = _recent

    fun pushRecent(ref: PageRef) {
        if (ref.path == "index.html" || ref.title.isBlank()) return
        _recent.value = (listOf(ref) + _recent.value.filterNot { it.label == ref.label && it.path == ref.path }).take(15)
        writeRefs("recent", _recent.value)
    }

    fun clearRecent() { _recent.value = emptyList(); writeRefs("recent", emptyList()) }
}
