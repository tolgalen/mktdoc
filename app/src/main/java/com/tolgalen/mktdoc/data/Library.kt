package com.tolgalen.mktdoc.data

import android.content.Context
import com.tolgalen.mktdoc.net.Http
import com.tolgalen.mktdoc.net.HttpStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Offline copy of a manual version. */
data class LocalVersion(
    val label: String,
    val prefix: String,
    val current: Boolean,
    val aliases: Set<String>,
    val downloadedAt: Long,
    val checkedAt: Long,
    val pages: Int,
    val sizeBytes: Long,
    /** The website no longer publishes this version. */
    val removedOnline: Boolean,
    val pendingChanges: Int,
    /** Only some sections of the manual were downloaded. */
    val partial: Boolean = false,
) {
    fun toJson() = JSONObject().put("label", label).put("prefix", prefix).put("current", current)
        .put("aliases", JSONArray(aliases.toList())).put("downloadedAt", downloadedAt).put("checkedAt", checkedAt)
        .put("pages", pages).put("size", sizeBytes).put("removedOnline", removedOnline).put("pending", pendingChanges).put("partial", partial)

    companion object {
        fun fromJson(o: JSONObject): LocalVersion {
            val al = o.optJSONArray("aliases")
            return LocalVersion(o.getString("label"), o.getString("prefix"), o.optBoolean("current"),
                if (al == null) setOf(o.getString("prefix")) else List(al.length()) { al.getString(it) }.toSet(),
                o.optLong("downloadedAt"), o.optLong("checkedAt"), o.optInt("pages"), o.optLong("size"),
                o.optBoolean("removedOnline"), o.optInt("pending"), o.optBoolean("partial"))
        }
    }
}

/** Entry of the outline (table of contents) of an offline copy; [path] is relative to `site/` (null for sections without a page). */
class OutlineNode(val title: String, val path: String?, val children: List<OutlineNode>)

enum class ChangeKind { ADDED, MODIFIED, REMOVED }

class PageChange(val key: String, val title: String, val kind: ChangeKind, val removedLines: List<String>, val addedLines: List<String>)

class CheckReport(
    val label: String,
    val checkedAt: Long,
    /** The version no longer exists on the website. */
    val unavailableOnline: Boolean,
    val changes: List<PageChange>,
    val failed: Int,
    /** Prefix the website now publishes the version under (differs from the stored one once it stops being the current one). */
    val newPrefix: String? = null,
) {
    val added get() = changes.count { it.kind == ChangeKind.ADDED }
    val modified get() = changes.count { it.kind == ChangeKind.MODIFIED }
    val removed get() = changes.count { it.kind == ChangeKind.REMOVED }
    val hasChanges get() = changes.isNotEmpty()

    fun toJson() = JSONObject().put("label", label).put("checkedAt", checkedAt).put("unavailable", unavailableOnline)
        .put("failed", failed).put("newPrefix", newPrefix ?: JSONObject.NULL)
        .put("changes", JSONArray().also { a ->
            changes.forEach { c ->
                a.put(JSONObject().put("key", c.key).put("title", c.title).put("kind", c.kind.name)
                    .put("rem", JSONArray(c.removedLines)).put("add", JSONArray(c.addedLines)))
            }
        })

    companion object {
        fun fromJson(o: JSONObject): CheckReport {
            val a = o.getJSONArray("changes")
            fun strs(j: JSONArray) = List(j.length()) { j.getString(it) }
            return CheckReport(o.getString("label"), o.getLong("checkedAt"), o.getBoolean("unavailable"),
                List(a.length()) { i ->
                    val c = a.getJSONObject(i)
                    PageChange(c.getString("key"), c.getString("title"), ChangeKind.valueOf(c.getString("kind")),
                        strs(c.getJSONArray("rem")), strs(c.getJSONArray("add")))
                }, o.optInt("failed"), o.optString("newPrefix").takeIf { !o.isNull("newPrefix") && it.isNotEmpty() })
        }
    }
}

/** Progress callbacks of the long operations. */
class Reporter(val log: (String) -> Unit = {}, val progress: (phase: String, done: Int, total: Int) -> Unit = { _, _, _ -> })

