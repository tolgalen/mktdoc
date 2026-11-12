@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.tolgalen.mktdoc.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.heading
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.LocaleListCompat
import com.tolgalen.mktdoc.BuildConfig
import com.tolgalen.mktdoc.R
import com.tolgalen.mktdoc.ui.theme.ButtonShape
import com.tolgalen.mktdoc.data.*
import com.tolgalen.mktdoc.ui.theme.Palette
import com.tolgalen.mktdoc.ui.theme.paletteSwatch
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.tolgalen.mktdoc.work.Jobs
import com.tolgalen.mktdoc.work.phaseText

/** Visible title of a "version": RouterOS 7.26, or the documentation changelog. */
@Composable
fun versionTitle(label: String): String = when (label) {
    CHANGELOG -> stringResource(R.string.changelog_title)
    HARDWARE -> stringResource(R.string.hardware_title)
    else -> "RouterOS $label"
}

private data class Row(val label: String, val online: DocVersion?, val local: LocalVersion?)

@Composable
fun HomeScreen(vm: MainViewModel, onOpen: (String) -> Unit, onSettings: () -> Unit, onSearch: () -> Unit, onPage: (PageRef) -> Unit) {
    var showBookmarks by rememberSaveable { mutableStateOf(false) }
    val recent by vm.prefs.recent.collectAsState()
    val bookmarks by vm.prefs.bookmarks.collectAsState()
    val online by vm.online.collectAsState()
    val ok by vm.onlineOk.collectAsState()
    val loading by vm.loading.collectAsState()
    val local by vm.local.collectAsState()
    val job by Jobs.state.collectAsState()
    val rows = remember(online, local) {
        val labels = (online.map { it.label } + local.map { it.label }).distinct().filter { !isDocSet(it) }.sortedWith { a, b -> compareVersions(b, a) }
        labels.map { l -> Row(l, online.firstOrNull { it.label == l }, local.firstOrNull { it.label == l }) } +
            Row(CHANGELOG, changelogVersion(), local.firstOrNull { it.label == CHANGELOG }) +
            Row(HARDWARE, hardwareVersion(), local.firstOrNull { it.label == HARDWARE })
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold) }, actions = {
            IconButton({ showBookmarks = true }) {
                BadgedBox(badge = { if (bookmarks.isNotEmpty()) Badge { Text("${bookmarks.size}") } }) {
                    Icon(Icons.Default.Bookmarks, stringResource(R.string.bookmarks))
                }
            }
            IconButton(onSearch, enabled = local.isNotEmpty()) { Icon(Icons.Default.Search, stringResource(R.string.search)) }
            IconButton({ vm.refresh() }, enabled = !loading) { Icon(Icons.Default.Refresh, stringResource(R.string.refresh)) }
            IconButton(onSettings) { Icon(Icons.Default.Settings, stringResource(R.string.settings)) }
        })
    }) { pad ->
        LazyColumn(Modifier.padding(pad).contentColumn().fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (job.running) item { Banner(stringResource(R.string.job_running, job.title), MaterialTheme.colorScheme.primary) }
            if (ok == false) item { Banner(stringResource(R.string.offline_list), Palette.warn) }
            val gone = local.filter { it.removedOnline }
            if (gone.isNotEmpty()) item {
                Banner(stringResource(R.string.banner_gone, gone.joinToString(", ") { it.label }), Palette.bad)
            }
            if (recent.isNotEmpty()) item {
                SectionCard(stringResource(R.string.continue_reading)) {
                    PageRow(recent.first(), Icons.Default.History, onPage)
                }
            }
            if (rows.isEmpty() && !loading) item { Text(stringResource(R.string.no_versions), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(rows, key = { it.label }) { r -> VersionCard(r) { onOpen(r.label) } }
        }
    }
    HomeBookmarks(vm, showBookmarks, { showBookmarks = false }, onPage)
}

@Composable
private fun HomeBookmarks(vm: MainViewModel, show: Boolean, onClose: () -> Unit, onPage: (PageRef) -> Unit) {
    if (show) BookmarksSheet(vm, onClose) { r -> onClose(); onPage(r) }
}

@Composable
fun PageRow(r: PageRef, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: (PageRef) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick(r) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(r.title.ifBlank { r.path }, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(versionTitle(r.label), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun VersionCard(r: Row, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.MenuBook, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(versionTitle(r.label), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (r.online?.current == true && !isDocSet(r.label)) Chip(stringResource(R.string.current))
            }
            val l = r.local
            if (l != null) {
                Text(stringResource(R.string.card_downloaded, formatSize(l.sizeBytes), l.pages), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(stringResource(R.string.offline_ready), Palette.ok.copy(alpha = 0.25f), MaterialTheme.colorScheme.onSurface)
                    if (l.partial) Chip(stringResource(R.string.partial_copy), Palette.warn.copy(alpha = 0.3f), MaterialTheme.colorScheme.onSurface)
                    if (l.removedOnline) Chip(stringResource(R.string.gone_online), Palette.bad.copy(alpha = 0.3f), MaterialTheme.colorScheme.onSurface)
                    if (l.pendingChanges > 0) Chip(stringResource(R.string.pending_changes, l.pendingChanges), Palette.warn.copy(alpha = 0.3f), MaterialTheme.colorScheme.onSurface)
                }
            } else {
                Text(stringResource(R.string.not_downloaded), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun VersionScreen(vm: MainViewModel, label: String, onBack: () -> Unit, onRead: () -> Unit, onSearch: () -> Unit, onSections: () -> Unit, onCompare: (String) -> Unit, onChanges: () -> Unit, onPdf: () -> Unit) {
    val online by vm.online.collectAsState()
    val ok by vm.onlineOk.collectAsState()
    val local by vm.local.collectAsState()
    val job by Jobs.state.collectAsState()
    val ov = docSetVersion(label) ?: online.firstOrNull { it.label == label }
    val lv = local.firstOrNull { it.label == label }
    var confirmDelete by remember { mutableStateOf(false) }
    var pickCompare by remember { mutableStateOf(false) }
    val others = local.filter { it.label != label && !isDocSet(it.label) }
    val zipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) vm.exportZip(label, uri)
    }
    val busy = job.running
    Scaffold(topBar = {
        TopAppBar(title = { Text(versionTitle(label)) }, navigationIcon = {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
        })
    }) { pad ->
        Column(Modifier.padding(pad).contentColumn().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (lv?.removedOnline == true) Banner(stringResource(R.string.version_gone_full, label), Palette.bad)
            if (ok == false) Banner(stringResource(R.string.offline_list), Palette.warn)
            if (lv != null) SectionCard(stringResource(R.string.offline_copy)) {
                Text(stringResource(R.string.info_pages, lv.pages), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.info_size, formatSize(lv.sizeBytes)), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.info_downloaded, formatDate(lv.downloadedAt)), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.info_checked, formatDate(lv.checkedAt)), style = MaterialTheme.typography.bodyMedium)
            }
            val tiles = buildList {
                if (lv != null) {
                    add(Tile(stringResource(R.string.open_offline), Icons.AutoMirrored.Filled.MenuBook, onRead, primary = true))
                    add(Tile(stringResource(R.string.search), Icons.Default.Search, onSearch))
                    if (!isDocSet(label) && others.isNotEmpty()) add(Tile(stringResource(R.string.compare_versions), Icons.Default.CompareArrows, { pickCompare = true }))
                    if (lv.pendingChanges > 0) add(Tile(stringResource(R.string.view_changes, lv.pendingChanges), Icons.Default.Compare, onChanges))
                    add(Tile(stringResource(R.string.check_updates), Icons.Default.Sync, { vm.check(label) }, enabled = !busy && ok != false))
                    add(Tile(stringResource(R.string.export_zip), Icons.Default.FolderZip, { zipLauncher.launch("mktdoc-$label.zip") }, enabled = !busy))
                }
                if (ov != null) add(Tile(stringResource(if (lv == null) R.string.download else R.string.redownload), Icons.Default.Download,
                    { vm.download(ov) }, enabled = !busy && ok == true, primary = lv == null))
                if (ov != null && label != CHANGELOG) add(Tile(stringResource(R.string.download_sections), Icons.Default.Checklist, onSections, enabled = !busy && ok == true))
                if (ov != null || lv != null) add(Tile(stringResource(R.string.export_pdf), Icons.Default.PictureAsPdf, onPdf, enabled = !busy && (ov != null || lv != null)))
                if (lv != null) add(Tile(stringResource(R.string.delete_copy), Icons.Default.Delete, { confirmDelete = true }, enabled = !busy, danger = true))
            }
            ActionGrid(tiles)
            if (ov == null && lv == null) Text(stringResource(R.string.no_versions))
        }
    }
    if (pickCompare) AlertDialog(
        onDismissRequest = { pickCompare = false },
        title = { Text(stringResource(R.string.compare_with)) },
        text = { Column { others.forEach { o -> TextButton({ pickCompare = false; onCompare(o.label) }, shape = ButtonShape) { Text(versionTitle(o.label)) } } } },
        confirmButton = {},
        dismissButton = { TextButton({ pickCompare = false }, shape = ButtonShape) { Text(stringResource(R.string.cancel)) } },
    )
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.delete_copy)) },
        text = { Text(stringResource(R.string.delete_confirm, label)) },
        confirmButton = { TextButton({ confirmDelete = false; vm.delete(label); onBack() }, shape = ButtonShape) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton({ confirmDelete = false }, shape = ButtonShape) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun ChangesScreen(vm: MainViewModel, label: String, onBack: () -> Unit) {
    val report = remember(label) { vm.library.pendingReport(label) }
    var removeGone by remember { mutableStateOf(false) }
    val job by Jobs.state.collectAsState()
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.changes_title, label)) }, navigationIcon = {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
        })
    }, bottomBar = {
        if (report != null) Surface(tonalElevation = 3.dp) {
            Column(Modifier.padding(12.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (report.removed > 0) Row(Modifier.checkRow(removeGone) { removeGone = it }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(removeGone, null)
                    Text(stringResource(R.string.remove_gone, report.removed), style = MaterialTheme.typography.bodyMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ vm.discard(label); onBack() }, shape = ButtonShape, modifier = Modifier.weight(1f), enabled = !job.running) { Text(stringResource(R.string.discard)) }
                    Button({ vm.apply(label, removeGone); onBack() }, shape = ButtonShape, modifier = Modifier.weight(1f), enabled = !job.running) { Text(stringResource(R.string.apply_changes)) }
                }
            }
        }
    }) { pad ->
        if (report == null) {
            Box(Modifier.padding(pad).contentColumn().fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.no_changes_pending)) }
        } else LazyColumn(Modifier.padding(pad).contentColumn(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(stringResource(R.string.msg_changes, report.added, report.modified, report.removed), style = MaterialTheme.typography.titleSmall)
                if (report.failed > 0) Text(stringResource(R.string.check_failed, report.failed), color = Palette.warn, style = MaterialTheme.typography.bodySmall)
            }
            items(report.changes, key = { it.key + it.kind }) { c -> ChangeItem(c) }
        }
    }
}

