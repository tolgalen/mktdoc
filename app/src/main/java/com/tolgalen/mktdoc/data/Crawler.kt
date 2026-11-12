package com.tolgalen.mktdoc.data

import com.tolgalen.mktdoc.net.Http
import com.tolgalen.mktdoc.net.Http.BASE
import com.tolgalen.mktdoc.net.Http.HOST
import com.tolgalen.mktdoc.net.HttpStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

const val ASSET_PREFIX = "@ASSETS@/"

class CrawlOptions(
    /** A page downloaded less than this many seconds ago is not requested again (0 = always revalidate). */
    val maxAgeSec: Long = 0,
    val threads: Int = 6,
)

class CrawlResult(
    /** Resulting manifest (includes unchanged pages and failed ones, with their previous copy). */
    val pages: Map<String, PageMeta>,
    val added: List<String>,
    val modified: List<String>,
    val gone: List<String>,
    val failed: List<String>,
)

/** Minimal robots.txt: Disallow and Crawl-delay of the `*` group. */
class Robots(private val disallow: List<String>, val delay: Double) {
    fun canFetch(path: String) = disallow.none { it.isNotEmpty() && path.startsWith(it) }

    companion object {
        fun load(): Robots = try {
            val dis = ArrayList<String>(); var delay = 0.0; var applies = false
            for (raw in Http.getString("$BASE/robots.txt").lines()) {
                val line = raw.substringBefore('#').trim()
                val k = line.substringBefore(':', "").trim().lowercase()
                val v = line.substringAfter(':', "").trim()
                when (k) {
                    "user-agent" -> applies = v == "*"
                    "disallow" -> if (applies) dis.add(v)
                    "crawl-delay" -> if (applies) delay = v.toDoubleOrNull() ?: 0.0
                }
            }
            Robots(dis, delay)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Robots(emptyList(), 0.0)
        }
    }
}

/**
 * Downloads the pages of the structure, cleans them (removes Docusaurus scripts and navigation) and saves the
 * HTML of each `<article>` in [out]. With [old] already downloaded pages are reused (If-None-Match /
 * If-Modified-Since) and the new, modified or removed pages are detected.
 */
