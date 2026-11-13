package com.tolgalen.mktdoc

import com.tolgalen.mktdoc.data.OnlineTranslator
import com.tolgalen.mktdoc.data.TranslateException
import com.tolgalen.mktdoc.data.TranslationCache
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class TranslateTest {
    @Test fun translationKeepsTheShapeOfTheOriginal() {
        assertEquals("Información del sistema", com.tolgalen.mktdoc.data.matchShape("System Information", "Información del sistema."))
        assertEquals("Índice", com.tolgalen.mktdoc.data.matchShape("Index", "índice"))
        assertEquals("Hola.", com.tolgalen.mktdoc.data.matchShape("Hello.", "Hola"))
        assertEquals("Hola", com.tolgalen.mktdoc.data.matchShape("Hello", "Hola"))
        assertEquals("ok", com.tolgalen.mktdoc.data.matchShape("ok", " "))
    }

    @Test fun endpointNeedsHttps() {
        assertEquals("https://t.example.org/translate", OnlineTranslator.endpoint("https://t.example.org"))
        assertEquals("https://t.example.org:5000/translate", OnlineTranslator.endpoint(" https://t.example.org:5000/ "))
        assertEquals("https://t.example.org/translate", OnlineTranslator.endpoint("https://t.example.org/translate"))
        assertNull(OnlineTranslator.endpoint("http://t.example.org"))
        assertNull(OnlineTranslator.endpoint("https://"))
        assertNull(OnlineTranslator.endpoint("t.example.org"))
        assertNull(OnlineTranslator.endpoint(""))
    }

    private fun client(code: Int, answer: (JSONObject) -> String, seen: MutableList<JSONObject> = mutableListOf()) = OkHttpClient.Builder().addInterceptor { chain ->
        val b = okio.Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
        val o = JSONObject(b); seen += o
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("x")
            .body(answer(o).toResponseBody("application/json".toMediaType())).build()
    }.build()

    @Test fun sendsTextsAndKeepsOrder() = runBlocking {
        val seen = mutableListOf<JSONObject>()
        val c = client(200, { o -> JSONObject().put("translatedText", org.json.JSONArray(List(o.getJSONArray("q").length()) { "T" + o.getJSONArray("q").getString(it) })).toString() }, seen)
        val out = OnlineTranslator("https://t.example.org", "k", c).translate(listOf("a", "b"), "es")
        assertEquals(listOf("Ta", "Tb"), out)
        assertEquals("en", seen[0].getString("source")); assertEquals("es", seen[0].getString("target")); assertEquals("k", seen[0].getString("api_key"))
    }

    @Test fun longPagesGoInSeveralRequests() = runBlocking {
        val seen = mutableListOf<JSONObject>()
        val c = client(200, { o -> JSONObject().put("translatedText", org.json.JSONArray(List(o.getJSONArray("q").length()) { "x" })).toString() }, seen)
        val out = OnlineTranslator("https://t.example.org", "", c).translate(List(100) { "text $it" }, "de")
        assertEquals(100, out.size); assertTrue(seen.size > 1); assertFalse(seen[0].has("api_key"))
    }

    @Test fun serverErrorIsReported() {
        val c = client(400, { """{"error":"Invalid API key"}""" })
        val e = runCatching { runBlocking { OnlineTranslator("https://t.example.org", "", c).translate(listOf("a"), "es") } }.exceptionOrNull()
        assertTrue(e is TranslateException); assertTrue(e!!.message!!.contains("400") && e.message!!.contains("Invalid API key"))
    }

    @Test fun cacheIsExactToTheText() {
        val dir = Files.createTempDirectory("tr").toFile()
        val c = TranslationCache(dir)
        c.put("offline", "7.26", "a.html", "es", listOf("Hello", "World"), listOf("Hola", "Mundo"))
        assertEquals(listOf("Hola", "Mundo"), c.get("offline", "7.26", "a.html", "es", listOf("Hello", "World")))
        assertNull(c.get("offline", "7.26", "a.html", "es", listOf("Hello", "Changed")))
        assertNull(c.get("online:x", "7.26", "a.html", "es", listOf("Hello", "World")))
        assertTrue(c.size() > 0); c.clear(); assertEquals(0L, c.size()); dir.deleteRecursively()
    }
}
