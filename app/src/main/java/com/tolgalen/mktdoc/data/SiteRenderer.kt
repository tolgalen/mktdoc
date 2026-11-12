package com.tolgalen.mktdoc.data

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.parser.Parser
import java.io.File

fun esc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

/** Texts of the buttons and messages of the generated HTML site (the exported site does not depend on the app language). */
class SiteLabels(val home: String, val search: String, val prev: String, val next: String, val theme: String,
                 val results: String, val none: String, val filter: String, val title: String, val footer: String, val changelog: String, val hardware: String) {
    companion object {
        fun forLang(lang: String) = if (lang == "es") SiteLabels("Índice", "Buscar…", "Anterior", "Siguiente", "Cambiar tema",
            "{n} resultados", "Sin resultados", "Buscar en toda la documentación…", "Manual de MikroTik RouterOS", "Copia sin conexión de manual.mikrotik.com", "Changelog de la documentación de MikroTik", "Guías de hardware de MikroTik")
        else SiteLabels("Index", "Search…", "Previous", "Next", "Toggle theme",
            "{n} results", "No results", "Search the whole documentation…", "MikroTik RouterOS manual", "Offline copy of manual.mikrotik.com", "MikroTik documentation changelog", "MikroTik hardware guides")
    }
}

object SiteRenderer {
    /** Bumped when the generated site changes in a way that existing copies should be rebuilt (a hidden stamp file records it). */
    const val RENDER_VERSION = 2
    const val RENDER_STAMP = ".render-version"

    const val OFFLINE_CSS = """
:root{--mk-bg:#fff;--mk-fg:#1c1e21;--mk-muted:#606770;--mk-border:#dadde1;--mk-field:#ccd0d5;--mk-accent:#2e8555}
[data-theme=dark]{--mk-bg:#1b1b1d;--mk-fg:#e3e3e3;--mk-muted:#a3a7ad;--mk-border:#3a3b3f;--mk-field:#4b4c51;--mk-accent:#25c2a0;color-scheme:dark}
html{scroll-behavior:smooth;-webkit-text-size-adjust:100%}
body{background:var(--mk-bg);color:var(--mk-fg);margin:0;font-family:system-ui,-apple-system,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;line-height:1.6}
.mk-wrap{max-width:980px;margin:0 auto;padding:.6rem 1rem 3rem}
.mk-top{display:flex;justify-content:space-between;align-items:center;gap:.8rem;padding:.5rem 0;border-bottom:1px solid var(--mk-border);margin-bottom:1rem;font-size:.9rem}
.mk-top a{color:var(--mk-accent)}
.mk-right{display:flex;align-items:center;gap:.8rem}
.mk-search{flex:1;max-width:22rem;margin:0}
.mk-search input,#sq{width:100%;box-sizing:border-box;padding:.3rem .6rem;border:1px solid var(--mk-field);border-radius:6px;background:var(--mk-bg);color:var(--mk-fg);font-size:.9rem}
#sq{padding:.6rem .8rem;font-size:1rem;margin:.5rem 0}
#sr small{color:var(--mk-muted)}
#sr ul{margin:.3rem 0 1rem;padding-left:1.2rem}
#sr a{color:var(--mk-accent)}
#mk-theme{cursor:pointer;border:1px solid var(--mk-border);background:transparent;color:var(--mk-fg);border-radius:6px;padding:.15rem .55rem;font-size:1rem;line-height:1.4}
.mk-crumbs{color:var(--mk-muted);font-size:.85rem;margin-bottom:.5rem}
.mk-pager{display:flex;justify-content:space-between;gap:1rem;margin-top:2.5rem;padding-top:1rem;border-top:1px solid var(--mk-border)}
.mk-pager a{border:1px solid var(--mk-border);border-radius:6px;padding:.5rem .9rem;text-decoration:none;max-width:48%;color:var(--mk-accent)}
.mk-foot{margin-top:2rem;font-size:.8rem;color:var(--mk-muted)}
.mk-index ul{list-style:none;padding-left:1.3rem;margin:.2rem 0}
.mk-index>ul{padding-left:0}
.mk-index summary{cursor:pointer;font-weight:600}
.mk-index a{color:var(--mk-accent)}
img{max-width:100%;height:auto}
[data-theme=dark] article img{background:#fff;border-radius:4px}
table{display:block;overflow-x:auto;max-width:100%}
pre{white-space:pre-wrap;word-break:break-word}
"""

