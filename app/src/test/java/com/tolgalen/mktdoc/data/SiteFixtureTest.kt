package com.tolgalen.mktdoc.data

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests against real sidebars saved from manual.mikrotik.com (`src/test/resources/fixtures`), so that a change in the
 * website's markup that breaks the structure parser shows up here.
 */
class SiteFixtureTest {
    private fun fixture(name: String) = javaClass.getResourceAsStream("/fixtures/$name")!!.readBytes().toString(Charsets.UTF_8)

    @Test fun hardwareCategoryListsItsFirstProductAgain() {
        val url = "https://manual.mikrotik.com/hardware/apa-1"
        val nodes = Discovery.parseMenuDoc(Jsoup.parse(fixture("hardware-apa-1-sidebar.html"), url), url, hardwareVersion())
        val acc = nodes.first { it.title == "Accessories" }
        assertEquals("/hardware/apa-1", acc.key)
        assertTrue(acc.category)
        assertEquals(15, acc.children.size)
        // the first product is the category's own page: kept as an entry, but without a key of its own
        val first = acc.children.first()
        assertEquals("APA-1", first.title)
        assertNull(first.key)
        assertEquals("/hardware/apa-1", first.href)
        assertTrue(acc.children.drop(1).all { it.key != null && it.key!!.startsWith("/hardware/") })
    }

    @Test fun hardwareSidebarHasTheCategories() {
        val url = "https://manual.mikrotik.com/hardware/apa-1"
        val nodes = Discovery.parseMenuDoc(Jsoup.parse(fixture("hardware-apa-1-sidebar.html"), url), url, hardwareVersion())
        val titles = nodes.map { it.title }
        assertTrue(titles.containsAll(listOf("60 GHz products", "Accessories", "Antennas", "Switches", "Wireless systems")))
    }

    @Test fun docsSidebarHasNoDuplicatePagesAndStaysInTheVersion() {
        val url = "https://manual.mikrotik.com/docs/introduction"
        val v = DocVersion("7.26", "/docs", true)
        val nodes = Discovery.parseMenuDoc(Jsoup.parse(fixture("docs-introduction-sidebar.html"), url), url, v)
        assertTrue(nodes.isNotEmpty())
        val keys = nodes.flatMap { it.walk().toList() }.mapNotNull { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.all { it.startsWith("/docs/") })
        assertTrue(nodes.any { it.title == "Getting Started" })
        assertEquals("/docs/introduction", nodes.first().key)
    }
}
