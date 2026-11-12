package com.tolgalen.mktdoc.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.tolgalen.mktdoc.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** A release of the app published on GitHub. */
data class AppRelease(
    val tag: String,
    val name: String,
    val body: String,
    val published: String,
    val htmlUrl: String,
    val apkUrl: String?,
    val apkSize: Long,
    val sha256: String?,
    val prerelease: Boolean,
) {
    /** `v1.2.0` -> `1.2.0`. */
    val version get() = tag.removePrefix("v")

    fun toJson() = JSONObject().put("tag", tag).put("name", name).put("body", body).put("published", published)
        .put("html", htmlUrl).put("apk", apkUrl ?: JSONObject.NULL).put("size", apkSize).put("sha", sha256 ?: JSONObject.NULL)
        .put("pre", prerelease)

    companion object {
        fun fromJson(o: JSONObject) = AppRelease(
            o.getString("tag"), o.optString("name"), o.optString("body"), o.optString("published"), o.optString("html"),
            o.optString("apk").takeIf { !o.isNull("apk") && it.isNotEmpty() }, o.optLong("size"),
            o.optString("sha").takeIf { !o.isNull("sha") && it.isNotEmpty() }, o.optBoolean("pre"),
        )

        private val shaInBody = Regex("""SHA-?256:?\s*`?([0-9a-fA-F]{64})`?""")

        /** Parses one element of GitHub's "list releases" response; drafts and releases without a tag are skipped. */
        fun fromGitHub(o: JSONObject): AppRelease? {
            if (o.optBoolean("draft")) return null
            val tag = o.optString("tag_name").ifEmpty { return null }
            val body = o.optString("body")
            var apk: JSONObject? = null
            val assets = o.optJSONArray("assets")
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) { apk = a; break }
            }
            val digest = apk?.optString("digest").orEmpty().removePrefix("sha256:").takeIf { it.length == 64 }
            return AppRelease(tag, o.optString("name").ifEmpty { tag }, body, o.optString("published_at"), o.optString("html_url"),
                apk?.optString("browser_download_url")?.takeIf { it.isNotEmpty() }, apk?.optLong("size") ?: 0L,
                (digest ?: shaInBody.find(body)?.groupValues?.get(1))?.lowercase(), o.optBoolean("prerelease"))
        }
    }
}

/** Looks for new versions of the app in the GitHub releases, downloads them, verifies them and starts the installation. */
class Updates(private val ctx: Context) {
    private val cacheFile = File(ctx.filesDir, "releases.json")
    private val dir get() = File(ctx.cacheDir, "updates")

    /** Last list obtained (for the version history while offline). */
    fun cached(): List<AppRelease> = try {
        val a = JSONArray(cacheFile.readText())
        List(a.length()) { AppRelease.fromJson(a.getJSONObject(it)) }
    } catch (_: Exception) { emptyList() }

    /** Releases of the repository, newest first. Throws when offline. */
    suspend fun fetch(): List<AppRelease> = withContext(Dispatchers.IO) {
        val text = Http.get(RELEASES_API, mapOf("Accept" to "application/vnd.github+json")).use { it.body.string() }
        val a = JSONArray(text)
        val list = (0 until a.length()).mapNotNull { AppRelease.fromGitHub(a.getJSONObject(it)) }
            .sortedWith { x, y -> compareVersions(y.version, x.version) }
        writeAtomic(cacheFile, JSONArray().also { j -> list.forEach { j.put(it.toJson()) } }.toString())
        list
    }

    fun apkFile(r: AppRelease) = File(dir, "mktdoc-${r.version}.apk")

    fun isDownloaded(r: AppRelease) = apkFile(r).isFile

    /** Deletes downloaded updates that are not newer than the installed version (already installed or obsolete). */
    fun cleanup(installed: String) {
        dir.listFiles()?.forEach { f ->
            val v = f.name.removePrefix("mktdoc-").removeSuffix(".apk").removeSuffix(".apk.part")
            if (f.name.endsWith(".part") || compareVersions(v, installed) <= 0) f.delete()
        }
    }