class Crawler(
    private val st: Structure,
    roots: List<Node>,
    private val old: Store?,
    private val out: Store,
    private val opts: CrawlOptions = CrawlOptions(),
    private val log: (String) -> Unit = {},
    private val progress: (Int, Int) -> Unit = { _, _ -> },
    /** Called every few dozen pages with the pages downloaded so far (to resume an interrupted download). */
    private val checkpoint: ((Map<String, PageMeta>) -> Unit)? = null,
) {
    private var roots = roots
    private val amap = ConcurrentHashMap<String, String>()   // url -> "img/xxx" ("" if it failed)
    private lateinit var robots: Robots

    // ------------------------------------------------------------------ resources
    private fun asset(url: String, kind: String): String? {
        amap[url]?.let { return it.ifEmpty { null } }
        var local = ""
        try {
            val path = try { URI(url).path ?: "" } catch (_: Exception) { "" }
            var ext = path.substringAfterLast('.', "").lowercase().let { if (Regex("[a-z0-9]{1,5}").matches(it) && '/' !in path.substringAfterLast('.')) ".$it" else "" }
            val base = sha1(url).take(16)
            if (ext.isNotEmpty()) {
                val f = File(out.assets, "$kind/$base$ext")
                if (f.isFile && f.length() > 0) { amap[url] = "$kind/${f.name}"; return amap[url] }
            }
            Http.get(url).use { r ->
                if (ext.isEmpty()) ext = when (r.header("Content-Type")?.substringBefore(';')) {
                    "image/png" -> ".png"; "image/jpeg" -> ".jpg"; "image/svg+xml" -> ".svg"
                    "image/gif" -> ".gif"; "image/webp" -> ".webp"; else -> ""
                }
                val dest = File(out.assets, "$kind/$base$ext")
                writeAtomic(dest, r.body.bytes())
                local = "$kind/${dest.name}"
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            log(Msg.t("  warning: could not download $url (${e.message})", "  aviso: no se pudo descargar $url (${e.message})"))
        }
        amap[url] = local
        return local.ifEmpty { null }
    }

    private val cssUrl = Regex("""url\(\s*(['"]?)([^)'"]+?)\1\s*\)""")

    private fun fetchCss() {
        out.assets.mkdirs()
        val parts = ArrayList<String>()
        for (cssUrl0 in st.stylesheets) {
            try {
                val text = Http.getString(cssUrl0)
                parts.add(cssUrl.replace(text) { m ->
                    val raw = m.groupValues[2].trim()
                    if (raw.startsWith("data:") || raw.startsWith("#")) m.value
                    else {
                        val abs = Http.resolve(cssUrl0, raw)
                        val loc = abs?.let { asset(it, "res") }
                        if (loc != null) "url($loc)" else "url(${abs ?: raw})"
                    }
                })
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                log(Msg.t("  warning: CSS not downloaded $cssUrl0 (${e.message})", "  aviso: CSS no descargado $cssUrl0 (${e.message})"))
            }
        }
        writeAtomic(File(out.assets, "site.css"), parts.joinToString("\n"))
    }

    // ------------------------------------------------------------------ page cleaning
    private fun clean(doc: Document, art: Element, pageUrl: String) {
        art.select("script, noscript, button, footer.theme-doc-footer, a.hash-link, nav.pagination-nav, iframe").remove()
        // Tabs: every panel is shown with its label
        for (tc in art.select("div.tabs-container")) {
            val tablist = tc.selectFirst("[role=tablist]")
            val labels = tablist?.select("[role=tab]")?.map { it.text().trim() } ?: emptyList()
            tc.select("[role=tabpanel]").forEachIndexed { i, panel ->
                panel.removeAttr("hidden")
                if (i < labels.size) panel.before("<p><strong>" + Jsoup.clean(labels[i], org.jsoup.safety.Safelist.none()) + "</strong></p>")
            }
            tablist?.remove()
        }
        // Docusaurus <details>: the content is hidden with an inline style
        for (d in art.select("details")) for (div in d.children().filter { it.tagName() == "div" }) {
            if ("display" in div.attr("style")) div.removeAttr("style")
        }
        art.select("picture source").remove()
        for (img in art.select("img")) {
            val src = img.attr("src").ifEmpty { img.attr("data-src") }
            for (a in listOf("srcset", "loading", "decoding", "data-src")) img.removeAttr(a)
            if (src.isEmpty() || src.startsWith("data:")) continue
            val abs = Http.resolve(pageUrl, src) ?: continue
            val loc = asset(abs, "img")
            img.attr("src", if (loc != null) ASSET_PREFIX + loc else abs)
        }
        for (a in art.select("a[href]")) {
            val h = a.attr("href").trim()
            if (h.startsWith("#") || h.startsWith("mailto:") || h.startsWith("tel:") || h.startsWith("javascript:")) continue
            val abs = Http.resolve(pageUrl, h) ?: continue
            val u = try { URI(abs) } catch (_: Exception) { continue }
            if (u.host == HOST) {
                a.attr("data-mk-key", normPath(u.rawPath ?: "/"))
                a.attr("data-mk-frag", u.rawFragment ?: "")
            }
            a.attr("href", abs)
        }
    }

    // ------------------------------------------------------------------ one page
    private class Fetched(val meta: PageMeta, val links: List<String>, val menu: List<Node>?, val isNew: Boolean, val changed: Boolean)

    private fun imagesPresent(meta: PageMeta): Boolean {
        val text = try { old!!.readRaw(meta) } catch (_: Exception) { return false }
        return Regex("""@ASSETS@/img/([^"'\s)]+)""").findAll(text).all { File(out.assets, "img/${it.groupValues[1]}").isFile }
    }

    private fun reuse(node: Node, meta: PageMeta, wantMenu: Boolean, changed: Boolean = false): Fetched {
        var menu: List<Node>? = null
        if (wantMenu) {
            val url = BASE + node.href
            menu = parseMenuFile(old!!.menuFile(meta), url)
        }
        return Fetched(meta, emptyList(), menu, false, changed)
    }

    private fun parseMenuFile(f: File, url: String): List<Node>? = try {
        Discovery.parseMenuDoc(Jsoup.parse(f.readText(), url), url, st.version)
    } catch (_: Exception) { null }

    private fun fetchPage(node: Node): Fetched {
        val key = node.key!!
        val url = BASE + node.href
        if (!robots.canFetch(node.href!!)) throw IOException(Msg.t("blocked by robots.txt", "bloqueada por robots.txt"))
        val wantMenu = (node.category || node.children.isNotEmpty()) && !node.expanded
        var prev = old?.manifest?.get(key)
        if (prev != null && (!old!!.rawFile(prev.file).isFile || (wantMenu && !old.menuFile(prev).isFile) || !imagesPresent(prev))) prev = null
        var headers = emptyMap<String, String>()
        if (prev != null) {
            if (opts.maxAgeSec > 0 && System.currentTimeMillis() - prev.ts <= opts.maxAgeSec * 1000) return reuse(node, prev, wantMenu)
            headers = buildMap {
                prev.etag?.let { put("If-None-Match", it) }
                prev.lastModified?.let { put("If-Modified-Since", it) }
            }
        }
        val resp = Http.get(url, headers, allow304 = headers.isNotEmpty())
        val etag: String?; val lm: String?; val body: String
        resp.use {
            if (it.code == 304 && prev != null) return reuse(node, prev.copy(ts = System.currentTimeMillis()), wantMenu)
            etag = it.header("ETag"); lm = it.header("Last-Modified"); body = it.body.string()
        }
        val doc = Jsoup.parse(body, url)
        var menuHtml: String? = null
        var menu: List<Node>? = null
        if (node.category || node.children.isNotEmpty()) {
            menuHtml = Discovery.menuHtml(doc)?.let { "<nav class=\"menu\">$it</nav>" }
            if (wantMenu) menu = Discovery.parseMenuDoc(doc, url, st.version)
        }
        val art = doc.selectFirst("article") ?: doc.selectFirst("main") ?: throw IOException(Msg.t("the page has no content", "la página no tiene contenido"))
        val links = ArrayList<String>()
        for (a in art.select("a[href]")) {
            val h = a.attr("href").trim()
            if (h.startsWith("#") || h.startsWith("mailto:") || h.startsWith("tel:") || h.startsWith("javascript:")) continue
            val abs = Http.resolve(url, h) ?: continue
            try { URI(abs).let { if (it.host == HOST) links.add(it.rawPath ?: "/") } } catch (_: Exception) {}
        }
        val title = art.selectFirst("h1")?.text()?.trim().takeUnless { it.isNullOrEmpty() } ?: node.title
        clean(doc, art, url)
        val text = textOf(art)
        val hash = sha1(text)
        val file = out.fileNameFor(key)
        writeAtomic(out.rawFile(file), art.outerHtml())
        if (menuHtml != null) writeAtomic(out.menuFile(PageMeta(key, title, hash, file = file)), menuHtml)
        val meta = PageMeta(key, title, hash, etag, lm, System.currentTimeMillis(), file)
        return Fetched(meta, links, menu, prev == null, prev != null && prev.hash != hash)
    }

    // ------------------------------------------------------------------ execution
    suspend fun run(): CrawlResult {
        robots = Robots.load()
        if (robots.delay > 0) Http.maxRps = minOf(Http.maxRps.takeIf { it > 0 } ?: 10.0, 1.0 / robots.delay)
        kotlinx.coroutines.withContext(Dispatchers.IO) { fetchCss() }

        val lock = Any()
        val reg = HashMap<String, Node>()
        Discovery.register(reg, roots)
        Discovery.register(reg, st.roots)
        val result = LinkedHashMap<String, PageMeta>()
        val added = ArrayList<String>(); val modified = ArrayList<String>()
        val gone = ArrayList<String>(); val failed = ArrayList<String>()
        val submitted = HashSet<String>()
        val sem = Semaphore(opts.threads)
        var total = 0
        var done = 0

        coroutineScope {
            val scope = this
            fun submit(n: Node) {
                val k = n.key ?: return
                if (n.href == null || !submitted.add(k)) return
                total++
                scope.launch(Dispatchers.IO) {
                    val outcome: Result<Fetched> = sem.withPermit {
                        ensureActive()
                        var attempt = 0
                        while (true) {
                            try { return@withPermit Result.success(fetchPage(n)) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                                // transient network errors (e.g. "stream was reset") are retried; HTTP errors are not
                                if (e is HttpStatus || attempt >= 2) return@withPermit Result.failure(e)
                                attempt++
                                kotlinx.coroutines.delay(1000L * attempt)
                            }
                        }
                        @Suppress("UNREACHABLE_CODE") Result.failure(IllegalStateException())
                    }
                    synchronized(lock) {
                        val f = outcome.getOrNull()
                        if (f != null) {
                            result[k] = f.meta
                            if (f.isNew) added.add(k) else if (f.changed) modified.add(k)
                            for (lp in f.links) Discovery.attachDiscovered(reg, roots, st.version, lp)?.let { submit(it) }
                            val seg = k.substringAfterLast('/').lowercase()
                            if (n.orphan || n.title.trim().lowercase() in listOf("index", seg, seg.replace('-', ' '))) n.title = f.meta.title
                            if (f.menu != null) {
                                n.expanded = true
                                val target = Discovery.findNode(f.menu, k)
                                if (target != null && target.children.isNotEmpty())
                                    for (nn in Discovery.mergeChildren(n, target.children, reg, st.roots)) submit(nn)
                            }
                        } else {
                            val e = outcome.exceptionOrNull()
                            val prev = old?.manifest?.get(k)
                            if (e is HttpStatus && (e.code == 404 || e.code == 410)) {
                                gone.add(k)
                                if (n.discovered) { n.parent?.children?.remove(n); reg.remove(k) }
                            } else if (n.discovered) {
                                n.parent?.children?.remove(n); reg.remove(k)   // link to something that is not a page
                            } else {
                                failed.add(k)
                                if (prev != null) result[k] = prev
                                log(Msg.t("  error in ${n.href}: ${e?.message}", "  error en ${n.href}: ${e?.message}"))
                            }
                        }
                        done++
                        progress(done, total)
                        if (checkpoint != null && done % 50 == 0) checkpoint.invoke(LinkedHashMap(result))
                    }
                }
            }
            val initial = roots.flatMap { r -> r.pages().toList() }
            synchronized(lock) { initial.forEach { submit(it) } }
            log(Msg.t("Downloading ${initial.size} pages (more will be discovered while reading each category menu)…", "Descargando ${initial.size} páginas (se descubrirán más al leer el menú de cada categoría)…"))
        }
        // synthetic folders left empty after moving pages to their official place
        fun prune(nodes: MutableList<Node>) {
            for (n in nodes.toList()) {
                prune(n.children)
                if (n.orphan && n.key == null && n.children.isEmpty()) nodes.remove(n)
            }
        }
        prune(st.roots)
        val reachable = st.roots.flatMap { r -> r.walk().toList() }.toHashSet()
        roots = roots.filter { it in reachable }
        log(Msg.t("${result.size} pages downloaded", "${result.size} páginas descargadas") + if (failed.isNotEmpty()) Msg.t(", ${failed.size} with errors.", ", ${failed.size} con errores.") else ".")
        return CrawlResult(result, added, modified, gone, failed)
    }

    companion object {
        private const val BLOCK_MARK = "\u0001"

        /** Normalized visible text of an element (one line per block), to compare versions of a page. */
        fun textOf(el: Element): String {
            val c = el.clone()
            c.select("p,li,h1,h2,h3,h4,h5,h6,tr,pre,div,br,table,ul,ol,blockquote,dt,dd,details,summary,section,hr").forEach { it.append(BLOCK_MARK) }
            return c.text().split(BLOCK_MARK).map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        }
    }
}
