@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.tolgalen.mktdoc.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.tolgalen.mktdoc.BuildConfig
import com.tolgalen.mktdoc.R
import com.tolgalen.mktdoc.data.*
import com.tolgalen.mktdoc.ui.theme.paletteSwatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reader text sizes (% of normal) and their names. */
val readerSizes = listOf(80 to R.string.size_xsmall, 90 to R.string.size_small, 100 to R.string.size_normal, 115 to R.string.size_large,
    130 to R.string.size_xlarge, 150 to R.string.size_huge, 175 to R.string.size_max)

private fun paletteName(p: ThemePalette) = when (p) {
    ThemePalette.MKTDOC -> R.string.palette_mktdoc
    ThemePalette.MATERIAL -> R.string.palette_material
    ThemePalette.OCEAN -> R.string.palette_ocean
    ThemePalette.FOREST -> R.string.palette_forest
    ThemePalette.VIOLET -> R.string.palette_violet
    ThemePalette.AMBER -> R.string.palette_amber
    ThemePalette.ROSE -> R.string.palette_rose
    ThemePalette.SLATE -> R.string.palette_slate
}

private val rpsOptions = listOf(2, 5, 10, 20)

/** Settings submenus, in list order. */
private enum class SettingsGroup(@StringRes val title: Int, @StringRes val summary: Int, val icon: ImageVector) {
    APPEARANCE(R.string.settings_appearance, R.string.settings_appearance_summary, Icons.Filled.Palette),
    READING(R.string.settings_reading, R.string.settings_reading_summary, Icons.Filled.TextFields),
    NETWORK(R.string.settings_network, R.string.settings_network_summary, Icons.Filled.Speed),
    ALERTS(R.string.notifications, R.string.settings_alerts_summary, Icons.Filled.Notifications),
    ABOUT(R.string.about, R.string.settings_about_summary, Icons.Filled.Info),
    LICENSES(R.string.settings_licenses, R.string.settings_licenses_summary, Icons.Filled.Description),
}

@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    // Open submenu (null = the category list); back returns to the list.
    var group by rememberSaveable { mutableStateOf<SettingsGroup?>(null) }
    BackHandler(enabled = group != null) { group = null }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(group?.title ?: R.string.settings)) }, navigationIcon = {
            IconButton({ if (group != null) group = null else onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
        })
    }) { pad ->
        val scroll = key(group) { rememberScrollState() }
        Column(Modifier.padding(pad).contentColumn().fillMaxSize().verticalScroll(scroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (group) {
                null -> SettingsGroup.entries.forEach { g -> GroupRow(g.icon, stringResource(g.title), stringResource(g.summary)) { group = g } }
                SettingsGroup.APPEARANCE -> AppearanceGroup(vm)
                SettingsGroup.READING -> ReadingGroup(vm)
                SettingsGroup.NETWORK -> NetworkGroup(vm)
                SettingsGroup.ALERTS -> AlertsGroup(vm)
                SettingsGroup.ABOUT -> AboutGroup(vm)
                SettingsGroup.LICENSES -> LicensesContent()
            }
        }
    }
}

