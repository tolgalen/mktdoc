package com.tolgalen.mktdoc.ui

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tolgalen.mktdoc.MktdocApp
import com.tolgalen.mktdoc.R
import com.tolgalen.mktdoc.data.CheckReport
import com.tolgalen.mktdoc.data.Discovery
import com.tolgalen.mktdoc.data.DocVersion
import com.tolgalen.mktdoc.data.LocalVersion
import com.tolgalen.mktdoc.data.Node
import com.tolgalen.mktdoc.data.nodeId
import com.tolgalen.mktdoc.data.Structure
import com.tolgalen.mktdoc.data.dirSize
import com.tolgalen.mktdoc.data.writeAtomic
import com.tolgalen.mktdoc.pdf.PdfExporter
import com.tolgalen.mktdoc.pdf.PdfOptions
import com.tolgalen.mktdoc.pdf.PdfSink
import com.tolgalen.mktdoc.work.Jobs
import com.tolgalen.mktdoc.work.VersionCheckWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale

class TreeState(val loading: Boolean = false, val structure: Structure? = null, val error: String? = null, val label: String = "", val local: Boolean = false)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val a = app as MktdocApp
    val prefs = a.prefs
    val library = a.library

    private val _online = MutableStateFlow<List<DocVersion>>(library.cachedVersions())
    val online: StateFlow<List<DocVersion>> = _online
    private val _onlineOk = MutableStateFlow<Boolean?>(null)
    val onlineOk: StateFlow<Boolean?> = _onlineOk
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading
    private val _local = MutableStateFlow(library.listLocal())
    val local: StateFlow<List<LocalVersion>> = _local
    private val _tree = MutableStateFlow(TreeState())
    val tree: StateFlow<TreeState> = _tree

    /** Language chosen in the app (per-app locale) or, if none, the system one. */
    private val locale: Locale get() = AppCompatDelegate.getApplicationLocales().let { if (it.isEmpty) Locale.getDefault() else it[0]!! }
    private val lang get() = if (locale.language == "es") "es" else "en"

    /** Application resources do not follow the per-app locale on their own, so build a localized context. */
    private fun str(id: Int, vararg args: Any): String {
        val cfg = android.content.res.Configuration(a.resources.configuration).also { it.setLocale(locale) }
        return a.createConfigurationContext(cfg).getString(id, *args)
    }

    init {
        refresh()
        viewModelScope.launch {
            var was = false
            Jobs.state.collect { s ->
                if (was && !s.running) refreshLocal()
                was = s.running
            }
        }
    }

    fun refreshLocal() { _local.value = library.listLocal() }

    fun refresh() {
        if (_loading.value) return
        viewModelScope.launch {
            _loading.value = true
            val (v, ok) = library.onlineVersions()
            _online.value = v; _onlineOk.value = ok
            if (ok) {
                val gone = library.refreshAvailability(v)
                if (gone.isNotEmpty()) VersionCheckWorker.notifyGone(a, gone.map { it.label })
            }
            refreshLocal()
            _loading.value = false
        }
    }

    // ------------------------------------------------------------------ long operations
    private fun busy() = Jobs.state.value.running

    private fun requireNetwork() {
        if (!prefs.wifiOnly.value) return
        val cm = a.getSystemService(android.net.ConnectivityManager::class.java)
        if (cm.isActiveNetworkMetered) throw java.io.IOException(str(R.string.err_wifi_only))
    }

    /** Downloads a version; with [sections] only those top-level sections (ids from [nodeId]). */
    fun download(v: DocVersion, sections: Set<String>? = null) {
        Jobs.start(a, str(R.string.job_download, v.label)) { rep ->
            requireNetwork()
            val m = library.download(v, lang, rep, sections)
            if (m.label == com.tolgalen.mktdoc.data.CHANGELOG) str(R.string.msg_downloaded_changelog, m.pages) else str(R.string.msg_downloaded, m.label, m.pages)
        }
    }

    fun check(label: String) {
        val online = if (_onlineOk.value == true) _online.value else null
        Jobs.start(a, str(R.string.job_check, label)) { rep ->
            requireNetwork()
            val r = library.check(label, online, rep)
            when {
                r.unavailableOnline -> {
                    VersionCheckWorker.notifyGone(a, listOf(label))
                    str(R.string.msg_unavailable, label)
                }
                !r.hasChanges -> str(R.string.msg_no_changes, label)
                else -> str(R.string.msg_changes, r.added, r.modified, r.removed)
            }
        }
    }

    fun apply(label: String, removeGone: Boolean) {
        Jobs.start(a, str(R.string.job_apply, label)) { rep ->
            library.apply(label, removeGone, lang, rep)
            str(R.string.msg_applied, label)
        }
    }

    fun discard(label: String) { library.discardPending(label); refreshLocal() }

    fun delete(label: String) { library.delete(label); prefs.removeBookmarksOf(label); refreshLocal() }

    // ------------------------------------------------------------------ compare versions
    class CompareState(val a: String = "", val b: String = "", val done: Int = 0, val total: Int = 0,
                       val result: List<com.tolgalen.mktdoc.data.PageChange>? = null, val error: String? = null)

    private val _compare = MutableStateFlow(CompareState())
    val compare: StateFlow<CompareState> = _compare
    private var compareJob: kotlinx.coroutines.Job? = null

    /** Compares two downloaded versions (the older one is the base). */
    fun compareVersions(x: String, y: String) {
        val (a, b) = if (com.tolgalen.mktdoc.data.compareVersions(x, y) <= 0) x to y else y to x
        val cur = _compare.value
        if (cur.a == a && cur.b == b && (cur.result != null || compareJob?.isActive == true)) return
        compareJob?.cancel()
        _compare.value = CompareState(a, b)
        compareJob = viewModelScope.launch {
            try {
                val r = library.compareVersions(a, b) { d, t -> _compare.value = CompareState(a, b, d, t) }
                _compare.value = CompareState(a, b, result = r)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _compare.value = CompareState(a, b, error = e.message ?: e.toString())
            }
        }
    }

    // ------------------------------------------------------------------ search
    private val _hits = MutableStateFlow<List<com.tolgalen.mktdoc.data.SearchHit>>(emptyList())
    val hits: StateFlow<List<com.tolgalen.mktdoc.data.SearchHit>> = _hits
    private var searchJob: kotlinx.coroutines.Job? = null

    fun search(q: String, only: String? = null) {
        searchJob?.cancel()
        if (q.trim().length < 2) { _hits.value = emptyList(); return }
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(200)
            _hits.value = library.search(q, only)
        }
    }

    fun exportZip(label: String, uri: Uri) {
        Jobs.start(a, str(R.string.job_zip, label)) { rep ->
            val out = a.contentResolver.openOutputStream(uri, "w") ?: error("Could not open the destination")
            library.exportZip(label, out, rep)
            str(R.string.msg_zip_done, label)
        }
    }

    fun rerender(label: String) {
        Jobs.start(a, str(R.string.job_apply, label)) { _ ->
            withContext(Dispatchers.IO) { library.rerender(label, lang) }
            null
        }
    }

    // ------------------------------------------------------------------ PDF
    private fun cachedStructureFile(label: String) = File(a.cacheDir, "structures/$label.json")

    /** Loads the section tree. With [local] it comes from the offline copy (no network). */
    fun loadTree(v: DocVersion, forceOnline: Boolean = false, local: Boolean = false) {
        if (_tree.value.label == v.label && _tree.value.structure != null && !forceOnline && _tree.value.local == local) return
        _tree.value = TreeState(loading = true, label = v.label, local = local)
        viewModelScope.launch {
            try {
                if (local) {
                    val ls = withContext(Dispatchers.IO) { library.store(v.label).loadStructure() }
                    _tree.value = if (ls != null) TreeState(structure = ls, label = v.label, local = true)
                    else TreeState(error = "No stored structure", label = v.label, local = true)
                    return@launch
                }
                var st: Structure? = null
                if (!forceOnline) st = withContext(Dispatchers.IO) {
                    runCatching { Structure.fromJson(JSONObject(cachedStructureFile(v.label).readText())) }.getOrNull()
                        ?: library.store(v.label).loadStructure()
                }
                if (st == null) {
                    st = try {
                        Discovery.loadStructure(v) { Jobs.log(it) }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        withContext(Dispatchers.IO) { library.store(v.label).loadStructure() } ?: throw e
                    }
                    val s2 = st
                    withContext(Dispatchers.IO) { writeAtomic(cachedStructureFile(v.label), s2.toJson().toString()) }
                }
                _tree.value = TreeState(structure = st, label = v.label, local = false)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _tree.value = TreeState(error = e.message ?: e.toString(), label = v.label, local = local)
            }
        }
    }

    /** `treeUri`: chosen folder (one PDF per section); otherwise `docUri` is the single chosen file. */
    fun exportPdf(v: DocVersion, selected: List<Node>, opts: PdfOptions, docUri: Uri?, treeUri: Uri?, local: Boolean = false) {
        val st = _tree.value.structure ?: return
        val o = opts.copy(lang = lang)
        prefs.savePdfOptions(o)
        val resolver = a.contentResolver
        val sink = PdfSink { name ->
            if (docUri != null) resolver.openOutputStream(docUri, "w")!!
            else {
                val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
                val doc = DocumentsContract.createDocument(resolver, parent, "application/pdf", name)
                resolver.openOutputStream(doc!!, "w")!!
            }
        }
        Jobs.start(a, str(R.string.job_pdf, v.label)) { rep ->
            try {
                if (!local) requireNetwork()
                val lstore = if (local) withContext(Dispatchers.IO) { library.store(v.label) } else null
                PdfExporter(a, st, o, rep, lstore).export(selected, sink)
            } catch (e: Throwable) {
                // the file chosen in the picker was created empty: do not leave it there
                if (docUri != null) runCatching { DocumentsContract.deleteDocument(resolver, docUri) }
                throw e
            }
            str(R.string.msg_pdf_done)
        }
    }

    fun clearCache() {
        File(a.filesDir, "pdfcache").deleteRecursively()
        File(a.cacheDir, "structures").deleteRecursively()
    }

    /** Free space available to the app and the space used by its data. */
    fun freeSpace(): Long = library.freeSpace()
    fun appDataSize(): Long = a.filesDir.dirSize()

    fun cacheSize(): Long = File(a.filesDir, "pdfcache").dirSize()
}
