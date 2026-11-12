package com.tolgalen.mktdoc.data

import com.tolgalen.mktdoc.net.Http
import com.tolgalen.mktdoc.net.Http.BASE
import com.tolgalen.mktdoc.net.Http.HOST
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

/** Discovery of the available versions and of the manual structure (sidebar menu). */
object Discovery {
    private val verRe = Regex("^/docs/(\\d+(?:\\.\\d+)*)/introduction$")
    private val verPathRe = Regex("^/docs/\\d+(?:\\.\\d+)*(?:/|$)")

    val fallbackVersions = listOf(DocVersion("7.26", "/docs", true), DocVersion("7.25", "/docs/7.25"), DocVersion("7.24", "/docs/7.24"))

    private fun pathOf(url: String): String = try { URI(url).rawPath ?: "/" } catch (e: Exception) { "/" }
    private fun hostOf(url: String): String? = try { URI(url).host } catch (e: Exception) { null }

    /** Versions published on the website (the current one first). Throws when offline. */
    fun discoverVersions(): List<DocVersion> {
        val url = "$BASE/docs/introduction"
        val doc = Jsoup.parse(Http.getString(url), url)
        var cur = doc.selectFirst("meta[name=docusaurus_version]")?.attr("content")?.trim()?.ifEmpty { null }
        val found = HashMap<String, String>()
        for (a in doc.select("a[href]")) {
            val abs = Http.resolve(url, a.attr("href")) ?: continue
            val p = normPath(pathOf(abs))
            val m = verRe.matchEntire(p)
            if (m != null) found[m.groupValues[1]] = p.substringBeforeLast('/')
            else if (p == "/docs/introduction" && cur == null) {
                val txt = a.text().trim()
                if (Regex("\\d+(?:\\.\\d+)*").matches(txt)) cur = txt
            }
        }
        val out = mutableListOf(DocVersion(cur ?: "actual", "/docs", true))
        for (label in found.keys.sortedWith { x, y -> compareVersions(y, x) }) {
            if (label != cur) out.add(DocVersion(label, found.getValue(label)))
        }
        return out
    }

    fun inVersion(path: String, prefix: String, current: Boolean): Boolean {
        val n = normPath(path)
        if (!(n == prefix || n.startsWith("$prefix/"))) return false
        if (current && verPathRe.containsMatchIn(n)) return false
        return true
    }

    private fun menuUl(doc: Document): Element? =
        doc.selectFirst("ul.theme-doc-sidebar-menu") ?: doc.selectFirst("nav.menu ul.menu__list")

    fun parseMenu(ul: Element, pageUrl: String, v: DocVersion, seen: MutableSet<String>): List<Node> {
        val nodes = ArrayList<Node>()
        for (li in ul.children().filter { it.tagName() == "li" }) {
            var a: Element? = null
            var sub: Element? = null
            for (c in li.children()) {
                if (c.hasClass("menu__link")) a = a ?: c
                else if (c.tagName() == "ul" && c.hasClass("menu__list")) sub = sub ?: c
                else if (c.tagName() == "div") for (d in c.children()) {
                    if (d.hasClass("menu__link")) a = a ?: d
                    else if (d.tagName() == "ul" && d.hasClass("menu__list")) sub = sub ?: d
                }
            }
            val title = a?.text()?.trim().orEmpty()
            var href: String? = null
            var key: String? = null
            val rawHref = a?.attr("href").orEmpty()
            if (a != null && a.tagName() == "a" && rawHref.isNotEmpty() && !rawHref.startsWith("#")) {
                val abs = Http.resolve(pageUrl, rawHref)
                if (abs != null && hostOf(abs) == HOST) {
                    val p = pathOf(abs)
                    if (inVersion(p, v.prefix, v.current)) {
                        href = p; key = normPath(p)
                        if (!seen.add(key)) key = null     // page repeated in the menu: linked but not duplicated
                    }
                }
            }
            val node = Node(title.ifEmpty { key ?: "?" }, href, key)
            val classes = li.className() + " " + (a?.className() ?: "")
            node.category = sub != null || "item-category" in classes || "menu__link--sublist" in classes
            if (sub != null) parseMenu(sub, pageUrl, v, seen).forEach { node.add(it) }
            nodes.add(node)
        }
        return nodes
    }

    fun parseMenuDoc(doc: Document, pageUrl: String, v: DocVersion): List<Node> =
        menuUl(doc)?.let { parseMenu(it, pageUrl, v, HashSet()) } ?: emptyList()