class Library(private val ctx: Context) {
    private val root = File(ctx.filesDir, "docs")
    private val versionsCache = File(ctx.filesDir, "versions.json")

    fun dir(label: String) = File(root, label)
    fun siteDir(label: String) = File(dir(label), "site")
    fun store(label: String) = Store(dir(label), File(siteDir(label), "assets")).load()
    private fun metaFile(label: String) = File(dir(label), "meta.json")
    private fun stagingDir(label: String) = File(dir(label), "staging")
    private fun reportFile(label: String) = File(stagingDir(label), "report.json")

    fun local(label: String): LocalVersion? = try {
        LocalVersion.fromJson(JSONObject(metaFile(label).readText()))
    } catch (_: Exception) { null }

    fun listLocal(): List<LocalVersion> =
        (root.listFiles() ?: emptyArray()).mapNotNull { if (it.isDirectory) local(it.name) else null }
            .sortedWith { a, b -> compareVersions(b.label, a.label) }

    private fun saveMeta(m: LocalVersion) = writeAtomic(metaFile(m.label), m.toJson().toString())

    fun hasOffline(label: String) = File(siteDir(label), "index.html").isFile

    fun indexFile(label: String) = File(siteDir(label), "index.html")

    fun delete(label: String) { dir(label).deleteRecursively(); indexes.remove(label) }

    private val indexes = HashMap<String, Pair<Long, SearchIndex?>>()

    private fun searchIndex(label: String): SearchIndex? {
        val f = File(siteDir(label), "assets/search-index.js")
        if (!f.isFile) return null
        val stamp = f.lastModified() xor f.length()
        synchronized(indexes) {
            indexes[label]?.let { if (it.first == stamp) return it.second }
            val idx = SearchIndex.load(f)
            indexes[label] = stamp to idx
            return idx
        }
    }

    /** Full-text search over the downloaded versions (all of them, or only [only]). */
    suspend fun search(query: String, only: String? = null, limit: Int = 100): List<SearchHit> = withContext(Dispatchers.IO) {
        listLocal().filter { only == null || it.label == only }.flatMap { v ->
            searchIndex(v.label)?.search(query, v.label, limit) ?: emptyList()
        }.sortedByDescending { it.score }.take(limit)
    }

    /** Free space (bytes) available for the app's data. */
    fun freeSpace(): Long = android.os.StatFs(ctx.filesDir.absolutePath).availableBytes

    /** Report of the last check that is still waiting to be applied. */
    fun pendingReport(label: String): CheckReport? = try {
        CheckReport.fromJson(JSONObject(reportFile(label).readText()))
    } catch (_: Exception) { null }

    // ------------------------------------------------------------------ online versions
    /** Published versions; when offline, the last known ones. The boolean says whether the list is fresh. */
    @Volatile var lastError: String? = null

    suspend fun onlineVersions(): Pair<List<DocVersion>, Boolean> = withContext(Dispatchers.IO) {
        try {
            val v = Discovery.discoverVersions()
            writeAtomic(versionsCache, JSONArray().also { a -> v.forEach { a.put(it.toJson()) } }.toString())
            v to true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            lastError = e.toString()
            android.util.Log.w("mktdoc", "versions", e)
            cachedVersions() to false
        }
    }

    fun cachedVersions(): List<DocVersion> = try {
        val a = JSONArray(versionsCache.readText())
        List(a.length()) { DocVersion.fromJson(a.getJSONObject(it)) }
    } catch (_: Exception) { emptyList() }

    /**
     * Flags the copies whose version is no longer published and returns the ones that just became unavailable.
     * It only trusts a list obtained online and, to avoid false alarms, confirms with the website itself.
     */
    suspend fun refreshAvailability(online: List<DocVersion>): List<LocalVersion> = withContext(Dispatchers.IO) {
        val newlyGone = ArrayList<LocalVersion>()
        for (lv in listLocal()) {
            if (isDocSet(lv.label)) continue          // not a version: it is always on the website
            val ov = online.firstOrNull { it.label == lv.label }
            val gone = if (ov != null) false else try {
                !Http.exists(Http.BASE + lv.prefix + "/introduction") ||
                    // the /docs prefix is now another version
                    (lv.prefix == "/docs" && online.firstOrNull { it.current }?.label != lv.label)
            } catch (_: Exception) { false }
            if (gone != lv.removedOnline) {
                saveMeta(lv.copy(removedOnline = gone))
                if (gone) newlyGone.add(lv)
            }
        }
        newlyGone
    }