@Composable
private fun GroupRow(icon: ImageVector, title: String, summary: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AppearanceGroup(vm: MainViewModel) {
    val theme by vm.prefs.theme.collectAsState()
    val palette by vm.prefs.palette.collectAsState()
    val dynamic by vm.prefs.dynamicColor.collectAsState()
    val currentLang = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-')
    SectionCard(stringResource(R.string.theme)) {
        listOf(ThemeMode.SYSTEM to R.string.theme_system, ThemeMode.DARK to R.string.theme_dark, ThemeMode.NAVY to R.string.theme_navy,
            ThemeMode.BLACK to R.string.theme_black, ThemeMode.LIGHT to R.string.theme_light).forEach { (m, res) ->
            Row(Modifier.radioRow(theme == m) { vm.prefs.setTheme(m) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(theme == m, null); Spacer(Modifier.width(8.dp)); Text(stringResource(res))
            }
        }
    }
    SectionCard(stringResource(R.string.palette)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (pl in ThemePalette.entries) {
                val selected = pl == palette && !(dynamic && android.os.Build.VERSION.SDK_INT >= 31)
                val name = stringResource(paletteName(pl))
                Box(
                    Modifier.size(48.dp).selectable(selected, role = Role.RadioButton) { vm.prefs.setPalette(pl); vm.prefs.setDynamicColor(false) }
                        .semantics { contentDescription = name },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(paletteSwatch(pl))
                            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { if (selected) Icon(Icons.Default.Check, null, Modifier.size(20.dp), tint = Color.White) }
                }
            }
        }
        Text(stringResource(paletteName(palette)), style = MaterialTheme.typography.bodyMedium)
        if (android.os.Build.VERSION.SDK_INT >= 31) Row(Modifier.switchRow(dynamic) { vm.prefs.setDynamicColor(it) }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.dynamic_color))
                Text(stringResource(R.string.dynamic_color_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(dynamic, null)
        }
    }
    SectionCard(stringResource(R.string.language)) {
        listOf("" to R.string.language_system, "en" to R.string.language_english, "es" to R.string.language_spanish,
            "fr" to R.string.language_french, "de" to R.string.language_german, "pt" to R.string.language_portuguese).forEach { (code, res) ->
            Row(Modifier.radioRow(currentLang == code) { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(code)) },
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(currentLang == code, null); Spacer(Modifier.width(8.dp))
                Text(stringResource(res))
            }
        }
    }
}