    fun menuHtml(doc: Document): String? = menuUl(doc)?.outerHtml()

    fun register(reg: MutableMap<String, Node>, nodes: List<Node>) {
        for (n in nodes) for (x in n.walk()) x.key?.let { reg.putIfAbsent(it, x) }
    }

    /** Merges the children read from the menu (official order and titles) with the known ones. Returns the new nodes. */
    fun mergeChildren(node: Node, parsed: List<Node>, reg: MutableMap<String, Node>, roots: MutableList<Node>): List<Node> {
        val newNodes = ArrayList<Node>()
        val result = ArrayList<Node>()
        for (p in parsed) {
            var c = p.key?.let { reg[it] }
            if (c == null || c === node) {
                c = p
                register(reg, listOf(p))
                newNodes.addAll(p.walk().filter { it.key != null })
            } else {
                if (c.parent !== node) {                  // it hung from somewhere else: move it here
                    (c.parent?.children ?: roots).remove(c)
                    c.parent = null
                }
                if (p.title.isNotEmpty()) c.title = p.title
                c.orphan = false
                c.category = c.category || p.category
                if (p.children.isNotEmpty()) newNodes.addAll(mergeChildren(c, p.children, reg, roots))
            }
            result.add(c)
        }
        val rest = node.children.filter { c -> result.none { it === c } }
        node.children.clear()
        (result + rest).forEach { node.add(it) }
        return newNodes
    }

    fun findNode(nodes: List<Node>, key: String): Node? {
        for (n in nodes) for (x in n.walk()) if (x.key == key) return x
        return null
    }

    /** Creates a node for a linked page that was in neither the menu nor the sitemap. */
    fun attachDiscovered(reg: MutableMap<String, Node>, selected: List<Node>, v: DocVersion, path: String): Node? {
        val key = normPath(path)
        val prefix = v.prefix
        if (key in reg || !inVersion(key, prefix, v.current) || key == prefix) return null
        val last = key.substringAfterLast('/')
        if ('.' in last || key.startsWith("$prefix/tags")) return null
        var p = key.substringBeforeLast('/')
        while (p.isNotEmpty() && p != prefix) {
            val par = reg[p]
            if (par != null) {
                var top: Node = par
                while (top.parent != null) top = top.parent!!
                if (selected.none { it === top }) return null
                val node = Node(prettyName(last), path, key)
                node.orphan = true; node.discovered = true
                par.add(node); par.category = true
                reg[key] = node
                return node
            }
            p = p.substringBeforeLast('/', "")
        }
        return null
    }

    // ------------------------------------------------------------------ list of site pages
    private val locRe = Regex("<loc>\\s*([^<\\s]+)\\s*</loc>")

    private fun siteLocs(log: (String) -> Unit): List<String> {
        var out = emptyList<String>()
        try {
            out = locRe.findAll(Http.getString("$BASE/sitemap.xml")).map { it.groupValues[1] }.toList()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            log("sitemap.xml: ${e.message}")
        }
        if (out.isEmpty()) try {
            val txt = Http.getString("$BASE/llms.txt")
            out = Regex("\\((https?://[^)\\s]+?)\\.md\\)").findAll(txt).map {
                val u = it.groupValues[1]; if (u.endsWith("/index")) u.dropLast(5) else u
            }.toList()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            log("llms.txt: ${e.message}")
        }
        return out
    }

    /** Older versions have no sitemap: the current version's paths are translated and probed to see which ones exist. */
    private suspend fun probeOld(locs: List<String>, prefix: String, log: (String) -> Unit): List<String> {
        val cands = ArrayList<String>()
        for (loc in locs) {
            if (hostOf(loc) != HOST) continue
            val p = normPath(pathOf(loc))
            if (!p.startsWith("/docs/") || verPathRe.containsMatchIn(p) || p.startsWith("/docs/tags")) continue
            cands.add(BASE + prefix + p.removePrefix("/docs") + "/")
        }
        log(Msg.t("Probing ${cands.size} candidate pages in $prefix…", "Sondeando ${cands.size} páginas candidatas en $prefix…"))
        val sem = Semaphore(12)
        val found = coroutineScope {
            cands.map { u ->
                async(Dispatchers.IO) {
                    sem.withPermit {
                        ensureActive()
                        try { if (Http.exists(u)) u else null } catch (e: Exception) { if (e is CancellationException) throw e; null }
                    }
                }
            }.awaitAll().filterNotNull()
        }
        log(Msg.t("${found.size} of them exist in this version.", "${found.size} de ellas existen en esta versión."))
        return found
    }

