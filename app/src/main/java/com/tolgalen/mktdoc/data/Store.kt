package com.tolgalen.mktdoc.data

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

fun sha1(s: String): String = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

fun writeAtomic(f: File, text: String) = writeAtomic(f, text.toByteArray(Charsets.UTF_8))

fun writeAtomic(f: File, bytes: ByteArray) {
    f.parentFile?.mkdirs()
    val tmp = File(f.parentFile, f.name + ".${Thread.currentThread().id}.tmp")
    tmp.writeBytes(bytes)
    if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
}

fun File.dirSize(): Long = if (isFile) length() else (listFiles()?.sumOf { it.dirSize() } ?: 0L)

/**
 * Folder with the downloaded pages of a version: `raw/` (clean HTML of each page), `manifest.json`,
 * `structure.json` and the resources (`assets/`: CSS and images), which may live in another folder.
 */
class Store(val root: File, val assets: File) {
    val rawDir = File(root, "raw")
    private val manifestFile = File(root, "manifest.json")
    val structureFile = File(root, "structure.json")
    val manifest = LinkedHashMap<String, PageMeta>()

    fun load(): Store {
        manifest.clear()
        try {
            if (manifestFile.isFile) {
                val o = JSONObject(manifestFile.readText())
                for (k in o.keys()) manifest[k] = PageMeta.fromJson(o.getJSONObject(k))
            }
        } catch (_: Exception) { manifest.clear() }
        return this
    }

    fun save() {
        val o = JSONObject()
        synchronized(manifest) { manifest.forEach { (k, v) -> o.put(k, v.toJson()) } }
        writeAtomic(manifestFile, o.toString())
    }

    fun rawFile(name: String) = File(rawDir, name)
    fun menuFile(meta: PageMeta) = File(rawDir, meta.file.removeSuffix(".html") + ".menu.html")
    fun readRaw(meta: PageMeta): String = rawFile(meta.file).readText()
    fun fileNameFor(key: String) = sha1(key).take(20) + ".html"

    fun loadStructure(): Structure? = try {
        if (structureFile.isFile) Structure.fromJson(JSONObject(structureFile.readText())) else null
    } catch (_: Exception) { null }

    fun saveStructure(st: Structure) = writeAtomic(structureFile, st.toJson().toString())
}
