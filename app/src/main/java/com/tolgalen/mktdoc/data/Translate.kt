package com.tolgalen.mktdoc.data

import android.content.Context
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Where the text of a page is translated. */
enum class TranslateEngine { OFFLINE, ONLINE }

/** The documentation is written in English; that is always the source language. */
const val SOURCE_LANG = "en"

/** Gives the translation the shape of the original: same first-letter case and same final punctuation (machine translation adds or drops them). */
fun matchShape(orig: String, tr: String): String {
    var t = tr.trim()
    if (t.isEmpty()) return orig
    val first = orig.firstOrNull { it.isLetter() }
    val tf = t.indexOfFirst { it.isLetter() }
    if (first != null && tf >= 0) {
        if (first.isUpperCase() && !t[tf].isUpperCase()) t = t.substring(0, tf) + t[tf].uppercaseChar() + t.substring(tf + 1)
        if (first.isLowerCase() && t[tf].isUpperCase() && t.length > tf + 1 && !t[tf + 1].isUpperCase()) t = t.substring(0, tf) + t[tf].lowercaseChar() + t.substring(tf + 1)
    }
    val enders = ".。!?…:;"
    val o = orig.trimEnd().lastOrNull()
    if (o != null && o !in enders && t.last() in ".。") t = t.dropLast(1)
    else if (o == '.' && t.last() !in enders) t += "."
    return t
}

/** Thrown with a message that can be shown to the user. */
class TranslateException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface PageTranslator {
    /** Translates [texts] (English) into [target]; the result has the same size and order. */
    suspend fun translate(texts: List<String>, target: String, onProgress: (Int) -> Unit = {}): List<String>
}

object TranslateLangs {
    /** Languages the on-device models can translate into (English is the source, so it is left out). */
    val all: List<String> by lazy { TranslateLanguage.getAllLanguages().filter { it != SOURCE_LANG } }

    /** [all] sorted by their name in [loc]. */
    fun sorted(loc: Locale): List<String> = all.sortedWith(compareBy(java.text.Collator.getInstance(loc)) { name(it, loc) })

    /** Name of the language in [loc] (the language of the app). */
    fun name(code: String, loc: Locale = Locale.getDefault()): String =
        Locale.forLanguageTag(code).getDisplayLanguage(loc).replaceFirstChar { it.titlecase(loc) }.ifBlank { code }

    /** Default target: the language of the device, or Spanish if that is English or unsupported. */
    fun default(): String = Locale.getDefault().language.takeIf { it != SOURCE_LANG && it in all } ?: "es"
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
    addOnSuccessListener { c.resume(it) }
    addOnFailureListener { c.resumeWithException(it) }
}

/** Translation with the ML Kit models downloaded to the device. After the download, no text leaves the phone. */
class MlKitTranslator(private val wifiOnly: () -> Boolean = { false }) : PageTranslator {
    private val manager get() = RemoteModelManager.getInstance()

    suspend fun isDownloaded(lang: String): Boolean = runCatching {
        manager.isModelDownloaded(TranslateRemoteModel.Builder(lang).build()).await() &&
            manager.isModelDownloaded(TranslateRemoteModel.Builder(SOURCE_LANG).build()).await()
    }.getOrDefault(false)

    suspend fun downloaded(): List<String> = runCatching {
        manager.getDownloadedModels(TranslateRemoteModel::class.java).await().map { it.language }
    }.getOrDefault(emptyList())

    suspend fun deleteAll() {
        for (code in downloaded()) runCatching { manager.deleteDownloadedModel(TranslateRemoteModel.Builder(code).build()).await() }
    }

    override suspend fun translate(texts: List<String>, target: String, onProgress: (Int) -> Unit): List<String> {
        val t = TranslatorOptions.Builder().setSourceLanguage(SOURCE_LANG).setTargetLanguage(target).build()
            .let { com.google.mlkit.nl.translate.Translation.getClient(it) }
        try {
            val cond = DownloadConditions.Builder().apply { if (wifiOnly()) requireWifi() }.build()
            try { t.downloadModelIfNeeded(cond).await() } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                throw TranslateException("model", e)
            }
            val out = ArrayList<String>(texts.size)
            for ((i, s) in texts.withIndex()) {
                out += try { t.translate(s).await() } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    s    // a text the model cannot handle stays in English
                }
                if (i % 10 == 0) onProgress(i)
            }
            return out
        } finally { t.close() }
    }
}

/**
 * Translation with a server compatible with the LibreTranslate API (`POST {url}/translate`).
 * The text of the page is sent to [baseUrl]; the user chooses that server and has to allow it in the settings.
 */