    /** Adds to the structure the pages known from the sitemap that are not in the menu. */
    suspend fun addOrphans(st: Structure, probe: Boolean, log: (String) -> Unit) {
        var locs = withContext(Dispatchers.IO) { siteLocs(log) }
        if (locs.isEmpty()) return
        val prefix = st.version.prefix
        if (!st.version.current) {
            if (!probe) return
            locs = probeOld(locs, prefix, log)
        }
        val byKey = HashMap<String, Node>()
        for (r in st.roots) for (n in r.pages()) byKey[n.key!!] = n
        // the tag pages of the RouterOS docs are not wanted; the hardware guides' few tag pages are part of that manual
        val tags = if (st.version.label == HARDWARE) "$prefix/\u0000none" else "$prefix/tags"
        val orphans = ArrayList<Node>()
        for (loc in locs) {
            if (hostOf(loc) != HOST) continue
            val path = pathOf(loc)
            if (!inVersion(path, prefix, st.version.current)) continue
            val k = normPath(path)
            if (k == prefix || k == tags || k.startsWith("$tags/") || k in byKey) continue
            orphans.add(Node(prettyName(k.substringAfterLast('/')), path, k).also { it.orphan = true })
        }
        if (orphans.isEmpty()) return

        fun ensure(path: String): Node? {
            byKey[path]?.let { return it }
            if (path == prefix || !path.startsWith("$prefix/")) return null
            val parent = ensure(path.substringBeforeLast('/'))
            val folder = Node(prettyName(path.substringAfterLast('/'))).also { it.orphan = true }
            if (parent != null) parent.add(folder) else st.roots.add(folder)
            byKey[path] = folder
            return folder
        }
        for (o in orphans.sortedBy { it.key }) {
            val parent = ensure(o.key!!.substringBeforeLast('/'))
            if (parent != null) parent.add(o) else st.roots.add(o)
            byKey[o.key!!] = o
        }
        for (r in st.roots) for (n in r.walk()) if (n.children.isNotEmpty()) n.category = true
        log(Msg.t("${orphans.size} pages from the sitemap were added to the structure.", "Se añadieron ${orphans.size} páginas del sitemap a la estructura."))
    }

    // ------------------------------------------------------------------ changelog
    private val postRe = Regex("/changelog/changelog-(\\d{4})-(\\d{2})-(\\d{2})$")
    private val extraPaths = listOf("/changelog/archive", "/changelog/tags", "/changelog/authors")

    private fun monthLabel(ym: String): String = try {
        java.time.YearMonth.parse(ym).format(java.time.format.DateTimeFormatter.ofPattern("LLLL yyyy", java.util.Locale.getDefault()))
            .replaceFirstChar { it.uppercase() }
    } catch (_: Exception) { ym }

    private fun isMonthFolder(n: Node) = n.key == null && n.children.isNotEmpty() && n.children.all { postRe.containsMatchIn(it.key ?: "") }

    /** Changelog tree: entries grouped by month (newest first) and, at the end, archive/tags/authors. */
    private fun buildChangelogRoots(posts: Map<String, String>, extras: List<Node>): MutableList<Node> {
        val roots = mutableListOf<Node>()
        val byMonth = posts.keys.sortedDescending().groupBy { k -> postRe.find(k)!!.let { "${it.groupValues[1]}-${it.groupValues[2]}" } }
        for ((ym, keys) in byMonth) {
            val folder = Node(monthLabel(ym)).also { it.orphan = true; it.category = true }
            keys.forEach { k -> folder.add(Node(posts.getValue(k), k, k).also { it.orphan = true }) }
            roots.add(folder)
        }
        roots.addAll(extras)
        return roots
    }

    /** Entries (and archive, tags and authors pages) listed by the sitemap. */
    private fun changelogLocs(log: (String) -> Unit): Pair<List<String>, List<String>> {
        val paths = siteLocs(log).filter { hostOf(it) == HOST }.map { normPath(pathOf(it)) }
        return paths.filter { postRe.containsMatchIn(it) }.distinct() to extraPaths.filter { it in paths }
    }