    // ------------------------------------------------------------------ download
    private fun partialMarker(label: String) = File(dir(label), ".partial")

    /**
     * Downloads a version. With [sections] (ids of top-level nodes, see [nodeId]) only those sections are read and
     * the rest of an existing copy is kept. If a previous download was interrupted, the pages already fetched are
     * not requested again.
     */
    suspend fun download(v: DocVersion, lang: String, rep: Reporter, sections: Set<String>? = null): LocalVersion {
        val dir = dir(v.label)
        if (freeSpace() < MIN_FREE_BYTES) throw java.io.IOException(Msg.t("Not enough free space (${freeSpace() / 1_000_000} MB). At least ${MIN_FREE_BYTES / 1_000_000} MB are needed.", "No hay espacio suficiente (${freeSpace() / 1_000_000} MB libres). Hacen falta al menos ${MIN_FREE_BYTES / 1_000_000} MB."))
        dir.mkdirs()
        val resuming = partialMarker(v.label).isFile
        partialMarker(v.label).writeText("1")
        rep.progress("structure", 0, 0)
        rep.log(Msg.t("Reading the structure of ${v.label}…", "Leyendo la estructura de ${v.label}…"))
        val st = Discovery.loadStructure(v, rep.log)
        val old = if (File(dir, "manifest.json").isFile) store(v.label) else null
        val out = Store(dir, File(siteDir(v.label), "assets"))
        val roots = if (sections == null) st.roots else st.roots.filter { nodeId(it) in sections }
        if (roots.isEmpty()) throw IllegalArgumentException(Msg.t("No section selected", "No hay ninguna sección seleccionada"))
        val keepOld = sections != null || resuming
        if (resuming) rep.log(Msg.t("Resuming an interrupted download…", "Reanudando una descarga interrumpida…"))
        val opts = CrawlOptions(maxAgeSec = if (resuming) 6 * 3600L else 0L)
        val res = Crawler(st, roots, old, out, opts, rep.log, { d, t -> rep.progress("pages", d, t) }, checkpoint = { snap ->
            // checkpoint: keep what is already downloaded so a cancelled download can be resumed
            synchronized(out.manifest) { out.manifest.clear(); old?.let { out.manifest.putAll(it.manifest) }; out.manifest.putAll(snap) }
            out.save()
        }).run()
        out.manifest.clear()
        if (keepOld) old?.let { out.manifest.putAll(it.manifest) }
        out.manifest.putAll(res.pages)
        out.save(); out.saveStructure(st)
        gc(out)
        val prev = local(v.label)
        val aliases = (prev?.aliases ?: emptySet()) + v.prefix
        val now = System.currentTimeMillis()
        stagingDir(v.label).deleteRecursively()
        rep.progress("render", 0, 0)
        withContext(Dispatchers.IO) { SiteRenderer.render(out, st, siteDir(v.label), lang, aliases) { d, t -> rep.progress("render", d, t) } }
        val partial = if (sections == null) false else prev?.partial ?: true
        val meta = LocalVersion(v.label, v.prefix, v.current, aliases, prev?.downloadedAt ?: now, now, out.manifest.size,
            dir.dirSize(), false, 0, partial)
        saveMeta(meta.copy(downloadedAt = now))
        partialMarker(v.label).delete()
        if (res.failed.isNotEmpty()) rep.log(Msg.t("${res.failed.size} pages could not be downloaded.", "${res.failed.size} páginas no se pudieron descargar."))
        return local(v.label)!!
    }

    /** Deletes from disk the pages that are no longer in the manifest. */
    private fun gc(s: Store) {
        val keep = s.manifest.values.flatMap { listOf(it.file, it.file.removeSuffix(".html") + ".menu.html") }.toHashSet()
        s.rawDir.listFiles()?.forEach { if (it.name !in keep) it.delete() }
    }