@Composable
private fun ReadingGroup(vm: MainViewModel) {
    val zoom by vm.prefs.readerZoom.collectAsState()
    SectionCard(stringResource(R.string.reader_font)) {
        Text(stringResource(R.string.reader_font_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for ((pct, res) in readerSizes) {
            Row(Modifier.radioRow(zoom == pct) { vm.prefs.setReaderZoom(pct) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(zoom == pct, null); Spacer(Modifier.width(8.dp))
                Text(stringResource(res) + " · $pct %")
            }
        }
    }
}

@Composable
private fun NetworkGroup(vm: MainViewModel) {
    val rps by vm.prefs.maxRps.collectAsState()
    val wifiOnly by vm.prefs.wifiOnly.collectAsState()
    val local by vm.local.collectAsState()
    var confirm by remember { mutableStateOf<String?>(null) }
    var cache by remember { mutableLongStateOf(vm.cacheSize()) }
    SectionCard(stringResource(R.string.network)) {
        Text(stringResource(R.string.max_rps_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            rpsOptions.forEach { v -> FilterChip(rps == v, { vm.prefs.setMaxRps(v) }, { Text("$v/s") }) }
        }
    }
    SectionCard(stringResource(R.string.wifi_only)) {
        Row(Modifier.switchRow(wifiOnly, vm.prefs::setWifiOnly), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.wifi_only_desc), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Switch(wifiOnly, null)
        }
    }
    SectionCard(stringResource(R.string.storage)) {
        Text(stringResource(R.string.storage_desc, formatSize(vm.appDataSize()), formatSize(vm.freeSpace())), style = MaterialTheme.typography.bodySmall)
        local.forEach { v ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(versionTitle(v.label) + " · " + formatSize(v.sizeBytes), Modifier.weight(1f))
                IconButton({ confirm = v.label }, enabled = !com.tolgalen.mktdoc.work.Jobs.state.value.running) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
            }
        }
    }
    confirm?.let { l ->
        AlertDialog({ confirm = null }, title = { Text(stringResource(R.string.delete_copy)) }, text = { Text(stringResource(R.string.delete_confirm, l)) },
            confirmButton = { TextButton({ vm.delete(l); confirm = null }, shape = com.tolgalen.mktdoc.ui.theme.ButtonShape) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton({ confirm = null }, shape = com.tolgalen.mktdoc.ui.theme.ButtonShape) { Text(stringResource(R.string.cancel)) } })
    }
    SectionCard(stringResource(R.string.cache)) {
        Text(stringResource(R.string.cache_desc, formatSize(cache)), style = MaterialTheme.typography.bodySmall)
        OutlinedButton({ vm.clearCache(); cache = vm.cacheSize() }, shape = com.tolgalen.mktdoc.ui.theme.ButtonShape) { Text(stringResource(R.string.clear_cache)) }
    }
}

@Composable
private fun AlertsGroup(vm: MainViewModel) {
    val periodic by vm.prefs.periodicCheck.collectAsState()
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    SectionCard(stringResource(R.string.notifications)) {
        val onPeriodic = { it: Boolean ->
            vm.prefs.setPeriodic(it)
            if (it && android.os.Build.VERSION.SDK_INT >= 33) notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        Row(Modifier.switchRow(periodic, onPeriodic), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.periodic_check))
                Text(stringResource(R.string.periodic_check_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(periodic, null)
        }
    }
}

private const val CONTACT_EMAIL = "candion@gmail.com"
private const val SOURCE_URL = "https://github.com/tolgalen/mktdoc"

@Composable
private fun ContactRow(icon: ImageVector, label: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun AboutGroup(vm: MainViewModel) {
    var showGpl by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    fun open(intent: Intent) { runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    SectionCard(stringResource(R.string.about)) {
        Text("mktdoc ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.about_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    UpdatesCard(vm)
    ReleaseHistoryCard(vm)
    SectionCard(stringResource(R.string.about_contact)) {
        ContactRow(Icons.Filled.Email, stringResource(R.string.about_email), CONTACT_EMAIL) {
            open(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$CONTACT_EMAIL")))
        }
        ContactRow(Icons.Filled.Code, stringResource(R.string.about_source), SOURCE_URL.removePrefix("https://")) {
            open(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL)))
        }
    }
    SectionCard(stringResource(R.string.license_title)) {
        Text(stringResource(R.string.license_gpl_summary), style = MaterialTheme.typography.bodyMedium)
        Text("© 2026 tolgalen", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.license_gpl_warranty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton({ showGpl = true }, shape = com.tolgalen.mktdoc.ui.theme.ButtonShape) { Text(stringResource(R.string.lic_show_text)) }
    }
    if (showGpl) LicenseTextDialog(Licenses.GPL) { showGpl = false }
}

/** All the third-party components the app includes or uses, with their license; full texts open in a dialog. */
@Composable
private fun LicensesContent() {
    var textAsset by remember { mutableStateOf<String?>(null) }
    Text(stringResource(R.string.lic_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    LicenseGroup.entries.forEach { g ->
        SectionCard(stringResource(if (g == LicenseGroup.LIBRARY) R.string.lic_g_library else R.string.lic_g_service)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Licenses.all.filter { it.group == g }.forEach { e -> LicenseRow(e) { textAsset = it } }
            }
        }
    }
    textAsset?.let { LicenseTextDialog(it) { textAsset = null } }
}

@Composable
private fun LicenseRow(e: LicenseEntry, onText: (String) -> Unit) {
    var open by rememberSaveable(e.name) { mutableStateOf(false) }
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { open = !open }.padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(e.name, style = MaterialTheme.typography.bodyMedium)
                Text(e.license, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(e.what), style = MaterialTheme.typography.bodySmall)
            Text(e.copyright, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton({ runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(e.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                    shape = com.tolgalen.mktdoc.ui.theme.ButtonShape) { Text(stringResource(R.string.lic_open_site)) }
                e.text?.let { t -> TextButton({ onText(t) }, shape = com.tolgalen.mktdoc.ui.theme.ButtonShape) { Text(stringResource(R.string.lic_show_text)) } }
            }
        }
    }
}

@Composable
private fun LicenseTextDialog(asset: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var text by remember(asset) { mutableStateOf<String?>(null) }
    LaunchedEffect(asset) {
        text = withContext(Dispatchers.IO) {
            runCatching { reflow(ctx.assets.open(asset).bufferedReader().use { it.readText() }) }.getOrDefault("")
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        text = {
            SelectionContainer {
                Text(text ?: "…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.verticalScroll(rememberScrollState()))
            }
        },
        confirmButton = { TextButton(onClose, shape = com.tolgalen.mktdoc.ui.theme.ButtonShape) { Text(stringResource(R.string.close)) } },
    )
}

/** License texts come wrapped at a fixed width: the lines of each paragraph are joined so they flow on screen. */
private fun reflow(raw: String): String = raw.replace("\r", "").split(Regex("\n[ \\t]*\n")).joinToString("\n\n") { para ->
    para.lines().joinToString(" ") { it.trim() }.trim()
}.trim()

/** Installed version, check for a newer release on GitHub, download it (verified) and install it with the system installer. */
@Composable
private fun UpdatesCard(vm: MainViewModel) {
    val ctx = LocalContext.current
    val state by vm.appUpdates.collectAsState()
    val job by com.tolgalen.mktdoc.work.Jobs.state.collectAsState()
    LaunchedEffect(Unit) { vm.checkAppUpdates() }
    val newer = com.tolgalen.mktdoc.data.Updates.newer(state.releases, BuildConfig.VERSION_NAME)
    val downloaded = remember(newer, job.running) { newer != null && vm.isUpdateDownloaded(newer) }
    SectionCard(stringResource(R.string.updates)) {
        Text(stringResource(R.string.app_installed_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium)
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        when {
            newer != null -> {
                Text(stringResource(R.string.update_available, newer.version), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                if (downloaded) {
                    Text(stringResource(R.string.update_downloaded), style = MaterialTheme.typography.bodySmall)
                    if (!vm.canInstallUpdates()) Text(stringResource(R.string.update_allow_install), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button({ runCatching { ctx.startActivity(vm.installIntent(newer).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                        shape = com.tolgalen.mktdoc.ui.theme.ButtonShape) { Text(stringResource(R.string.update_install, newer.version)) }
                } else Button({ vm.downloadUpdate(newer) }, enabled = !job.running, shape = com.tolgalen.mktdoc.ui.theme.ButtonShape) {
                    Text(stringResource(R.string.update_download, formatSize(newer.apkSize)))
                }
            }
            state.error != null && state.releases.isEmpty() -> Text(stringResource(R.string.update_check_failed, state.error!!), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
            state.releases.isNotEmpty() && !state.loading -> Text(
                if (state.error != null) stringResource(R.string.update_check_failed, state.error!!) else stringResource(R.string.up_to_date),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton({ vm.checkAppUpdates(force = true) }, enabled = !state.loading, shape = com.tolgalen.mktdoc.ui.theme.ButtonShape) {
            Text(stringResource(R.string.check_app_updates))
        }
    }
}

/** Every published release with its notes (the newest one open), marking the installed one. */
@Composable
private fun ReleaseHistoryCard(vm: MainViewModel) {
    val ctx = LocalContext.current
    val state by vm.appUpdates.collectAsState()
    if (state.releases.isEmpty()) return
    SectionCard(stringResource(R.string.release_history)) {
        state.releases.forEachIndexed { i, r ->
            var open by rememberSaveable(r.tag) { mutableStateOf(i == 0) }
            val installed = r.version == BuildConfig.VERSION_NAME
            Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { open = !open }.padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(r.tag + if (r.published.length >= 10) " · ${r.published.take(10)}" else "", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    if (installed) Chip(stringResource(R.string.release_installed))
                    Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
                }
                if (open) {
                    Text(com.tolgalen.mktdoc.data.Updates.plainNotes(r.body), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall)
                    if (r.htmlUrl.isNotEmpty()) TextButton({ runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.htmlUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                        shape = com.tolgalen.mktdoc.ui.theme.ButtonShape) { Text(stringResource(R.string.release_open_github)) }
                }
            }
            if (i < state.releases.lastIndex) HorizontalDivider()
        }
    }
}
