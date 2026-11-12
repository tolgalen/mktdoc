package com.tolgalen.mktdoc.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocSetTest {
    @Test fun changelogAndHardwareAreNotVersions() {
        assertTrue(isDocSet(CHANGELOG)); assertTrue(isDocSet(HARDWARE)); assertFalse(isDocSet("7.26"))
        assertEquals("/hardware", docSetVersion(HARDWARE)?.prefix)
        assertNull(docSetVersion("7.25"))
    }

    @Test fun entryPageOfEachCollection() {
        assertEquals("https://manual.mikrotik.com/hardware/", entryUrl(hardwareVersion()))
        assertEquals("https://manual.mikrotik.com/docs/7.25/introduction", entryUrl(DocVersion("7.25", "/docs/7.25")))
        assertEquals("https://manual.mikrotik.com/docs/introduction", entryUrl(DocVersion("7.26", "/docs", true)))
    }

    @Test fun hardwarePagesBelongToTheHardwareCollection() {
        val v = hardwareVersion()
        assertTrue(Discovery.inVersion("/hardware/cap-ax", v.prefix, v.current))
        assertFalse(Discovery.inVersion("/docs/hardware/leds", v.prefix, v.current))
    }

    @Test fun localFilesForFlatHardwarePages() {
        val m = SiteRenderer.localMap("/hardware", listOf("/hardware", "/hardware/cap-ax", "/hardware/crs-series", "/hardware/crs-series/crs326"))
        assertEquals("home.html", m["/hardware"])
        assertEquals("cap-ax.html", m["/hardware/cap-ax"])
        assertEquals("crs-series/index.html", m["/hardware/crs-series"])
    }

    @Test fun menuEntryRepeatingItsCategoryPageStillPointsToThatPage() {
        val have = setOf("/hardware/apa-1", "/hardware/ftc21")
        val category = Node("Accessories", "/hardware/apa-1", "/hardware/apa-1")
        val first = Node("APA-1", "/hardware/apa-1", null)          // same page, key dropped as a duplicate
        val other = Node("FTC21", "/hardware/ftc21", "/hardware/ftc21")
        assertEquals("/hardware/apa-1", SiteRenderer.pageKeyOf(category, have))
        assertEquals("/hardware/apa-1", SiteRenderer.pageKeyOf(first, have))
        assertEquals("/hardware/ftc21", SiteRenderer.pageKeyOf(other, have))
        assertNull(SiteRenderer.pageKeyOf(Node("Folder"), have))
        assertNull(SiteRenderer.pageKeyOf(Node("Gone", "/hardware/gone", null), have))
    }

    @Test fun categoryWhoseFirstProductIsItsOwnPageGetsAListPage() {
        val cat = Node("Accessories", "/hardware/apa-1", "/hardware/apa-1")
        cat.add(Node("APA-1", "/hardware/apa-1", null))
        cat.add(Node("FTC21", "/hardware/ftc21", "/hardware/ftc21"))
        val plain = Node("Container", "/docs/container", "/docs/container")     // a docs category has its own page
        plain.add(Node("VETH", "/docs/container/veth", "/docs/container/veth"))
        val st = Structure(hardwareVersion(), mutableListOf(cat, plain), emptyList())
        val have = setOf("/hardware/apa-1", "/hardware/ftc21", "/docs/container", "/docs/container/veth")
        val lm = SiteRenderer.localMap("", have.toList())
        val pages = SiteRenderer.categoryPages(st, have, lm)
        assertEquals(setOf("/hardware/apa-1"), pages.keys)
        assertTrue(pages.getValue("/hardware/apa-1").startsWith("_cat/"))
    }
}