class OnlineTranslator(private val baseUrl: String, private val apiKey: String, private val client: OkHttpClient = defaultClient) : PageTranslator {
    override suspend fun translate(texts: List<String>, target: String, onProgress: (Int) -> Unit): List<String> = withContext(Dispatchers.IO) {
        val url = endpoint(baseUrl) ?: throw TranslateException("url")
        val out = ArrayList<String>(texts.size)
        var i = 0
        while (i < texts.size) {
            // batches of limited size: servers refuse long requests
            var j = i; var chars = 0
            while (j < texts.size && (j == i || (chars + texts[j].length <= 2500 && j - i < 40))) { chars += texts[j].length; j++ }
            val body = JSONObject().put("q", JSONArray(texts.subList(i, j))).put("source", SOURCE_LANG).put("target", target).put("format", "text")
                .apply { if (apiKey.isNotBlank()) put("api_key", apiKey) }.toString()
            val req = Request.Builder().url(url).header("User-Agent", "mktdoc")
                .post(body.toRequestBody("application/json".toMediaType())).build()
            client.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw TranslateException("http ${r.code}" + serverError(text).let { if (it.isEmpty()) "" else ": $it" })
                val t = JSONObject(text).opt("translatedText")
                val list = when (t) { is JSONArray -> List(t.length()) { t.getString(it) }; is String -> listOf(t); else -> emptyList() }
                if (list.size != j - i) throw TranslateException("answer")
                out += list
            }
            i = j; onProgress(i)
        }
        out
    }

    private fun serverError(body: String) = runCatching { JSONObject(body).optString("error") }.getOrDefault("").take(200)

    companion object {
        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
        }

        /** `https://host[:port][/path]` → the `/translate` endpoint; null when the address is not a usable https address. */
        fun endpoint(base: String): String? {
            val u = base.trim().trimEnd('/')
            if (!u.startsWith("https://", ignoreCase = true) || u.length <= 8 || ' ' in u) return null
            val host = u.substring(8).substringBefore('/').substringAfterLast('@')
            if (host.isBlank()) return null
            return if (u.endsWith("/translate")) u else "$u/translate"
        }
    }
}

/** Translations already made, so a page is not translated twice. Stored in the cache folder: the system may clear it. */
class TranslationCache(private val dir: File) {
    private fun file(engine: String, label: String, path: String, target: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest("$engine|$label|$path|$target".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(dir, "$h.json")
    }

    private fun digest(texts: List<String>) = MessageDigest.getInstance("SHA-1").digest(texts.joinToString("\u0000").toByteArray())
        .joinToString("") { "%02x".format(it) }

    /** The saved translation of exactly these [texts], or null when there is none (or the page has changed since). */
    fun get(engine: String, label: String, path: String, target: String, texts: List<String>): List<String>? = runCatching {
        val o = JSONObject(file(engine, label, path, target).readText())
        if (o.getString("h") != digest(texts)) return null
        val a = o.getJSONArray("t")
        List(a.length()) { a.getString(it) }.takeIf { it.size == texts.size }
    }.getOrNull()

    fun put(engine: String, label: String, path: String, target: String, texts: List<String>, out: List<String>) {
        runCatching {
            dir.mkdirs()
            file(engine, label, path, target).writeText(JSONObject().put("h", digest(texts)).put("t", JSONArray(out)).toString())
        }
    }

    fun size(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L
    fun clear() { dir.listFiles()?.forEach { it.delete() } }
}

/** JavaScript the reader runs in the page to take its text out and to put the translation (or the original) back. */
object TranslateJs {
    /** Collects the translatable text nodes (not code, commands or buttons) and returns their texts as a JSON string. */
    const val COLLECT = """(function(){
if(window.__mkN){__mkRestore()}
var skip='pre,code,kbd,samp,tt,script,style,button,textarea,select,.mk-copy,[translate=no]';
var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null),n,nodes=[],texts=[],orig=[];
while(n=w.nextNode()){var p=n.parentElement;if(!p||p.closest(skip))continue;
var t=n.nodeValue.trim();if(t.length<2||!/[A-Za-z]/.test(t))continue;
if(!/\s/.test(t)&&/[\/=_\\:.0-9]/.test(t))continue;
nodes.push(n);texts.push(t);orig.push(n.nodeValue)}
window.__mkN=nodes;window.__mkO=orig;
window.__mkSet=function(from,arr){for(var i=0;i<arr.length;i++){var n=window.__mkN[from+i],o=window.__mkO[from+i];if(!n)continue;
var m=/^(\s*)[\s\S]*?(\s*)${'$'}/.exec(o);n.nodeValue=m[1]+arr[i]+m[2]}};
window.__mkRestore=function(){if(!window.__mkN)return;for(var i=0;i<window.__mkN.length;i++)window.__mkN[i].nodeValue=window.__mkO[i];window.__mkN=null;window.__mkO=null};
return JSON.stringify(texts)})()"""

    const val RESTORE = "if(window.__mkRestore){__mkRestore()}"
}
