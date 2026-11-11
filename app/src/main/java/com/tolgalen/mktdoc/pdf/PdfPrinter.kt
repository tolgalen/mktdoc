package com.tolgalen.mktdoc.pdf

import android.annotation.SuppressLint
import android.content.Context
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PrintCallbacks
import android.print.PrintAttributes
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Prints local HTML files to PDF with the WebView's Chromium engine, without a print dialog. */
class PdfPrinter(context: Context) {
    private val ctx = context.applicationContext

    private val attrs: PrintAttributes = PrintAttributes.Builder()
        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
        .setResolution(PrintAttributes.Resolution("pdf", "pdf", 600, 600))
        .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
        .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
        .build()

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun print(html: File, out: File) = withContext(Dispatchers.Main) {
        withTimeout(15 * 60_000L) {
            val wv = WebView(ctx)
            try {
                wv.settings.apply {
                    javaScriptEnabled = false
                    allowFileAccess = true
                    @Suppress("DEPRECATION") allowUniversalAccessFromFileURLs = true
                    blockNetworkLoads = true
                    loadsImagesAutomatically = true
                }
                val d = ctx.resources.displayMetrics.density
                wv.layout(0, 0, (794 * d).toInt(), (1123 * d).toInt())     // A4 at 96 dpi, in device pixels
                suspendCancellableCoroutine { cont ->
                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) { if (cont.isActive) cont.resume(Unit) }
                    }
                    wv.loadUrl("file://" + html.absolutePath)
                    cont.invokeOnCancellation { wv.stopLoading() }
                }
                delay(400)     // let images and layout finish painting
                writePdf(wv, out)
            } finally {
                wv.destroy()
            }
        }
    }

    private suspend fun writePdf(wv: WebView, out: File) = suspendCancellableCoroutine<Unit> { cont ->
        val adapter = wv.createPrintDocumentAdapter("mktdoc")
        val signal = CancellationSignal()
        cont.invokeOnCancellation { signal.cancel() }
        fun fail(e: Throwable) { try { adapter.onFinish() } catch (_: Exception) {}; if (cont.isActive) cont.resumeWithException(e) }
        adapter.onStart()
        PrintCallbacks.layout(adapter, attrs, signal, object : PrintCallbacks.LayoutListener {
            override fun onFinished() {
                try {
                    out.parentFile?.mkdirs()
                    val pfd = ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
                    PrintCallbacks.write(adapter, pfd, signal, object : PrintCallbacks.WriteListener {
                        override fun onFinished() {
                            try { pfd.close() } catch (_: Exception) {}
                            adapter.onFinish()
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onFailed(error: CharSequence?) {
                            try { pfd.close() } catch (_: Exception) {}
                            fail(java.io.IOException("Error writing the PDF: $error"))
                        }
                    })
                } catch (e: Exception) { fail(e) }
            }

            override fun onFailed(error: CharSequence?) = fail(java.io.IOException("Layout error: $error"))
        })
    }

}