    /** Downloads the APK, checks its SHA-256 (when published) and that it is signed like the installed app. */
    suspend fun download(r: AppRelease, progress: (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
        val url = r.apkUrl ?: throw IOException("This release has no APK")
        require(url.startsWith("https://github.com/$REPO/releases/download/")) { "Unexpected download address" }
        dir.mkdirs()
        val dest = apkFile(r)
        dir.listFiles()?.forEach { if (it.name != dest.name) it.delete() }
        val tmp = File(dir, dest.name + ".part")
        val md = MessageDigest.getInstance("SHA-256")
        Http.get(url).use { resp ->
            val total = resp.body.contentLength().takeIf { it > 0 } ?: r.apkSize
            resp.body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); md.update(buf, 0, n)
                        done += n; progress(done, total)
                    }
                }
            }
        }
        val actual = md.digest().joinToString("") { "%02x".format(it) }
        if (r.sha256 != null && !r.sha256.equals(actual, ignoreCase = true)) { tmp.delete(); throw IOException(Msg.t(
            "The download does not match the published SHA-256.", "La descarga no coincide con el SHA-256 publicado.")) }
        if (!sameSigner(tmp)) { tmp.delete(); throw IOException(Msg.t(
            "The update is not signed with the same key as this app, so it cannot replace it.",
            "La actualización no está firmada con la misma clave que esta app, así que no puede sustituirla.")) }
        dest.delete()
        if (!tmp.renameTo(dest)) throw IOException("Could not save the update")
        dest
    }

    /** SHA-256 of each signing certificate. An archive that is not installed has `signingInfo` only on newer Android; `signatures` fills the gap. */
    @Suppress("DEPRECATION")
    private fun certs(info: android.content.pm.PackageInfo?): Set<String> {
        val sigs = info?.signingInfo?.apkContentsSigners ?: info?.signatures ?: return emptySet()
        return sigs.map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    /** True when [apk] is this app's package signed by the same certificate as the installed one. */
    private fun sameSigner(apk: File): Boolean {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION") val flags = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.path, flags)
        val mine = certs(pm.getPackageInfo(ctx.packageName, flags))
        val theirs = certs(archive)
        if (archive == null || archive.packageName != ctx.packageName) return false
        return mine.isNotEmpty() && mine == theirs
    }

    fun canInstall(): Boolean = ctx.packageManager.canRequestPackageInstalls()

    /** The intent that opens the system installer for a downloaded update, or the setting that allows installing from this app. */
    fun installIntent(r: AppRelease): Intent {
        if (!canInstall()) return Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apkFile(r))
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    companion object {
        const val REPO = "tolgalen/mktdoc"
        const val RELEASES_API = "https://api.github.com/repos/$REPO/releases?per_page=30"

        /** Newest stable release that has an APK and is newer than [installed]; null when up to date. */
        fun newer(list: List<AppRelease>, installed: String): AppRelease? =
            list.filter { !it.prerelease && it.apkUrl != null && compareVersions(it.version, installed) > 0 }
                .maxWithOrNull { x, y -> compareVersions(x.version, y.version) }

        /** Release notes as plain text: Markdown marks removed, list items with a bullet, checksum line dropped. */
        fun plainNotes(md: String): String = md.replace("\r", "").lines()
            .filter { !it.trimStart().startsWith("SHA-256", ignoreCase = true) && !it.trimStart().startsWith("sha256", ignoreCase = true) }
            .joinToString("\n") { l ->
                var t = l.trimEnd()
                t = t.replace(Regex("^#{1,6}\\s*"), "").replace(Regex("^\\s*[-*]\\s+"), "• ")
                t.replace("**", "").replace("`", "")
            }.replace(Regex("\n{3,}"), "\n\n").trim()
    }
}
