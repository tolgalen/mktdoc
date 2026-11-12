package com.tolgalen.mktdoc.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import com.tolgalen.mktdoc.data.OutlineNode
import com.tolgalen.mktdoc.data.PageRef
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.tolgalen.mktdoc.R
import java.io.File

/** Offline reader: loads the local HTML site in a WebView. */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ReaderScreen(
    label: String, index: File, initialPath: String?, zoom: Int, onZoom: (Int) -> Unit,
    bookmarks: List<PageRef>, onToggleBookmark: (PageRef) -> Unit, onVisit: (PageRef) -> Unit,
    onSearch: () -> Unit, onBack: () -> Unit, outline: List<OutlineNode> = emptyList(),
) {
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 840 && outline.isNotEmpty()
    val siteDir = index.parentFile!!
    var current by remember { mutableStateOf<PageRef?>(null) }
    val starred = current?.let { c -> bookmarks.any { it.label == c.label && it.path == c.path } } == true
    var sizeMenu by remember { mutableStateOf(false) }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val bg = MaterialTheme.colorScheme.background.toArgb()
    var web by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    val theme = if (dark) "dark" else "light"
    BackHandler(enabled = canGoBack) { web?.goBack() }
    LaunchedEffect(theme, web) { web?.evaluateJavascript("document.documentElement.setAttribute('data-theme','$theme')", null) }
    Scaffold(topBar = {
        TopAppBar(title = { Text(versionTitle(label)) }, navigationIcon = {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
        }, actions = {
            Box {
                IconButton({ sizeMenu = true }) { Icon(Icons.Default.TextFields, stringResource(R.string.reader_font)) }
                DropdownMenu(sizeMenu, { sizeMenu = false }) {
                    for ((pct, res) in readerSizes) DropdownMenuItem(
                        text = { Text(stringResource(res) + " · $pct %") },
                        leadingIcon = { if (pct == zoom) Icon(Icons.Default.Check, null) else Spacer(Modifier.size(24.dp)) },
                        onClick = { onZoom(pct); sizeMenu = false },
                    )
                }
            }
            IconButton({ current?.let(onToggleBookmark) }, enabled = current != null) {
                Icon(if (starred) Icons.Default.Star else Icons.Default.StarBorder, stringResource(if (starred) R.string.bookmark_remove else R.string.bookmark))
            }
            IconButton({ web?.loadUrl("file://" + index.absolutePath) }) { Icon(Icons.Default.Home, stringResource(R.string.index)) }
            IconButton(onSearch) { Icon(Icons.Default.Search, stringResource(R.string.search)) }
        })
    }) { pad ->
        Row(Modifier.padding(pad).fillMaxSize()) {
        if (wide) {
            OutlinePanel(outline, current?.path, { path -> web?.loadUrl("file://" + File(siteDir, path).absolutePath) }, Modifier.width(320.dp).fillMaxHeight())
            VerticalDivider()
        }
        AndroidView<WebView>(modifier = Modifier.weight(1f).fillMaxHeight(), factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = true
                settings.domStorageEnabled = false
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.setSupportZoom(true)
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                        view.evaluateJavascript("window.MK_FORCE='$theme';", null)
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        canGoBack = view.canGoBack()
                        val rel = (url ?: "").substringBefore('#').substringBefore('?').removePrefix("file://")
                            .removePrefix(siteDir.absolutePath).trimStart('/')
                        if (rel.endsWith(".html")) {
                            val ref = PageRef(label, rel, (view.title ?: "").substringBefore(" — "))
                            current = ref; onVisit(ref)
                        }
                        view.evaluateJavascript("document.documentElement.setAttribute('data-theme','$theme')", null)
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val u = request.url
                        if (u.scheme == "file") return false
                        runCatching { view.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.toString())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        return true
                    }
                }
                web = this
                loadUrl("file://" + (initialPath?.let { File(siteDir, it).takeIf { f -> f.isFile } } ?: index).absolutePath)
            }
        }, update = { it.setBackgroundColor(bg); it.settings.textZoom = zoom }, onRelease = { it.destroy() })
        }
    }
}
