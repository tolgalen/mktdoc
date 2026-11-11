package com.tolgalen.mktdoc.net

import kotlinx.coroutines.CancellationException
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

class HttpStatus(val code: Int, url: String) : IOException("HTTP $code $url")

/** Single HTTP client: caps requests per second and retries 429/5xx with growing back-off. */
object Http {
    const val BASE = "https://manual.mikrotik.com"
    val HOST: String = URI(BASE).host
    const val UA = "mktdoc/1.0 (copia personal de la documentacion)"

    /** Maximum requests per second across all threads (0 = unlimited). */
    @Volatile var maxRps: Double = 10.0
    private val rateLock = Any()
    private var nextAt = 0L

    private fun throttle() {
        val rps = maxRps
        if (rps <= 0) return
        val wait: Long
        synchronized(rateLock) {
            val now = System.nanoTime()
            wait = nextAt - now
            nextAt = maxOf(now, nextAt) + (1_000_000_000L / rps).toLong()
        }
        if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
    }

    private val retry = Interceptor { chain ->
        var attempt = 0
        while (true) {
            throttle()
            val resp = try {
                chain.proceed(chain.request())
            } catch (e: IOException) {
                if (attempt >= 3 || chain.call().isCanceled()) throw e
                Thread.sleep(1000L shl attempt); attempt++; continue
            }
            if ((resp.code == 429 || resp.code in 500..599) && attempt < 4) {
                val ra = resp.header("Retry-After")?.toLongOrNull()
                resp.close()
                Thread.sleep(((ra ?: (1L shl attempt)).coerceAtMost(30)) * 1000); attempt++; continue
            }
            return@Interceptor resp
        }
        @Suppress("UNREACHABLE_CODE") throw IllegalStateException()
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false)
        .addInterceptor(retry).build()

    /** GET; throws [HttpStatus] when the code is not 2xx (except 304 with `allow304`). The response must be closed. */
    fun get(url: String, headers: Map<String, String> = emptyMap(), allow304: Boolean = false): Response {
        var target = url
        // Redirecciones a mano: la web redirige a http:// (sin barra final) y eso se fuerza a https://
        for (hop in 0..6) {
            val rb = Request.Builder().url(target).header("User-Agent", UA)
            headers.forEach { (k, v) -> rb.header(k, v) }
            val r = client.newCall(rb.build()).execute()
            if (r.code in 301..308 && r.code != 304 && r.code != 305 && r.code != 306) {
                val loc = r.header("Location")
                r.close()
                if (loc != null) {
                    var next = resolve(target, loc) ?: throw HttpStatus(r.code, target)
                    if (next.startsWith("http://$HOST")) next = "https://" + next.removePrefix("http://")
                    target = next
                    continue
                }
            }
            if (!r.isSuccessful && !(allow304 && r.code == 304)) {
                val code = r.code; r.close(); throw HttpStatus(code, url)
            }
            return r
        }
        throw IOException("Too many redirects: $url")
    }

    fun getString(url: String): String = get(url).use { it.body.string() }

    fun getBytes(url: String): ByteArray = get(url).use { it.body.bytes() }

    /** Does the URL exist? (GET without reading the body). Only 404/410 count as missing; other errors throw. */
    fun exists(url: String): Boolean = try {
        get(url).close(); true
    } catch (e: HttpStatus) {
        if (e.code == 404 || e.code == 410) false else throw e
    }

    fun resolve(base: String, ref: String): String? = try {
        URI(base).resolve(ref.trim().replace(" ", "%20")).toString()
    } catch (e: Exception) {
        if (e is CancellationException) throw e else null
    }
}
