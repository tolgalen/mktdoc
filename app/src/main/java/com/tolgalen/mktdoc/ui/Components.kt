package com.tolgalen.mktdoc.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tolgalen.mktdoc.ui.theme.ButtonShape
import java.text.DateFormat
import java.util.Date

@Composable
fun SectionCard(title: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

class Tile(val text: String, val icon: ImageVector, val onClick: () -> Unit, val enabled: Boolean = true,
           val primary: Boolean = false, val danger: Boolean = false)

/** Action buttons in a grid of [columns] columns: icon on top, text below. */
@Composable
fun ActionGrid(tiles: List<Tile>, columns: Int = 2) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { t -> Box(Modifier.weight(1f)) { ActionTile(t) } }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ActionTile(t: Tile) {
    val mod = Modifier.fillMaxWidth().height(104.dp)
    val pad = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
    val content: @Composable RowScope.() -> Unit = {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(t.icon, null, Modifier.size(28.dp))
            Spacer(Modifier.height(8.dp))
            Text(t.text, textAlign = TextAlign.Center, maxLines = 3, style = MaterialTheme.typography.labelLarge)
        }
    }
    when {
        t.primary -> Button(t.onClick, mod, t.enabled, shape = ButtonShape, contentPadding = pad, content = content)
        t.danger -> OutlinedButton(t.onClick, mod, t.enabled, shape = ButtonShape, contentPadding = pad,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error), content = content)
        else -> FilledTonalButton(t.onClick, mod, t.enabled, shape = ButtonShape, contentPadding = pad, content = content)
    }
}

@Composable
fun Chip(text: String, color: Color = MaterialTheme.colorScheme.secondaryContainer, textColor: Color = MaterialTheme.colorScheme.onSecondaryContainer) {
    Surface(color = color, shape = ButtonShape) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 3.dp), color = textColor, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun Banner(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.18f), shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

fun formatDate(ts: Long): String = if (ts <= 0) "—" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ts))

fun formatSize(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}
