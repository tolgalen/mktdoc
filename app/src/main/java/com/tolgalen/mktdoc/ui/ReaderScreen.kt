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
fun ReaderScreen(label: String, index: File, zoom: Int, onZoom: (Int) -> Unit, onBack: () -> Unit) {
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
            IconButton({ web?.loadUrl("file://" + index.absolutePath) }) { Icon(Icons.Default.Home, stringResource(R.string.index)) }
            IconButton({ web?.loadUrl("file://" + index.absolutePath + "?q=") ; web?.evaluateJavascript("document.getElementById('sq')&&document.getElementById('sq').focus()", null) }) {
                Icon(Icons.Default.Search, stringResource(R.string.search))
            }
        })
    }) { pad ->
        AndroidView<WebView>(modifier = Modifier.padding(pad).fillMaxSize(), factory = { ctx ->
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
                loadUrl("file://" + index.absolutePath)
            }
        }, update = { it.setBackgroundColor(bg); it.settings.textZoom = zoom }, onRelease = { it.destroy() })
    }
}