    /** Runs in <head> before the first paint. Order: theme forced by the app, saved choice, system preference. */
    const val THEME_JS = """(function(){var K='mk-theme',d=document.documentElement,s=window.MK_FORCE||null;
try{if(!s)s=localStorage.getItem(K)}catch(e){}
if(!s&&/^mk-theme=(dark|light)${'$'}/.test(window.name))s=window.name.slice(9);
if(s!=='dark'&&s!=='light')s=window.matchMedia&&matchMedia('(prefers-color-scheme: dark)').matches?'dark':'light';
d.setAttribute('data-theme',s);
function set(v){d.setAttribute('data-theme',v);try{localStorage.setItem(K,v)}catch(e){}
if(!window.name||/^mk-theme=/.test(window.name))window.name='mk-theme='+v}
window.addEventListener('DOMContentLoaded',function(){var b=document.getElementById('mk-theme');if(!b||window.MK_FORCE)return;
b.hidden=false;b.onclick=function(){set(d.getAttribute('data-theme')==='dark'?'light':'dark')}})})();"""

    const val SEARCH_JS = """(function(){var I=window.MK_INDEX,S=window.MK_I18N||{},box=document.getElementById('sq'),out=document.getElementById('sr');
if(!I||!box||!out)return;var keys=Object.keys(I.i);
function tok(s){return (s.toLowerCase().match(/[\p{L}\p{N}_]{2,}/gu)||[])}
function ids(k){return I.i[k].split(',').map(function(x){return parseInt(x,36)})}
function run(){var qs=tok(box.value);out.innerHTML='';if(!qs.length)return;var res=null;
qs.forEach(function(w,n){var last=n===qs.length-1,set={};
keys.forEach(function(k){if(k===w||(last&&k.indexOf(w)===0))ids(k).forEach(function(d){set[d]=1})});
if(res===null)res=set;else{var r={};for(var d in res)if(set[d])r[d]=1;res=r}});
var list=Object.keys(res).map(function(d){d=+d;var t=I.t[d].toLowerCase();return {id:d,s:qs.every(function(w){return t.indexOf(w)>=0})?0:1}});
list.sort(function(a,b){return a.s-b.s||a.id-b.id});
var h='<p>'+(list.length?(S.n||'{n}').replace('{n}',list.length):(S.none||'No results'))+'</p><ul>';
list.slice(0,50).forEach(function(d){h+='<li><a href="'+I.u[d.id]+'">'+esc(I.t[d.id])+'</a> <small>'+esc(I.c[d.id])+'</small></li>'});
out.innerHTML=h+'</ul>'}
function esc(s){return s.replace(/[&<>"]/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]})}
box.addEventListener('input',run);var m=/[?&]q=([^&]*)/.exec(location.search);
if(m){box.value=decodeURIComponent(m[1].replace(/\+/g,' '));run()}})();"""

    private val token = Regex("[\\p{L}\\p{N}]{2,}")
    private val aTag = Regex("<a\\b[^>]*\\bdata-mk-key=\"([^\"]*)\"[^>]*>")
    private val fragAttr = Regex("data-mk-frag=\"([^\"]*)\"")
    private val hrefAttr = Regex("\\bhref=\"[^\"]*\"")

    /** key -> relative path of the local HTML file. */
    fun localMap(prefix: String, keys: List<String>): Map<String, String> {
        val rels = keys.associateWith { it.removePrefix(prefix).trim('/') }
        val dirs = HashSet<String>()
        for (rel in rels.values) {
            val parts = rel.split('/')
            for (i in 1 until parts.size) dirs.add(parts.take(i).joinToString("/"))
        }
        return keys.associateWith { k ->
            val rel0 = rels.getValue(k)
            val rel = if (rel0.isEmpty()) "home" else rel0.split('/').joinToString("/") { it.replace(Regex("[<>:\"|?*\\\\]"), "_") }
            if (rel0 in dirs) "$rel/index.html" else "$rel.html"
        }
    }

