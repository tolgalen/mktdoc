package com.tolgalen.mktdoc

import androidx.test.platform.app.InstrumentationRegistry
import com.tolgalen.mktdoc.data.Library
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File

/** Runs the real search code against a small copy written into the app's private storage. */
class LibrarySearchTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(ctx.filesDir, "docs/9.99")

    @Before fun setUp() {
        File(dir, "site/assets").mkdirs()
        File(dir, "meta.json").writeText("""{"label":"9.99","prefix":"/docs/9.99","current":false,"aliases":["/docs/9.99"],"downloadedAt":1,"checkedAt":1,"pages":2,"size":10,"removedOnline":false,"pending":0}""")
        File(dir, "site/assets/search-index.js").writeText(
            """window.MK_INDEX={"t":["Container","Bridge VLAN"],"c":["A","B"],"u":["container.html","bridge/vlan.html"],"i":{"container":"0","bridge":"1","vlan":"1"}};""")
    }

    @After fun tearDown() { dir.deleteRecursively() }

    @Test fun findsPagesInTheDownloadedVersion() = runBlocking {
        val lib = Library(ctx)
        val hits = lib.search("vlan", only = "9.99")
        assertEquals(listOf("bridge/vlan.html"), hits.map { it.path })
        assertEquals(emptyList<String>(), lib.search("nothing", only = "9.99").map { it.path })
    }
}