    /** The offline site of this copy was generated by an older renderer. */
    fun renderStale(label: String): Boolean =
        hasOffline(label) && (File(siteDir(label), SiteRenderer.RENDER_STAMP).takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull() ?: 0) < SiteRenderer.RENDER_VERSION

    fun rerender(label: String, lang: String) {
        val s = store(label)
        val st = s.loadStructure() ?: return
        val m = local(label) ?: return
        SiteRenderer.render(s, st, siteDir(label), lang, m.aliases)
    }

    // ------------------------------------------------------------------ compare with the website
    private fun migratePrefix(label: String, newPrefix: String) {
        val m = local(label) ?: return
        val s = store(label)
        val st = s.loadStructure() ?: return
        val old = m.prefix
        fun re(k: String) = if (k == old || k.startsWith("$old/")) newPrefix + k.removePrefix(old) else k
        for (r in st.roots) for (n in r.walk()) { n.key = n.key?.let(::re); n.href = n.href?.let(::re) }
        val moved = LinkedHashMap<String, PageMeta>()
        s.manifest.forEach { (k, v) -> moved[re(k)] = v.copy(key = re(k)) }
        s.manifest.clear(); s.manifest.putAll(moved)
        s.save()
        s.saveStructure(Structure(DocVersion(label, newPrefix, false), st.roots, st.stylesheets))
        saveMeta(m.copy(prefix = newPrefix, current = false, aliases = m.aliases + newPrefix))
    }

    private fun lines(html: String): List<String> =
        Crawler.textOf(Jsoup.parseBodyFragment(html).body()).lines()

    /**
     * Reads the website again and compares it with the offline copy (new, modified and removed pages).
     * The changes stay in `staging/` until [apply] is called.
     */
    suspend fun check(label: String, online: List<DocVersion>?, rep: Reporter): CheckReport {
        var m = local(label) ?: throw IllegalStateException("no local copy")
        val now = System.currentTimeMillis()
        val ov = online?.firstOrNull { it.label == label }
        var newPrefix: String? = null
        if (ov != null && ov.prefix != m.prefix) { migratePrefix(label, ov.prefix); newPrefix = ov.prefix; m = local(label)!! }
        val reachable = withContext(Dispatchers.IO) {
            try { Http.exists(if (label == CHANGELOG) Http.BASE + m.prefix else entryUrl(DocVersion(label, m.prefix))) } catch (e: HttpStatus) { true } catch (e: java.io.IOException) { throw e }
        }
        // the /docs prefix may now belong to another version
        val gone = !reachable || (!isDocSet(label) && ov == null && online != null && online.isNotEmpty())
        if (gone) {
            saveMeta(m.copy(removedOnline = true, checkedAt = now))
            return CheckReport(label, now, true, emptyList(), 0, newPrefix)
        }
        val old = store(label)
        val st = old.loadStructure() ?: throw IllegalStateException("no stored structure")
        for (r in st.roots) for (n in r.walk()) if (n.category || n.children.isNotEmpty()) n.expanded = false
        rep.log(Msg.t("Looking for new pages…", "Buscando páginas nuevas…"))
        if (label == CHANGELOG) Discovery.refreshChangelog(st, rep.log) else Discovery.addOrphans(st, probe = false, rep.log)
        stagingDir(label).deleteRecursively()
        val staging = Store(stagingDir(label), File(siteDir(label), "assets"))
        // a partial copy only checks the sections it holds
        val roots = if (m.partial) st.roots.filter { r -> r.pages().any { it.key in old.manifest } } else st.roots
        val res = Crawler(st, roots, old, staging, CrawlOptions(), rep.log, progress = { d, t -> rep.progress("pages", d, t) }).run()

        val changes = ArrayList<PageChange>()
        withContext(Dispatchers.IO) {
            rep.progress("diff", 0, 0)
            for (k in res.added) {
                val meta = res.pages[k] ?: continue
                changes.add(PageChange(k, meta.title, ChangeKind.ADDED, emptyList(), emptyList()))
            }
            for (k in res.modified) {
                val oldMeta = old.manifest[k] ?: continue
                val newMeta = res.pages[k] ?: continue
                val a = lines(old.readRaw(oldMeta)); val b = lines(staging.readRaw(newMeta))
                val aSet = a.toHashSet(); val bSet = b.toHashSet()
                changes.add(PageChange(k, newMeta.title, ChangeKind.MODIFIED,
                    a.filter { it !in bSet }.take(40), b.filter { it !in aSet }.take(40)))
            }
            for (k in res.gone) {
                changes.add(PageChange(k, old.manifest[k]?.title ?: k, ChangeKind.REMOVED, emptyList(), emptyList()))
            }
        }
        changes.sortBy { it.key }
        val report = CheckReport(label, now, false, changes, res.failed.size, newPrefix)
        if (changes.isEmpty()) {
            // no content differences: only the revalidation stamps are renewed
            old.manifest.putAll(res.pages); old.save()
            stagingDir(label).deleteRecursively()
        } else {
            staging.manifest.putAll(res.pages); staging.save(); staging.saveStructure(st)
            writeAtomic(reportFile(label), report.toJson().toString())
        }
        saveMeta(local(label)!!.copy(checkedAt = now, removedOnline = false, pendingChanges = changes.size))
        return report
    }