    fun relPath(from: String, to: String): String {
        val f = from.split('/').dropLast(1)
        val t = to.split('/')
        var i = 0
        while (i < f.size && i < t.size - 1 && f[i] == t[i]) i++
        return "../".repeat(f.size - i) + t.drop(i).joinToString("/")
    }

    /**
     * Downloaded page a menu entry points to. An entry whose page is already used by its category (the hardware guides'
     * categories link to their first product, which is also listed inside them) has no key of its own but its `href`
     * still names a downloaded page.
     */
    fun pageKeyOf(n: Node, have: Set<String>): String? = (n.key ?: n.href?.let { normPath(it) })?.takeIf { it in have }

    private fun hasPage(n: Node, have: Set<String>): Boolean = pageKeyOf(n, have) != null || n.children.any { hasPage(it, have) }

    private fun treeHtml(nodes: List<Node>, lm: Map<String, String>, have: Set<String>): String {
        val sb = StringBuilder("<ul>")
        for (n in nodes) {
            if (!hasPage(n, have)) continue
            val kids = treeHtml(n.children, lm, have)
            val pk = pageKeyOf(n, have)
            val label = if (pk != null) "<a href=\"${esc(lm.getValue(pk))}\">${esc(n.title)}</a>" else esc(n.title)
            sb.append(if (n.children.any { c -> hasPage(c, have) }) "<li><details><summary>$label</summary>$kids</details></li>" else "<li>$label</li>")
        }
        return sb.append("</ul>").toString()
    }

    private fun head(title: String, up: String, lang: String): String =
        "<!doctype html><html lang=\"$lang\" data-theme=\"light\"><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>${esc(title)}</title>" +
            "<link rel=\"stylesheet\" href=\"${up}assets/site.css\"><style>$OFFLINE_CSS</style><script>$THEME_JS</script></head>"

    private fun top(up: String, l: SiteLabels, searchBox: Boolean = true) =
        "<div class=\"mk-top\"><a href=\"${up}index.html\">⌂ ${esc(l.home)}</a>" +
            (if (searchBox) "<form class=\"mk-search\" action=\"${up}index.html\"><input name=\"q\" type=\"search\" placeholder=\"${esc(l.search)}\"></form>" else "<span></span>") +
            "<div class=\"mk-right\"><button id=\"mk-theme\" type=\"button\" hidden title=\"${esc(l.theme)}\" aria-label=\"${esc(l.theme)}\">◐</button></div></div>"

