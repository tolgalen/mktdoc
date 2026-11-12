package com.tolgalen.mktdoc.data

import org.json.JSONArray
import org.json.JSONObject

/** Label of the special "version" that holds the documentation changelog (https://manual.mikrotik.com/changelog). */
const val CHANGELOG = "changelog"

fun changelogVersion() = DocVersion(CHANGELOG, "/changelog")

/** Label of the special "version" that holds the hardware guides (https://manual.mikrotik.com/hardware/). */
const val HARDWARE = "hardware"

/** The hardware guides are a separate Docusaurus site with its own sidebar; they are treated like the current version of it. */
fun hardwareVersion() = DocVersion(HARDWARE, "/hardware", current = true)

/** Collections that are not RouterOS versions (changelog, hardware guides). */
fun isDocSet(label: String) = label == CHANGELOG || label == HARDWARE

fun docSetVersion(label: String): DocVersion? = when (label) { CHANGELOG -> changelogVersion(); HARDWARE -> hardwareVersion(); else -> null }

/** Page whose sidebar menu describes the structure of a version (`/docs/7.25/introduction`, `/hardware/`). */
fun entryUrl(v: DocVersion) = com.tolgalen.mktdoc.net.Http.BASE + if (v.label == HARDWARE) v.prefix + "/" else v.prefix + "/introduction"

/** A documentation version (7.26, 7.25...). `prefix` is its path on the website: /docs or /docs/7.25. */
data class DocVersion(val label: String, val prefix: String, val current: Boolean = false) {
    fun toJson() = JSONObject().put("label", label).put("prefix", prefix).put("current", current)

    companion object {
        fun fromJson(o: JSONObject) = DocVersion(o.getString("label"), o.getString("prefix"), o.optBoolean("current"))
    }
}

/** Item of the manual's sidebar menu (section, category or page). */
class Node(var title: String, var href: String? = null, var key: String? = null) {
    val children = mutableListOf<Node>()
    var parent: Node? = null
    var orphan = false       // added from the sitemap (it was not in the menu)
    var category = false     // menu category: its children only show on its own page
    var expanded = false     // its expanded menu has already been read
    var discovered = false   // found by following an internal link (it may not exist)

    fun add(c: Node): Node { c.parent = this; children.add(c); return c }

    fun walk(): Sequence<Node> = sequence {
        yield(this@Node)
        for (c in children.toList()) yieldAll(c.walk())
    }

    fun pages(): Sequence<Node> = walk().filter { it.key != null }

    fun ancestors(): List<Node> {
        val out = ArrayList<Node>()
        var p = parent
        while (p != null) { out.add(p); p = p.parent }
        return out.reversed()
    }

    fun depth() = ancestors().size

    fun toJson(): JSONObject = JSONObject()
        .put("title", title).put("href", href ?: JSONObject.NULL).put("key", key ?: JSONObject.NULL)
        .put("orphan", orphan).put("category", category).put("expanded", expanded).put("discovered", discovered)
        .put("children", JSONArray().also { a -> children.forEach { a.put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): Node {
            val n = Node(o.getString("title"), o.optString("href").takeIf { !o.isNull("href") && it.isNotEmpty() },
                o.optString("key").takeIf { !o.isNull("key") && it.isNotEmpty() })
            n.orphan = o.optBoolean("orphan"); n.category = o.optBoolean("category")
            n.expanded = o.optBoolean("expanded"); n.discovered = o.optBoolean("discovered")
            val ch = o.optJSONArray("children")
            if (ch != null) for (i in 0 until ch.length()) n.add(fromJson(ch.getJSONObject(i)))
            return n
        }
    }
}

/** Stable identifier of a tree node (to remember the selection). */
fun nodeId(n: Node): String = n.key ?: ("t:" + (n.ancestors() + n).joinToString("/") { it.title })

class Structure(val version: DocVersion, val roots: MutableList<Node>, val stylesheets: List<String>) {
    fun allPages(): List<Node> = roots.flatMap { it.pages().toList() }

    fun toJson(): JSONObject = JSONObject()
        .put("version", version.toJson())
        .put("roots", JSONArray().also { a -> roots.forEach { a.put(it.toJson()) } })
        .put("stylesheets", JSONArray(stylesheets))

    companion object {
        fun fromJson(o: JSONObject): Structure {
            val r = o.getJSONArray("roots")
            val css = o.getJSONArray("stylesheets")
            return Structure(DocVersion.fromJson(o.getJSONObject("version")),
                MutableList(r.length()) { Node.fromJson(r.getJSONObject(it)) },
                List(css.length()) { css.getString(it) })
        }
    }
}

/** Stored data of a downloaded page. */
data class PageMeta(
    val key: String,
    val title: String,
    val hash: String,
    val etag: String? = null,
    val lastModified: String? = null,
    val ts: Long = 0L,
    val file: String,
) {
    fun toJson() = JSONObject().put("key", key).put("title", title).put("hash", hash)
        .put("etag", etag ?: JSONObject.NULL).put("lm", lastModified ?: JSONObject.NULL)
        .put("ts", ts).put("file", file)

    companion object {
        fun fromJson(o: JSONObject) = PageMeta(
            o.getString("key"), o.getString("title"), o.getString("hash"),
            o.optString("etag").takeIf { !o.isNull("etag") && it.isNotEmpty() },
            o.optString("lm").takeIf { !o.isNull("lm") && it.isNotEmpty() },
            o.optLong("ts"), o.getString("file"),
        )
    }
}

/** Normalized path: no repeated slashes, no trailing slash. */
fun normPath(path: String): String {
    val p = Regex("/{2,}").replace(path.substringBefore('#').substringBefore('?').ifEmpty { "/" }, "/")
    return p.trimEnd('/').ifEmpty { "/" }
}

fun versionTuple(v: String): List<Int> = v.split('.').map { it.toIntOrNull() ?: 0 }

fun compareVersions(a: String, b: String): Int {
    val x = versionTuple(a); val y = versionTuple(b)
    for (i in 0 until maxOf(x.size, y.size)) {
        val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
        if (c != 0) return c
    }
    return 0
}

fun prettyName(seg: String): String =
    seg.replace('-', ' ').replace('_', ' ').trim().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }.ifEmpty { seg }