    /** Applies the changes of the last check to the offline copy. */
    suspend fun apply(label: String, removeGone: Boolean, lang: String, rep: Reporter) = withContext(Dispatchers.IO) {
        val report = pendingReport(label) ?: return@withContext
        val staging = Store(stagingDir(label), File(siteDir(label), "assets")).load()
        val st = staging.loadStructure() ?: return@withContext
        val store = store(label)
        val goneKeys = report.changes.filter { it.kind == ChangeKind.REMOVED }.map { it.key }.toSet()
        for (c in report.changes) {
            if (c.kind == ChangeKind.REMOVED) continue
            val meta = staging.manifest[c.key] ?: continue
            for (name in listOf(meta.file, meta.file.removeSuffix(".html") + ".menu.html")) {
                val src = File(staging.rawDir, name)
                if (src.isFile) { val dst = File(store.rawDir, name); dst.parentFile?.mkdirs(); dst.delete(); src.renameTo(dst) }
            }
        }
        val merged = LinkedHashMap(store.manifest)
        merged.putAll(staging.manifest)
        if (removeGone) {
            goneKeys.forEach { merged.remove(it) }
            for (r in st.roots.toList()) pruneKeys(r, goneKeys)
            st.roots.removeAll { it.key in goneKeys && it.children.isEmpty() }
        }
        store.manifest.clear(); store.manifest.putAll(merged)
        store.save(); store.saveStructure(st)
        gc(store)
        stagingDir(label).deleteRecursively()
        val m = local(label)!!
        rep.progress("render", 0, 0)
        SiteRenderer.render(store, st, siteDir(label), lang, m.aliases) { d, t -> rep.progress("render", d, t) }
        saveMeta(m.copy(pages = merged.size, sizeBytes = dir(label).dirSize(), pendingChanges = 0))
    }

    private fun pruneKeys(n: Node, gone: Set<String>) {
        for (c in n.children.toList()) {
            pruneKeys(c, gone)
            if (c.key in gone && c.children.isEmpty()) n.children.remove(c)
        }
    }

    fun discardPending(label: String) {
        stagingDir(label).deleteRecursively()
        local(label)?.let { saveMeta(it.copy(pendingChanges = 0)) }
    }

    /** Table of contents of the offline copy (only pages that were downloaded), for the reader's side panel. */
    suspend fun outline(label: String): List<OutlineNode> = withContext(Dispatchers.IO) {
        val s = store(label)
        val st = s.loadStructure() ?: return@withContext emptyList()
        val keys = st.roots.flatMap { r -> r.pages().toList() }.filter { it.key in s.manifest }.distinctBy { it.key }.map { it.key!! }
        val lm = SiteRenderer.localMap(st.version.prefix, keys)
        val have = keys.toHashSet()
        fun build(n: Node): OutlineNode? {
            val own = SiteRenderer.pageKeyOf(n, have)
            val kids = n.children.mapNotNull(::build)
            if (own == null && kids.isEmpty()) return null
            return OutlineNode(n.title, own?.let { lm[it] }, kids)
        }
        st.roots.mapNotNull(::build)
    }