    suspend fun loadChangelogStructure(log: (String) -> Unit): Structure {
        val url = "$BASE/changelog"
        val css = withContext(Dispatchers.IO) {
            Jsoup.parse(Http.getString(url), url).select("link[rel=stylesheet][href]").mapNotNull { Http.resolve(url, it.attr("href")) }
        }
        val (posts, extras) = withContext(Dispatchers.IO) { changelogLocs(log) }
        if (posts.isEmpty()) throw java.io.IOException(Msg.t("No changelog entries were found", "No se encontraron entradas del changelog"))
        log(Msg.t("${posts.size} changelog entries.", "${posts.size} entradas del changelog."))
        val extraNodes = extras.map { Node(prettyName(it.substringAfterLast('/')), it, it).also { n -> n.orphan = true } }
        return Structure(changelogVersion(), buildChangelogRoots(posts.associateWith { it.substringAfter("changelog-") }, extraNodes), css)
    }

    /** Adds the new sitemap entries to the stored structure (to look for news). */
    suspend fun refreshChangelog(st: Structure, log: (String) -> Unit) {
        val (posts, extras) = withContext(Dispatchers.IO) { changelogLocs(log) }
        val known = HashMap<String, String>()
        val extraNodes = ArrayList<Node>()
        for (r in st.roots) {
            if (isMonthFolder(r)) r.children.forEach { known[it.key!!] = it.title } else extraNodes.add(r)
        }
        val before = known.size
        for (k in posts) known.putIfAbsent(k, k.substringAfter("changelog-"))
        for (e in extras) if (extraNodes.none { it.key == e }) extraNodes.add(Node(prettyName(e.substringAfterLast('/')), e, e).also { it.orphan = true })
        st.roots.clear()
        st.roots.addAll(buildChangelogRoots(known, extraNodes))
        if (known.size > before) log(Msg.t("${known.size - before} new entries in the sitemap.", "${known.size - before} entradas nuevas en el sitemap."))
    }

    /** Structure of a version: sidebar menu + sitemap + second level (so subsections can be chosen). */
    suspend fun loadStructure(v: DocVersion, log: (String) -> Unit): Structure {
        if (v.label == CHANGELOG) return loadChangelogStructure(log)
        val url = entryUrl(v)
        val (doc, css) = withContext(Dispatchers.IO) {
            val d = Jsoup.parse(Http.getString(url), url)
            d to d.select("link[rel=stylesheet][href]").mapNotNull { Http.resolve(url, it.attr("href")) }
        }
        val st = Structure(v, parseMenuDoc(doc, url, v).toMutableList(), css)
        if (st.roots.isEmpty()) log(Msg.t("Warning: the sidebar menu was not found; the sitemap will be used.", "Aviso: no se encontró el menú lateral; se usará el sitemap."))
        addOrphans(st, probe = true, log)
        expandMenus(st, log, deep = false)
        return st
    }

    /** Top-level sections of a version, from the sidebar menu of one page (fast; used to pick what to download). */
    suspend fun loadRoots(v: DocVersion): List<Node> = withContext(Dispatchers.IO) {
        val url = entryUrl(v)
        parseMenuDoc(Jsoup.parse(Http.getString(url), url), url, v)
    }

    /** Reads each category page to learn its children (first level only, or all of them with `deep`). */
    suspend fun expandMenus(st: Structure, log: (String) -> Unit, deep: Boolean) {
        val reg = HashMap<String, Node>()
        register(reg, st.roots)
        var todo = st.roots.flatMap { r -> r.pages().filter { it.href != null && (it.category || it.children.isNotEmpty()) }.toList() }
        val seen = todo.mapNotNull { it.key }.toMutableSet()
        val sem = Semaphore(8)
        while (todo.isNotEmpty()) {
            val batch = todo; todo = emptyList()
            val results = coroutineScope {
                batch.map { n ->
                    async(Dispatchers.IO) {
                        sem.withPermit {
                            ensureActive()
                            try {
                                val url = BASE + n.href
                                n to parseMenuDoc(Jsoup.parse(Http.getString(url), url), url, st.version)
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                log(Msg.t("  warning: could not read a category menu (${e.message})", "  aviso: no se pudo leer el menú de una categoría (${e.message})")); null
                            }
                        }
                    }
                }.awaitAll()
            }
            val next = ArrayList<Node>()
            for (r in results) {
                val (n, menu) = r ?: continue
                n.expanded = true
                val target = n.key?.let { findNode(menu, it) }
                if (target != null && target.children.isNotEmpty()) {
                    for (nn in mergeChildren(n, target.children, reg, st.roots)) {
                        if (deep && nn.key != null && seen.add(nn.key!!) && (nn.category || nn.children.isNotEmpty()) && nn.href != null) next.add(nn)
                    }
                }
            }
            todo = next
        }
    }
}