@Composable
fun ChangeItem(c: PageChange) {
    var open by remember { mutableStateOf(false) }
    val (icon, color, kind) = when (c.kind) {
        ChangeKind.ADDED -> Triple(Icons.Default.AddCircle, Palette.ok, stringResource(R.string.kind_added))
        ChangeKind.MODIFIED -> Triple(Icons.Default.Edit, Palette.warn, stringResource(R.string.kind_modified))
        ChangeKind.REMOVED -> Triple(Icons.Default.RemoveCircle, Palette.bad, stringResource(R.string.kind_removed))
    }
    val expandable = c.kind == ChangeKind.MODIFIED && (c.addedLines.isNotEmpty() || c.removedLines.isNotEmpty())
    SectionCard {
        Row(Modifier.clickable(enabled = expandable) { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(c.title, fontWeight = FontWeight.SemiBold)
                Text("$kind · ${c.key}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expandable) Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
        }
        if (open) SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                c.removedLines.forEach { Text("− $it", color = Palette.bad, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                c.addedLines.forEach { Text("+ $it", color = Palette.ok, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            }
        }
    }
}

/** Progress dialog of the running operation (or its result). */
@Composable
fun JobDialog() {
    val s by Jobs.state.collectAsState()
    val ctx = LocalContext.current
    var hiddenToken by remember { mutableIntStateOf(-1) }
    if (!s.visible || hiddenToken == s.token) return
    AlertDialog(
        onDismissRequest = { if (s.running) hiddenToken = s.token else Jobs.dismiss() },
        title = { Text(s.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    s.running -> {
                        if (s.total > 0) LinearProgressIndicator(progress = { s.done.toFloat() / s.total }, Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(phaseText(ctx, s), style = MaterialTheme.typography.bodyMedium)
                    }
                    s.error != null -> Text(stringResource(R.string.job_failed, s.error!!), Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error)
                    s.cancelled -> Text(stringResource(R.string.job_cancelled), Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
                    else -> Text(s.message ?: stringResource(R.string.job_done), Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
                }
                if (s.log.isNotEmpty()) Text(s.log.takeLast(5).joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                    maxLines = 8, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            if (s.running) TextButton({ hiddenToken = s.token }, shape = ButtonShape) { Text(stringResource(R.string.hide)) }
            else TextButton({ Jobs.dismiss() }, shape = ButtonShape) { Text(stringResource(R.string.close)) }
        },
        dismissButton = { if (s.running) TextButton({ Jobs.cancel() }, shape = ButtonShape) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Full-text search over the downloaded versions (or one of them). */
@Composable
fun SearchScreen(vm: MainViewModel, only: String?, onBack: () -> Unit, onOpen: (PageRef) -> Unit) {
    var q by rememberSaveable { mutableStateOf("") }
    val hits by vm.hits.collectAsState()
    val bookmarks by vm.prefs.bookmarks.collectAsState()
    val recent by vm.prefs.recent.collectAsState()
    LaunchedEffect(q, only) { vm.search(q, only) }
    DisposableEffect(Unit) { onDispose { vm.search("") } }
    Scaffold(topBar = {
        TopAppBar(title = { Text(if (only != null) stringResource(R.string.search_in, versionTitle(only)) else stringResource(R.string.search)) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } })
    }) { pad ->
        LazyColumn(Modifier.padding(pad).contentColumn().fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text(stringResource(R.string.search_hint)) },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = { if (q.isNotEmpty()) IconButton({ q = "" }) { Icon(Icons.Default.Clear, null) } })
            }
            if (q.trim().length < 2) {
                if (bookmarks.isNotEmpty()) {
                    item { Text(stringResource(R.string.bookmarks), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
                    items(bookmarks.filter { only == null || it.label == only }.reversed(), key = { "b" + it.label + it.path }) { PageRow(it, Icons.Default.Star, onOpen) }
                }
                if (recent.isNotEmpty()) {
                    item { Text(stringResource(R.string.recent), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
                    items(recent.filter { only == null || it.label == only }, key = { "r" + it.label + it.path }) { PageRow(it, Icons.Default.History, onOpen) }
                }
            } else if (hits.isEmpty()) {
                item { Text(stringResource(R.string.no_results), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                item { Text(stringResource(R.string.n_results, hits.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(hits, key = { it.label + it.path }) { h ->
                    Column(Modifier.fillMaxWidth().clickable { onOpen(PageRef(h.label, h.path, h.title)) }.padding(vertical = 6.dp)) {
                        Text(h.title, fontWeight = FontWeight.SemiBold)
                        Text((if (only == null) "${versionTitle(h.label)} · " else "") + h.crumbs, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/** Differences between two downloaded versions. */
@Composable
fun CompareScreen(vm: MainViewModel, a: String, b: String, onBack: () -> Unit) {
    val st by vm.compare.collectAsState()
    LaunchedEffect(a, b) { vm.compareVersions(a, b) }
    val shown = if (st.a == a || st.a == b) st else MainViewModel.CompareState()
    val result = shown.result
    var only by rememberSaveable { mutableStateOf("") }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.compare_title, shown.a.ifEmpty { a }, shown.b.ifEmpty { b })) }, navigationIcon = {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
        })
    }) { pad ->
        when {
            shown.error != null -> Box(Modifier.padding(pad).contentColumn().fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.structure_error, shown.error), color = MaterialTheme.colorScheme.error)
            }
            result == null -> Column(Modifier.padding(pad).contentColumn().fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                if (shown.total > 0) LinearProgressIndicator(progress = { shown.done.toFloat() / shown.total }, Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.comparing), Modifier.padding(top = 12.dp))
            }
            else -> {
                val list = result.filter { only.isEmpty() || it.kind.name == only }
                LazyColumn(Modifier.padding(pad).contentColumn(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Text(stringResource(R.string.compare_summary, shown.a, shown.b, result.count { it.kind == ChangeKind.ADDED },
                            result.count { it.kind == ChangeKind.MODIFIED }, result.count { it.kind == ChangeKind.REMOVED }), style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            FilterChip(only.isEmpty(), { only = "" }, { Text(stringResource(R.string.filter_all)) })
                            FilterChip(only == "ADDED", { only = if (only == "ADDED") "" else "ADDED" }, { Text(stringResource(R.string.kind_added)) })
                            FilterChip(only == "MODIFIED", { only = if (only == "MODIFIED") "" else "MODIFIED" }, { Text(stringResource(R.string.kind_modified)) })
                            FilterChip(only == "REMOVED", { only = if (only == "REMOVED") "" else "REMOVED" }, { Text(stringResource(R.string.kind_removed_short)) })
                        }
                        if (result.isEmpty()) Text(stringResource(R.string.compare_none), Modifier.padding(top = 12.dp))
                    }
                    items(list, key = { it.key + it.kind }) { c -> ChangeItem(c) }
                }
            }
        }
    }
}

/** Bottom sheet with all the bookmarks, grouped by version (newest first), with a text filter and removal with undo. */
@Composable
fun BookmarksSheet(vm: MainViewModel, onDismiss: () -> Unit, onOpen: (PageRef) -> Unit) {
    val bookmarks by vm.prefs.bookmarks.collectAsState()
    var q by rememberSaveable { mutableStateOf("") }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val removedMsg = stringResource(R.string.bookmark_removed)
    val undoLabel = stringResource(R.string.undo)
    val shown = remember(bookmarks, q) {
        val t = q.trim().lowercase()
        bookmarks.filter { t.isEmpty() || it.title.lowercase().contains(t) || it.label.lowercase().contains(t) }.reversed()
    }
    val groups = shown.groupBy { it.label }.toSortedMap { x, y -> compareVersions(y, x) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Box(Modifier.fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxWidth().contentColumn(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 72.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item {
                    Text(stringResource(R.string.bookmarks) + " · ${bookmarks.size}", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                    if (bookmarks.size > 4) OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true,
                        placeholder = { Text(stringResource(R.string.bookmarks_filter)) }, leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = { if (q.isNotEmpty()) IconButton({ q = "" }) { Icon(Icons.Default.Clear, null) } })
                }
                if (bookmarks.isEmpty()) item { Text(stringResource(R.string.bookmarks_empty), Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                else if (shown.isEmpty()) item { Text(stringResource(R.string.no_results), Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                groups.forEach { (label, refs) ->
                    item(key = "h$label") {
                        Text(versionTitle(label) + " · ${refs.size}", Modifier.padding(top = 12.dp).semantics { heading() },
                            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    items(refs, key = { it.label + "|" + it.path }) { r ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable { onOpen(r) }.padding(vertical = 6.dp)) {
                                Text(r.title.ifBlank { r.path }, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Text(r.path.removeSuffix(".html"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                            IconButton({
                                val idx = bookmarks.indexOfFirst { it.label == r.label && it.path == r.path }
                                vm.prefs.toggleBookmark(r)
                                scope.launch {
                                    snack.currentSnackbarData?.dismiss()
                                    if (snack.showSnackbar(removedMsg, undoLabel, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed)
                                        vm.prefs.insertBookmark(idx, r)
                                }
                            }) { Icon(Icons.Default.Delete, stringResource(R.string.bookmark_remove)) }
                        }
                        HorizontalDivider()
                    }
                }
            }
            SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(8.dp))
        }
    }
}