    // ------------------------------------------------------------------ compare two copies
    private val versionBadge = Regex("^Version: \\d+(\\.\\d+)*$")

    /** Titles of the top-level sections that have at least one downloaded page (null = the copy is complete). */
    private fun heldSections(s: Store, st: Structure, partial: Boolean): Set<String>? =
        if (!partial) null else st.roots.filter { r -> r.pages().any { it.key in s.manifest } }.map { it.title }.toSet()

    /**
     * Compares two downloaded versions page by page (paths relative to each version's prefix). Partial copies are only
     * compared over the sections both hold. Results are ordered by path; [a] is the base and [b] the target:
     * ADDED = only in [b], REMOVED = only in [a].
     */
    suspend fun compareVersions(a: String, b: String, progress: (Int, Int) -> Unit): List<PageChange> = withContext(Dispatchers.IO) {
        val ma = local(a) ?: throw IllegalStateException("no local copy of $a")
        val mb = local(b) ?: throw IllegalStateException("no local copy of $b")
        val sa = store(a); val sb = store(b)
        val sta = sa.loadStructure(); val stb = sb.loadStructure()
        fun rel(k: String, p: String) = k.removePrefix(p).ifEmpty { "/" }
        val pa = sa.manifest.mapKeys { rel(it.key, ma.prefix) }
        val pb = sb.manifest.mapKeys { rel(it.key, mb.prefix) }
        fun rootMap(st: Structure?, p: String): Map<String, String> {
            val m = HashMap<String, String>()
            st?.roots?.forEach { r -> r.pages().forEach { n -> n.key?.let { m[rel(it, p)] = r.title } } }
            return m
        }
        val ra = rootMap(sta, ma.prefix); val rb = rootMap(stb, mb.prefix)
        val ha = if (sta != null) heldSections(sa, sta, ma.partial) else null
        val hb = if (stb != null) heldSections(sb, stb, mb.partial) else null
        fun eligible(k: String): Boolean {
            val root = ra[k] ?: rb[k] ?: return !ma.partial && !mb.partial
            return (ha == null || root in ha) && (hb == null || root in hb)
        }
        val keys = (pa.keys + pb.keys).filter(::eligible).sorted()
        val out = ArrayList<PageChange>()
        keys.forEachIndexed { i, k ->
            val x = pa[k]; val y = pb[k]
            when {
                x == null && y != null -> out.add(PageChange(k, y.title, ChangeKind.ADDED, emptyList(), emptyList()))
                x != null && y == null -> out.add(PageChange(k, x.title, ChangeKind.REMOVED, emptyList(), emptyList()))
                x != null && y != null && x.hash != y.hash -> {
                    val la = lines(sa.readRaw(x)).filter { !versionBadge.matches(it) }
                    val lb = lines(sb.readRaw(y)).filter { !versionBadge.matches(it) }
                    val sA = la.toHashSet(); val sB = lb.toHashSet()
                    val rem = la.filter { it !in sB }; val add = lb.filter { it !in sA }
                    if (rem.isNotEmpty() || add.isNotEmpty()) out.add(PageChange(k, y.title, ChangeKind.MODIFIED, rem.take(40), add.take(40)))
                }
            }
            if (i % 25 == 0) progress(i, keys.size)
        }
        progress(keys.size, keys.size)
        out
    }

    // ------------------------------------------------------------------ export
    /** Compresses the HTML site of a version into a ZIP (index.html at the root). */
    suspend fun exportZip(label: String, out: java.io.OutputStream, rep: Reporter) = withContext(Dispatchers.IO) {
        val site = siteDir(label)
        val files = site.walkTopDown().filter { it.isFile }.toList()
        val prefix = "mktdoc-$label/"
        ZipOutputStream(out.buffered()).use { zip ->
            zip.setLevel(6)
            files.forEachIndexed { i, f ->
                zip.putNextEntry(ZipEntry(prefix + f.relativeTo(site).path.replace(File.separatorChar, '/')))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                if (i % 40 == 0) rep.progress("zip", i, files.size)
            }
        }
        rep.progress("zip", files.size, files.size)
    }

    companion object { const val MIN_FREE_BYTES = 400L * 1024 * 1024 }
}
