package com.tolgalen.mktdoc.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tolgalen.mktdoc.R
import com.tolgalen.mktdoc.data.*

/** Choose the language and the engine to translate the open page. */
@Composable
fun TranslateDialog(prefs: Prefs, onDismiss: () -> Unit, onStart: (target: String, engine: TranslateEngine) -> Unit) {
    val target0 by prefs.translateTarget.collectAsState()
    val engine0 by prefs.translateEngine.collectAsState()
    val loc = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val langs = remember(loc) { TranslateLangs.sorted(loc) }
    val onlineReady = prefs.onlineReady()
    var target by remember { mutableStateOf(target0) }
    var engine by remember { mutableStateOf(if (engine0 == TranslateEngine.ONLINE && onlineReady) engine0 else TranslateEngine.OFFLINE) }
    // whether the model of the chosen language is already on the device (the offline engine needs it)
    var modelReady by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(target, engine) { modelReady = if (engine == TranslateEngine.OFFLINE) MlKitTranslator().isDownloaded(target) else true }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.translate_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.translate_to), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                val listState = androidx.compose.foundation.lazy.rememberLazyListState(maxOf(0, langs.indexOf(target) - 1))
                LazyColumn(Modifier.heightIn(max = 220.dp), listState) {
                    items(langs) { code ->
                        Row(Modifier.fillMaxWidth().radioRow(code == target) { target = code }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(code == target, null)
                            Spacer(Modifier.width(12.dp))
                            Text(TranslateLangs.name(code, loc))
                        }
                    }
                }
                Text(stringResource(R.string.translate_engine), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Row(Modifier.fillMaxWidth().radioRow(engine == TranslateEngine.OFFLINE) { engine = TranslateEngine.OFFLINE }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(engine == TranslateEngine.OFFLINE, null)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.translate_offline))
                }
                if (onlineReady) {
                    val host = prefs.translateUrl.value.trim().removePrefix("https://").substringBefore('/')
                    Row(Modifier.fillMaxWidth().radioRow(engine == TranslateEngine.ONLINE) { engine = TranslateEngine.ONLINE }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(engine == TranslateEngine.ONLINE, null)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.translate_online_choice, host))
                    }
                }
                val note = when {
                    engine == TranslateEngine.ONLINE -> stringResource(R.string.translate_online_note, prefs.translateUrl.value.trim().removePrefix("https://").substringBefore('/'))
                    modelReady == false -> stringResource(R.string.translate_model_needed)
                    else -> stringResource(R.string.translate_offline_note)
                }
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton({
                prefs.setTranslateTarget(target); prefs.setTranslateEngine(engine)
                onStart(target, engine)
            }) { Text(stringResource(R.string.translate_start)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } })
}
