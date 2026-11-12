package com.tolgalen.mktdoc

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tolgalen.mktdoc.ui.*
import com.tolgalen.mktdoc.ui.theme.MktdocTheme

class MainActivity : AppCompatActivity() {
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            val vm: MainViewModel = viewModel()
            val theme by vm.prefs.theme.collectAsState()
            val palette by vm.prefs.palette.collectAsState()
            val dynamic by vm.prefs.dynamicColor.collectAsState()
            MktdocTheme(theme, palette, dynamic) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    App(vm)
                    JobDialog()
                }
            }
        }
    }
}

/** Simple navigation stack: "home", "version:7.25", "changes:7.25", "pdf:7.25", "reader:7.25", "settings". */
@Composable
private fun App(vm: MainViewModel) {
    val stack = rememberSaveable(saver = listSaver(save = { it.toList() }, restore = { mutableStateListOf(*it.toTypedArray()) })) {
        mutableStateListOf("home")
    }
    val route = stack.last()
    BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.size - 1) }
    fun push(r: String) = stack.add(r)
    fun pop() { if (stack.size > 1) stack.removeAt(stack.size - 1) }
    val kind = route.substringBefore(':')
    val arg = route.substringAfter(':', "")
    when (kind) {
        "home" -> HomeScreen(vm, onOpen = { push("version:$it") }, onSettings = { push("settings") }, onSearch = { push("search:") },
            onPage = { r -> push("reader:${r.label}|${r.path}") }, onAllBookmarks = { push("bookmarks") })
        "version" -> VersionScreen(vm, arg, onBack = ::pop, onRead = { push("reader:$arg") }, onSearch = { push("search:$arg") }, onSections = { push("download:$arg") }, onCompare = { o -> push("compare:$arg|$o") }, onChanges = { push("changes:$arg") }, onPdf = { push("pdf:$arg") })
        "compare" -> CompareScreen(vm, arg.substringBefore('|'), arg.substringAfter('|'), onBack = ::pop)
        "bookmarks" -> BookmarksScreen(vm, onBack = ::pop, onOpen = { r -> push("reader:${r.label}|${r.path}") })
        "download" -> DownloadScreen(vm, arg, onBack = ::pop)
        "changes" -> ChangesScreen(vm, arg, onBack = ::pop)
        "pdf" -> PdfScreen(vm, arg, onBack = ::pop)
        "reader" -> {
            val zoom by vm.prefs.readerZoom.collectAsState()
            val bookmarks by vm.prefs.bookmarks.collectAsState()
            val label = arg.substringBefore('|')
            val outline by produceState<List<com.tolgalen.mktdoc.data.OutlineNode>>(emptyList(), label) { value = vm.library.outline(label) }
            ReaderScreen(label, vm.library.indexFile(label), arg.substringAfter('|', "").ifEmpty { null }, zoom, vm.prefs::setReaderZoom,
                bookmarks, vm.prefs::toggleBookmark, vm.prefs::pushRecent, onSearch = { push("search:$label") }, onBack = ::pop, outline = outline)
        }
        "search" -> SearchScreen(vm, only = arg.ifEmpty { null }, onBack = ::pop, onOpen = { r -> push("reader:${r.label}|${r.path}") })
        "settings" -> SettingsScreen(vm, onBack = ::pop)
    }
}
