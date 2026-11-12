@file:OptIn(ExperimentalMaterial3Api::class)

package com.tolgalen.mktdoc.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tolgalen.mktdoc.R
import com.tolgalen.mktdoc.ui.theme.ButtonShape
import com.tolgalen.mktdoc.data.CHANGELOG
import com.tolgalen.mktdoc.data.DocVersion
import com.tolgalen.mktdoc.data.changelogVersion
import com.tolgalen.mktdoc.data.Node
import com.tolgalen.mktdoc.data.nodeId
import com.tolgalen.mktdoc.pdf.*
import com.tolgalen.mktdoc.work.Jobs

private class Opt<T>(val value: T, val label: Int)

@Composable
private fun <T> OptionGroup(title: String, options: List<Opt<T>>, selected: T, onSelect: (T) -> Unit) {
    Column {
        if (title.isNotEmpty()) Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        options.forEach { o ->
            Row(Modifier.radioRow(selected == o.value) { onSelect(o.value) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected == o.value, null)
                Text(stringResource(o.label), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private class Visible(val node: Node, val depth: Int, val expandable: Boolean)

@Composable
fun PdfScreen(vm: MainViewModel, label: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val online by vm.online.collectAsState()
    val tree by vm.tree.collectAsState()
    val job by Jobs.state.collectAsState()
    val version = if (label == CHANGELOG) changelogVersion() else online.firstOrNull { it.label == label }
        ?: DocVersion(label, if (label == online.firstOrNull { it.current }?.label) "/docs" else "/docs/$label")
    val localVersions by vm.local.collectAsState()
    val hasLocal = localVersions.any { it.label == label }
    val hasOnline = label == CHANGELOG || online.any { it.label == label }
    // source of the pages: the offline copy (default, no network) or the website
    var useLocal by rememberSaveable { mutableStateOf(hasLocal) }
    val fromLocal = if (!hasOnline) true else if (!hasLocal) false else useLocal
    LaunchedEffect(label, fromLocal) { vm.loadTree(version, local = fromLocal) }

    var opts by remember { mutableStateOf(vm.prefs.pdfOptions("es")) }
    var filter by rememberSaveable { mutableStateOf("") }
    val selected = rememberSaveable(saver = listSaver(save = { it.toList() }, restore = { mutableStateListOf(*it.toTypedArray()) })) { mutableStateListOf<String>() }
    val expanded = rememberSaveable(saver = listSaver(save = { it.toList() }, restore = { mutableStateListOf(*it.toTypedArray()) })) { mutableStateListOf<String>() }
    var pending by remember { mutableStateOf<List<Node>>(emptyList()) }

    val st = tree.structure
    val byId = remember(st) { st?.roots?.flatMap { r -> r.walk().toList() }?.associateBy { nodeId(it) } ?: emptyMap() }

    val docLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) { vm.exportPdf(version, pending, opts, uri, null, fromLocal); onBack() }
    }
    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { vm.exportPdf(version, pending, opts, null, uri, fromLocal); onBack() }
    }
    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            opts = opts.copy(coverImage = uri)
        }
    }

    val visible = remember(st, filter, expanded.toList()) {
        val out = ArrayList<Visible>()
        val q = filter.trim().lowercase()
        fun matches(n: Node): Boolean = q.isEmpty() || n.walk().any { it.title.lowercase().contains(q) }
        fun add(n: Node, d: Int) {
            if (!matches(n) || n.pages().none() && n.children.isEmpty()) return
            val id = nodeId(n)
            out.add(Visible(n, d, n.children.isNotEmpty()))
            if (n.children.isNotEmpty() && (q.isNotEmpty() || id in expanded)) n.children.forEach { add(it, d + 1) }
        }
        st?.roots?.forEach { add(it, 0) }
        out
    }
    val selectedNodes = selected.mapNotNull { byId[it] }
    val groups = remember(selectedNodes.size, opts.layout) { PdfExporter.groupsFor(label, opts.layout, selectedNodes).size }

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.pdf_title, label)) }, navigationIcon = {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
        }, actions = {
            IconButton({ vm.loadTree(version, forceOnline = true) }, enabled = !tree.loading && !fromLocal) { Icon(Icons.Default.Refresh, stringResource(R.string.reload_structure)) }
        })
    }, bottomBar = {
        Surface(tonalElevation = 3.dp) {
            Button({
                pending = PdfExporter.normalize(selectedNodes)
                if (groups == 1) docLauncher.launch(PdfExporter.groupsFor(label, opts.layout, pending)[0].fileName) else treeLauncher.launch(null)
            }, shape = ButtonShape, modifier = Modifier.fillMaxWidth().padding(12.dp).navigationBarsPadding(), enabled = selectedNodes.isNotEmpty() && !job.running) {
                Icon(Icons.Default.PictureAsPdf, null); Spacer(Modifier.width(8.dp))
                Text(if (selectedNodes.isEmpty()) stringResource(R.string.pdf_select_something) else stringResource(R.string.pdf_generate, selectedNodes.size, groups))
            }
        }
    }) { pad ->
        LazyColumn(Modifier.padding(pad).contentColumn(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                if (hasLocal && hasOnline) SectionCard(stringResource(R.string.pdf_source)) {
                    OptionGroup("", listOf(Opt(true, R.string.source_local), Opt(false, R.string.source_online)), fromLocal) { useLocal = it; selected.clear() }
                }
            }
            item {
                SectionCard(stringResource(R.string.pdf_options)) {
                    OptionGroup(stringResource(R.string.pdf_layout), listOf(Opt(PdfLayout.SINGLE, R.string.layout_single), Opt(PdfLayout.SECTION, R.string.layout_section)), opts.layout) { opts = opts.copy(layout = it) }
                    OutlinedTextField(opts.coverTitle, { opts = opts.copy(coverTitle = it) }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text(stringResource(R.string.cover_title)) }, placeholder = { Text(stringResource(R.string.optional)) },
                        keyboardOptions = KeyboardOptions.Default)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ imageLauncher.launch(arrayOf("image/*")) }, shape = ButtonShape) { Icon(Icons.Default.Image, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.cover_image)) }
                        if (opts.coverImage != null) {
                            Text(stringResource(R.string.cover_image_set), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            IconButton({ opts = opts.copy(coverImage = null) }) { Icon(Icons.Default.Close, null) }
                        }
                    }
                    OptionGroup(stringResource(R.string.image_quality), listOf(Opt(ImageQuality.BALANCED, R.string.q_balanced), Opt(ImageQuality.SMALL, R.string.q_small), Opt(ImageQuality.ORIGINAL, R.string.q_original)), opts.imageQuality) { opts = opts.copy(imageQuality = it) }
                    OptionGroup(stringResource(R.string.text_size), listOf(Opt(TextSize.XSMALL, R.string.t_xsmall), Opt(TextSize.SMALL, R.string.t_small), Opt(TextSize.NORMAL, R.string.t_normal)), opts.textSize) { opts = opts.copy(textSize = it) }
                    OptionGroup(stringResource(R.string.page_breaks), listOf(Opt(PageBreaks.CONTINUOUS, R.string.b_continuous), Opt(PageBreaks.SECTION, R.string.b_section), Opt(PageBreaks.SUBSECTION, R.string.b_subsection), Opt(PageBreaks.PAGE, R.string.b_page)), opts.breaks) { opts = opts.copy(breaks = it) }
                }
            }
            item {
                Text(stringResource(R.string.pdf_sections), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.filter)) },
                    leadingIcon = { Icon(Icons.Default.Search, null) })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ selected.clear(); st?.roots?.forEach { selected.add(nodeId(it)) } }, shape = ButtonShape) { Text(stringResource(R.string.select_all)) }
                    TextButton({ selected.clear() }, shape = ButtonShape) { Text(stringResource(R.string.select_none)) }
                }
                if (tree.loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.loading_structure), style = MaterialTheme.typography.bodySmall) }
                tree.error?.let { Text(stringResource(R.string.structure_error, it), color = MaterialTheme.colorScheme.error) }
            }
            items(visible, key = { nodeId(it.node) }) { v ->
                val id = nodeId(v.node)
                val inherited = v.node.ancestors().any { nodeId(it) in selected }
                Row(Modifier.fillMaxWidth().padding(start = (v.depth * 16).dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(id in selected || inherited, { c -> if (c) selected.add(id) else selected.remove(id) }, Modifier.semantics { contentDescription = v.node.title }, enabled = !inherited)
                    Text(v.node.title, Modifier.weight(1f).clickable(enabled = v.expandable) { if (id in expanded) expanded.remove(id) else expanded.add(id) },
                        style = MaterialTheme.typography.bodyMedium)
                    if (v.expandable) IconButton({ if (id in expanded) expanded.remove(id) else expanded.add(id) }) {
                        Icon(if (id in expanded || filter.isNotBlank()) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                    }
                }
            }
        }
    }
}
