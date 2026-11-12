package com.tolgalen.mktdoc.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.tolgalen.mktdoc.R
import com.tolgalen.mktdoc.data.OutlineNode
import com.tolgalen.mktdoc.data.PageRef
import java.io.File

/** Methods the offline pages can call (copy a code block, report the scroll position). Only local pages run in this WebView. */
private class ReaderBridge(private val ctx: Context, private val copiedText: String, private val onScroll: (Float) -> Unit) {
    @JavascriptInterface
    fun copy(text: String) {
        Handler(Looper.getMainLooper()).post {
            val cm = ctx.getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("mktdoc", text))
            // Android 13+ shows its own confirmation
            if (Build.VERSION.SDK_INT < 33) Toast.makeText(ctx, copiedText, Toast.LENGTH_SHORT).show()
        }
    }

    @JavascriptInterface
    fun scroll(fraction: Double) { onScroll(fraction.toFloat()) }
}

/**
 * Offline reader: loads the local HTML site in a WebView.
 * [initialPath] is relative to the site folder and may carry a query (`?hl=words`, words to highlight).
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ReaderScreen(
    label: String, index: File, initialPath: String?, zoom: Int, onZoom: (Int) -> Unit,
    bookmarks: List<PageRef>, onToggleBookmark: (PageRef) -> Unit, onVisit: (PageRef) -> Unit,
    onSearch: () -> Unit, onBack: () -> Unit, outline: List<OutlineNode> = emptyList(),
    scrollOf: (String, String) -> Float? = { _, _ -> null },
    onScroll: (String, String, Float) -> Unit = { _, _, _ -> },
    onLeave: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 840 && outline.isNotEmpty()
    val siteDir = index.parentFile!!
    var current by remember { mutableStateOf<PageRef?>(null) }
    val currentNow = rememberUpdatedState(current)
    var currentUrl by remember { mutableStateOf<String?>(null) }
    val starred = current?.let { c -> bookmarks.any { it.label == c.label && it.path == c.path } } == true
    var menu by remember { mutableStateOf(false) }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val bg = MaterialTheme.colorScheme.background.toArgb()
    var web by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var finding by rememberSaveable { mutableStateOf(false) }
    var findQuery by rememberSaveable { mutableStateOf("") }
    var findState by remember { mutableStateOf(0 to 0) }      // active match (1-based) and total
    val theme = if (dark) "dark" else "light"
    val copiedText = stringResource(R.string.copied)
    BackHandler(enabled = canGoBack || finding) { if (finding) { finding = false; web?.clearMatches() } else web?.goBack() }
    LaunchedEffect(theme, web) { web?.evaluateJavascript("document.documentElement.setAttribute('data-theme','$theme')", null) }
    DisposableEffect(Unit) { onDispose { onLeave() } }

    val findFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(finding) { if (finding) runCatching { findFocus.requestFocus() } }

    fun find(q: String) {
        findQuery = q
        if (q.isBlank()) { web?.clearMatches(); findState = 0 to 0 } else web?.findAllAsync(q)
    }

    Scaffold(topBar = {
        Column {
            TopAppBar(title = { Text(versionTitle(label)) }, navigationIcon = {
                IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            }, actions = {
                IconButton({ current?.let(onToggleBookmark) }, enabled = current != null) {
                    Icon(if (starred) Icons.Default.Star else Icons.Default.StarBorder, stringResource(if (starred) R.string.bookmark_remove else R.string.bookmark))
                }
                IconButton({ finding = !finding; if (!finding) web?.clearMatches() }) { Icon(Icons.Default.FindInPage, stringResource(R.string.find_in_page)) }
                IconButton(onSearch) { Icon(Icons.Default.Search, stringResource(R.string.search)) }
                Box {
                    IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more_options)) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.index)) }, onClick = { menu = false; web?.loadUrl("file://" + index.absolutePath) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.open_on_web)) }, enabled = currentUrl != null, onClick = {
                            menu = false
                            currentUrl?.let { u -> runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.share_link)) }, enabled = currentUrl != null, onClick = {
                            menu = false
                            currentUrl?.let { u ->
                                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                                    .putExtra(Intent.EXTRA_TEXT, (current?.title?.takeIf { it.isNotBlank() }?.let { "$it\n" } ?: "") + u)
                                runCatching { ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            }
                        })
                        HorizontalDivider()
                        Text(stringResource(R.string.reader_font), Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        for ((pct, res) in readerSizes) DropdownMenuItem(
                            text = { Text(stringResource(res) + " · $pct %") },
                            leadingIcon = { if (pct == zoom) Icon(Icons.Default.Check, null) else Spacer(Modifier.size(24.dp)) },
                            onClick = { onZoom(pct); menu = false },
                        )
                    }
                }
            })
            if (finding) Surface(tonalElevation = 2.dp) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    OutlinedTextField(findQuery, { find(it) }, Modifier.weight(1f).focusRequester(findFocus), singleLine = true, placeholder = { Text(stringResource(R.string.find_hint)) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { web?.findNext(true) }))
                    Text(if (findQuery.isBlank()) "" else if (findState.second == 0) stringResource(R.string.find_none) else "${findState.first}/${findState.second}",
                        Modifier.padding(horizontal = 6.dp), style = MaterialTheme.typography.labelMedium)
                    IconButton({ web?.findNext(false) }, enabled = findState.second > 0) { Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.find_prev)) }
                    IconButton({ web?.findNext(true) }, enabled = findState.second > 0) { Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.find_next)) }
                    IconButton({ finding = false; web?.clearMatches() }) { Icon(Icons.Default.Close, stringResource(R.string.find_close)) }
                }
            }
        }
    }) { pad ->
        Row(Modifier.padding(pad).fillMaxSize()) {
        if (wide) {
            OutlinePanel(outline, current?.path, { path -> web?.loadUrl("file://" + File(siteDir, path).absolutePath) }, Modifier.width(320.dp).fillMaxHeight())
            VerticalDivider()
        }
        AndroidView<WebView>(modifier = Modifier.weight(1f).fillMaxHeight(), factory = { c ->
            WebView(c).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = true
                settings.domStorageEnabled = false
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.setSupportZoom(true)
                addJavascriptInterface(ReaderBridge(c, copiedText) { f -> currentNow.value?.let { onScroll(it.label, it.path, f) } }, "MkBridge")
                setFindListener { active, total, _ -> findState = (if (total > 0) active + 1 else 0) to total }
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                        view.evaluateJavascript("window.MK_FORCE='$theme';", null)
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        canGoBack = view.canGoBack()
                        val u = url ?: ""
                        val rel = u.substringBefore('#').substringBefore('?').removePrefix("file://").removePrefix(siteDir.absolutePath).trimStart('/')
                        if (rel.endsWith(".html")) {
                            val ref = PageRef(label, rel, (view.title ?: "").substringBefore(" — "))
                            current = ref; onVisit(ref)
                            // back where the page was left, unless the link points to an anchor or to searched words
                            if ('#' !in u && "hl=" !in u) scrollOf(label, rel)?.let { f ->
                                view.evaluateJavascript("setTimeout(function(){window.scrollTo(0,$f*Math.max(1,document.documentElement.scrollHeight))},150)", null)
                            }
                        }
                        view.evaluateJavascript("document.documentElement.setAttribute('data-theme','$theme')", null)
                        view.evaluateJavascript("(document.querySelector('meta[name=mk-url]')||{}).content||''") { v ->
                            currentUrl = v?.trim('"')?.takeIf { it.startsWith("http") }
                        }
                        if (finding && findQuery.isNotBlank()) view.findAllAsync(findQuery)
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val u = request.url
                        if (u.scheme == "file") return false
                        runCatching { view.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.toString())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        return true
                    }
                }
                web = this
                val rel = initialPath?.substringBefore('?')
                val query = initialPath?.substringAfter('?', "")?.takeIf { it.isNotEmpty() }
                val file = rel?.let { File(siteDir, it).takeIf { f -> f.isFile } }
                loadUrl("file://" + (file ?: index).absolutePath + if (file != null && query != null) "?$query" else "")
            }
        }, update = { it.setBackgroundColor(bg); it.settings.textZoom = zoom }, onRelease = { it.destroy() })
        }
    }
}
