@file:OptIn(ExperimentalMaterial3Api::class)

package com.tolgalen.mktdoc.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tolgalen.mktdoc.R
import com.tolgalen.mktdoc.data.DocVersion
import com.tolgalen.mktdoc.data.nodeId
import com.tolgalen.mktdoc.ui.theme.ButtonShape
import com.tolgalen.mktdoc.work.Jobs

/** Choose which top-level sections of a version to download (the rest of an existing copy is kept). */
@Composable
fun DownloadScreen(vm: MainViewModel, label: String, onBack: () -> Unit) {
    val online by vm.online.collectAsState()
    val job by Jobs.state.collectAsState()
    val version = online.firstOrNull { it.label == label }
        ?: DocVersion(label, if (label == online.firstOrNull { it.current }?.label) "/docs" else "/docs/$label")
    var roots by remember { mutableStateOf<List<com.tolgalen.mktdoc.data.Node>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(label) {
        try { roots = com.tolgalen.mktdoc.data.Discovery.loadRoots(version) } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error = e.message ?: e.toString()
        }
        loading = false
    }
    val selected = rememberSaveable(saver = listSaver(save = { it.toList() }, restore = { mutableStateListOf(*it.toTypedArray()) })) { mutableStateListOf<String>() }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.download_sections_title, label)) }, navigationIcon = {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
        })
    }, bottomBar = {
        Surface(tonalElevation = 3.dp) {
            Button({ vm.download(version, selected.toSet()); onBack() }, shape = ButtonShape,
                modifier = Modifier.fillMaxWidth().padding(12.dp).navigationBarsPadding(), enabled = selected.isNotEmpty() && !job.running) {
                Icon(Icons.Default.Download, null); Spacer(Modifier.width(8.dp))
                Text(if (selected.isEmpty()) stringResource(R.string.pdf_select_something) else stringResource(R.string.download_n_sections, selected.size))
            }
        }
    }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                Text(stringResource(R.string.download_sections_desc), style = MaterialTheme.typography.bodyMedium)
                if (loading) { LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp)) }
                error?.let { Text(stringResource(R.string.structure_error, it), color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ selected.clear(); roots.forEach { selected.add(nodeId(it)) } }, shape = ButtonShape) { Text(stringResource(R.string.select_all)) }
                    TextButton({ selected.clear() }, shape = ButtonShape) { Text(stringResource(R.string.select_none)) }
                }
            }
            items(roots, key = { nodeId(it) }) { r ->
                val id = nodeId(r)
                Row(Modifier.fillMaxWidth().clickable { if (id in selected) selected.remove(id) else selected.add(id) }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(id in selected, { c -> if (c) selected.add(id) else selected.remove(id) })
                    Text(r.title, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
