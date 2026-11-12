package com.tolgalen.mktdoc.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tolgalen.mktdoc.data.OutlineNode

private class OutRow(val node: OutlineNode, val depth: Int, val id: String)

/** Ids (index paths) of the sections that contain the page [path], outermost first; null if it is not in the outline. */
private fun chainTo(nodes: List<OutlineNode>, path: String, parent: String = ""): List<String>? {
    nodes.forEachIndexed { i, n ->
        val id = "$parent/$i"
        if (n.path == path) return listOf(id)
        chainTo(n.children, path, id)?.let { return listOf(id) + it }
    }
    return null
}

/** Side panel with the table of contents of the offline copy (used on wide screens). */
@Composable
fun OutlinePanel(outline: List<OutlineNode>, currentPath: String?, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val expanded = rememberSaveable(saver = listSaver(save = { it.toList() }, restore = { mutableStateListOf(*it.toTypedArray()) })) { mutableStateListOf<String>() }
    LaunchedEffect(currentPath, outline) {
        val chain = currentPath?.let { chainTo(outline, it) } ?: return@LaunchedEffect
        chain.dropLast(1).forEach { if (it !in expanded) expanded.add(it) }
    }
    val rows = remember(outline, expanded.toList()) {
        val out = ArrayList<OutRow>()
        fun add(n: OutlineNode, depth: Int, id: String) {
            out.add(OutRow(n, depth, id))
            if (n.children.isNotEmpty() && id in expanded) n.children.forEachIndexed { i, c -> add(c, depth + 1, "$id/$i") }
        }
        outline.forEachIndexed { i, n -> add(n, 0, "/$i") }
        out
    }
    LazyColumn(modifier, contentPadding = PaddingValues(vertical = 8.dp)) {
        items(rows, key = { it.id }) { r ->
            val selected = r.node.path != null && r.node.path == currentPath
            val open = r.id in expanded
            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp)
                    .clickable {
                        r.node.path?.let(onOpen)
                        if (r.node.children.isNotEmpty() && (r.node.path == null || !open)) { if (open) expanded.remove(r.id) else expanded.add(r.id) }
                    }
                    .padding(start = (12 + r.depth * 14).dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(r.node.title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                if (r.node.children.isNotEmpty()) IconButton({ if (open) expanded.remove(r.id) else expanded.add(r.id) }) {
                    Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                }
            }
        }
    }
}