    /**
     * Builds the offline HTML site in [siteDir] from the pages saved in [store].
     * The resources (`assets/`) are already inside [siteDir].
     */
    fun render(store: Store, st: Structure, siteDir: File, lang: String, aliases: Set<String>, progress: (Int, Int) -> Unit = { _, _ -> }) {
        val l = SiteLabels.forLang(lang)
        val order = st.roots.flatMap { r -> r.pages().toList() }.filter { it.key in store.manifest }.distinctBy { it.key }
        val keys = order.map { it.key!! }
        val lm = localMap(st.version.prefix, keys)
        val have = keys.toHashSet()
        // links stored in the pages may carry the prefix the version had when it was downloaded
        val prefixes = (aliases + st.version.prefix).sortedByDescending { it.length }
        val byRel = keys.associateBy { k -> k.removePrefix(st.version.prefix).ifEmpty { "/" } }
        fun resolveKey(k: String): String? {
            if (k in lm) return k
            for (p in prefixes) if (k == p || k.startsWith("$p/")) return byRel[k.removePrefix(p).ifEmpty { "/" }]
            return null
        }
        val label = st.version.label
        val siteTitle = when (label) { CHANGELOG -> l.changelog; HARDWARE -> l.hardware; else -> "${l.title} $label" }
        siteDir.mkdirs()
        // old pages are deleted (not the resources)
        siteDir.listFiles()?.forEach { if (it.name != "assets") it.deleteRecursively() }

        val titles = ArrayList<String>(); val crumbs = ArrayList<String>(); val urls = ArrayList<String>()
        val inv = HashMap<String, MutableList<Int>>()
        order.forEachIndexed { i, n ->
            val meta = store.manifest.getValue(n.key!!)
            val raw = try { store.readRaw(meta) } catch (_: Exception) { "" }
            val path = lm.getValue(n.key!!)
            val up = "../".repeat(path.count { it == '/' })
            var html = raw.replace(ASSET_PREFIX, "${up}assets/")
            html = aTag.replace(html) { m ->
                val k = resolveKey(m.groupValues[1])
                // a link to the section home (e.g. /changelog) goes to the site index
                val target = k?.let { lm[it] } ?: if (prefixes.any { m.groupValues[1].trimEnd('/') == it }) "index.html" else null
                if (target == null) m.value else {
                    val frag = fragAttr.find(m.value)?.groupValues?.get(1).orEmpty()
                    val href = relPath(path, target) + if (frag.isNotEmpty()) "#$frag" else ""
                    hrefAttr.replace(m.value) { "href=\"$href\"" }
                }
            }
            val crumb = n.ancestors().filter { it.title.isNotEmpty() }.joinToString(" › ") { esc(it.title) }
            val prev = order.getOrNull(i - 1); val next = order.getOrNull(i + 1)
            fun pager(x: Node?, text: String, right: Boolean): String =
                if (x == null) "<span></span>" else "<a href=\"${esc(relPath(path, lm.getValue(x.key!!)))}\"${if (right) " style=\"text-align:right\"" else ""}>${if (right) "" else "« "}${esc(text)}: ${esc(x.title)}${if (right) " »" else ""}</a>"
            val sb = StringBuilder(head("${meta.title} — $siteTitle", up, lang))
            sb.append("<body><div class=\"mk-wrap\">").append(top(up, l))
            if (crumb.isNotEmpty()) sb.append("<div class=\"mk-crumbs\">$crumb</div>")
            sb.append("<div class=\"mk-page\">").append(html).append("</div>")
            sb.append("<nav class=\"mk-pager\">").append(pager(prev, l.prev, false)).append(pager(next, l.next, true)).append("</nav>")
            sb.append("<div class=\"mk-foot\">${esc(siteTitle)} · ${esc(l.footer)}</div></div></body></html>")
            val f = File(siteDir, path)
            f.parentFile?.mkdirs()
            f.writeText(sb.toString())

            titles.add(meta.title); crumbs.add(n.ancestors().filter { it.title.isNotEmpty() }.joinToString(" › ") { it.title }); urls.add(path)
            val text = Parser.unescapeEntities(raw.replace(Regex("<[^>]+>"), " "), false).lowercase()
            for (tk in (token.findAll(meta.title.lowercase()) + token.findAll(text)).map { it.value }.toSet()) inv.getOrPut(tk) { ArrayList() }.add(i)
            if (i % 50 == 0) progress(i, order.size)
        }
        // full-text search index (loaded with <script>, so it also works from file://)
        val limit = order.size * 0.9
        val invJson = JSONObject()
        for ((k, v) in inv) if (v.size < limit) invJson.put(k, v.joinToString(",") { it.toString(36) })
        val index = JSONObject().put("t", JSONArray(titles)).put("c", JSONArray(crumbs)).put("u", JSONArray(urls)).put("i", invJson)
        writeAtomic(File(siteDir, "assets/search-index.js"), "window.MK_INDEX=$index;")

        val i18n = JSONObject().put("n", l.results).put("none", l.none)
        val idx = StringBuilder(head(siteTitle, "", lang))
        idx.append("<body><div class=\"mk-wrap\">").append(top("", l, searchBox = false))
        idx.append("<h1>${esc(siteTitle)}</h1><input id=\"sq\" type=\"search\" placeholder=\"${esc(l.filter)}\" autocomplete=\"off\"><div id=\"sr\"></div>")
        idx.append("<div class=\"mk-index\">").append(treeHtml(st.roots, lm, have)).append("</div>")
        idx.append("<div class=\"mk-foot\">${esc(l.footer)}</div></div>")
        idx.append("<script>window.MK_I18N=$i18n;</script><script src=\"assets/search-index.js\"></script><script>$SEARCH_JS</script></body></html>")
        File(siteDir, "index.html").writeText(idx.toString())
        writeAtomic(File(siteDir, RENDER_STAMP), RENDER_VERSION.toString())
        progress(order.size, order.size)
    }
}
