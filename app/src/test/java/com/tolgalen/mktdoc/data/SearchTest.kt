package com.tolgalen.mktdoc.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SearchTest {
    private fun index(): SearchIndex {
        val f = File.createTempFile("idx", ".js")
        f.writeText("""window.MK_INDEX={"t":["Container","Bridge VLAN","Container config"],"c":["A","B","A › C"],"u":["a.html","b.html","c.html"],"i":{"container":"0,2","config":"2","bridge":"1","vlan":"1"}};""")
        return SearchIndex.load(f)!!.also { f.delete() }
    }

    @Test fun matchesAllTermsAndPrefixes() {
        val hits = index().search("contain conf", "7.26", 10)
        assertEquals(listOf("c.html"), hits.map { it.path })
    }

    @Test fun titleMatchRanksFirst() {
        val hits = index().search("container", "7.26", 10)
        assertEquals("Container", hits.first().title)
        assertEquals(2, hits.size)
    }

    @Test fun noResultsAndShortQuery() {
        assertTrue(index().search("zzz", "x", 10).isEmpty())
        assertTrue(index().search("a", "x", 10).isEmpty())
    }
}
