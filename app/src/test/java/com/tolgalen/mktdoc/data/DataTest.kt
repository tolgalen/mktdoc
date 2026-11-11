package com.tolgalen.mktdoc.data

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class DataTest {
    @Test fun versionsCompareNumerically() {
        assertTrue(compareVersions("7.10", "7.9") > 0)
        assertEquals(0, compareVersions("7.25", "7.25.0"))
    }

    @Test fun normPathStripsSlashesQueryAndFragment() {
        assertEquals("/docs/a/b", normPath("/docs//a/b/?x=1#f"))
        assertEquals("/", normPath(""))
    }

    @Test fun localMapUsesIndexForFolders() {
        val m = SiteRenderer.localMap("/docs", listOf("/docs", "/docs/a", "/docs/a/b"))
        assertEquals("home.html", m["/docs"])
        assertEquals("a/index.html", m["/docs/a"])
        assertEquals("a/b.html", m["/docs/a/b"])
    }

    @Test fun relPathBetweenPages() {
        assertEquals("../c/d.html", SiteRenderer.relPath("a/b.html", "c/d.html"))
        assertEquals("c.html", SiteRenderer.relPath("a/b.html", "a/c.html"))
        assertEquals("a/b.html", SiteRenderer.relPath("index.html", "a/b.html"))
    }

    @Test fun parsesDocusaurusMenu() {
        val html = """<ul class="theme-doc-sidebar-menu menu__list">
            <li class="menu__list-item"><a class="menu__link" href="/docs/introduction">Intro</a></li>
            <li class="menu__list-item"><div class="menu__list-item-collapsible"><a class="menu__link menu__link--sublist" href="/docs/wireless">Wireless</a></div>
              <ul class="menu__list"><li><a class="menu__link" href="/docs/wireless/wifi">WiFi</a></li></ul></li>
            <li><a class="menu__link" href="/docs/7.25/introduction">Old</a></li></ul>"""
        val nodes = Discovery.parseMenu(Jsoup.parse(html).selectFirst("ul")!!, "https://manual.mikrotik.com/docs/introduction", DocVersion("7.26", "/docs", true), HashSet())
        assertEquals(3, nodes.size)
        assertEquals("/docs/introduction", nodes[0].key)
        assertTrue(nodes[1].category)
        assertEquals("/docs/wireless/wifi", nodes[1].children[0].key)
        assertNull("links to other versions are not pages of this one", nodes[2].key)
    }

    @Test fun structureJsonRoundTrip() {
        val root = Node("A", "/docs/a", "/docs/a"); root.add(Node("B", "/docs/a/b", "/docs/a/b"))
        val st = Structure(DocVersion("7.26", "/docs", true), mutableListOf(root), listOf("https://x/y.css"))
        val back = Structure.fromJson(st.toJson())
        assertEquals(listOf("/docs/a", "/docs/a/b"), back.allPages().map { it.key })
        assertEquals(1, back.roots[0].children.size)
    }

    @Test fun textOfIgnoresWhitespaceDifferences() {
        val a = Crawler.textOf(Jsoup.parse("<article><p>Hola   mundo</p>\n<p>fin</p></article>").selectFirst("article")!!)
        val b = Crawler.textOf(Jsoup.parse("<article><p>Hola mundo</p><p>fin</p></article>").selectFirst("article")!!)
        assertEquals(sha1(a), sha1(b))
    }
}
