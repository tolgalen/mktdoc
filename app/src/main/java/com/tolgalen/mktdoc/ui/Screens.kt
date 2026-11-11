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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
fun versionTitle(label: String): String = if (label == CHANGELOG) stringResource(R.string.changelog_title) else "RouterOS $label"

private data class Row(val label: String, val online: DocVersion?, val local: LocalVersion?)

@Composable
fun HomeScreen(vm: MainViewModel, onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val online by vm.online.collectAsState()
    val ok by vm.onlineOk.collectAsState()
    val loading by vm.loading.collectAsState()
    val local by vm.local.collectAsState()
    val job by Jobs.state.collectAsState()
    val rows = remember(online, local) {
        val labels = (online.map { it.label } + local.map { it.label }).distinct().filter { it != CHANGELOG }.sortedWith { a, b -> compareVersions(b, a) }
        labels.map { l -> Row(l, online.firstOrNull { it.label == l }, local.firstOrNull { it.label == l }) } +
            Row(CHANGELOG, changelogVersion(), local.firstOrNull { it.label == CHANGELOG })
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold) }, actions = {
            IconButton({ vm.refresh() }, enabled = !loading) { Icon(Icons.Default.Refresh, stringResource(R.string.refresh)) }
            IconButton(onSettings) { Icon(Icons.Default.Settings, stringResource(R.string.settings)) }
        })
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (job.running) item { Banner(stringResource(R.string.job_running, job.title), MaterialTheme.colorScheme.primary) }
            if (ok == false) item { Banner(stringResource(R.string.offline_list), Palette.warn) }
            val gone = local.filter { it.removedOnline }
            if (gone.isNotEmpty()) item {
                Banner(stringResource(R.string.banner_gone, gone.joinToString(", ") { it.label }), Palette.bad)
            }
            if (rows.isEmpty() && !loading) item { Text(stringResource(R.string.no_versions), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(rows, key = { it.label }) { r -> VersionCard(r) { onOpen(r.label) } }
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
                if (r.online?.current == true && r.label != CHANGELOG) Chip(stringResource(R.string.current))
            }
            val l = r.local
            if (l != null) {
                Text(stringResource(R.string.card_downloaded, formatSize(l.sizeBytes), l.pages), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(stringResource(R.string.offline_ready), Palette.ok.copy(alpha = 0.25f), MaterialTheme.colorScheme.onSurface)
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
fun VersionScreen(vm: MainViewModel, label: String, onBack: () -> Unit, onRead: () -> Unit, onChanges: () -> Unit, onPdf: () -> Unit) {
    val online by vm.online.collectAsState()
    val ok by vm.onlineOk.collectAsState()
    val local by vm.local.collectAsState()
    val job by Jobs.state.collectAsState()
    val ov = if (label == CHANGELOG) changelogVersion() else online.firstOrNull { it.label == label }
    val lv = local.firstOrNull { it.label == label }
    var confirmDelete by remember { mutableStateOf(false) }
    val zipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) vm.exportZip(label, uri)
    }
    val busy = job.running
    Scaffold(topBar = {
        TopAppBar(title = { Text(versionTitle(label)) }, navigationIcon = {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
        })
    }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    if (lv.pendingChanges > 0) add(Tile(stringResource(R.string.view_changes, lv.pendingChanges), Icons.Default.Compare, onChanges))
                    add(Tile(stringResource(R.string.check_updates), Icons.Default.Sync, { vm.check(label) }, enabled = !busy && ok != false))
                    add(Tile(stringResource(R.string.export_zip), Icons.Default.FolderZip, { zipLauncher.launch("mktdoc-$label.zip") }, enabled = !busy))
                }
                if (ov != null) add(Tile(stringResource(if (lv == null) R.string.download else R.string.redownload), Icons.Default.Download,
                    { vm.download(ov) }, enabled = !busy && ok == true, primary = lv == null))
                if (ov != null || lv != null) add(Tile(stringResource(R.string.export_pdf), Icons.Default.PictureAsPdf, onPdf, enabled = !busy && ov != null))
                if (lv != null) add(Tile(stringResource(R.string.delete_copy), Icons.Default.Delete, { confirmDelete = true }, enabled = !busy, danger = true))
            }
            ActionGrid(tiles)
            if (ov == null && lv == null) Text(stringResource(R.string.no_versions))
        }
    }
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
                if (report.removed > 0) Row(Modifier.clickable { removeGone = !removeGone }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(removeGone, { removeGone = it })
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
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.no_changes_pending)) }
        } else LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(stringResource(R.string.msg_changes, report.added, report.modified, report.removed), style = MaterialTheme.typography.titleSmall)
                if (report.failed > 0) Text(stringResource(R.string.check_failed, report.failed), color = Palette.warn, style = MaterialTheme.typography.bodySmall)
            }
            items(report.changes, key = { it.key + it.kind }) { c -> ChangeItem(c) }
        }
    }
}

@Composable
private fun ChangeItem(c: PageChange) {
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
                    s.error != null -> Text(stringResource(R.string.job_failed, s.error!!), color = MaterialTheme.colorScheme.error)
                    s.cancelled -> Text(stringResource(R.string.job_cancelled))
                    else -> Text(s.message ?: stringResource(R.string.job_done))
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
