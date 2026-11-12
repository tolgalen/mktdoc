package com.tolgalen.mktdoc

import androidx.test.platform.app.InstrumentationRegistry
import com.tolgalen.mktdoc.data.PageRef
import com.tolgalen.mktdoc.data.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Runs the preferences code on a device, with a scratch preferences file so the app's own settings are untouched. */
class PrefsTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "mktdoc-test"
    private fun prefs() = Prefs(ctx, name)

    @After fun clean() { ctx.getSharedPreferences(name, 0).edit().clear().commit() }

    @Test fun firstSeenVersionsAreRecordedSilentlyAndNewOnesAreReportedOnce() {
        val p = prefs()
        assertEquals(emptyList<String>(), p.registerOnlineVersions(listOf("7.26", "7.25")))
        assertEquals(listOf("7.27"), p.registerOnlineVersions(listOf("7.27", "7.26", "7.25")))
        assertEquals(emptyList<String>(), p.registerOnlineVersions(listOf("7.27", "7.26", "7.25")))
        assertEquals(emptyList<String>(), prefs().registerOnlineVersions(listOf("7.27")))          // persisted
    }

    @Test fun scrollPositionIsRememberedPerPage() {
        val p = prefs()
        p.setScroll("7.26", "a.html", 0.4f)
        p.setScroll("7.26", "b.html", 0.01f)          // practically the top: not stored
        p.flushScroll()
        val q = prefs()
        assertEquals(0.4f, q.scrollOf("7.26", "a.html")!!, 0.001f)
        assertNull(q.scrollOf("7.26", "b.html"))
    }

    @Test fun bookmarksExportAndImportMergeWithoutDuplicates() {
        val a = prefs()
        a.toggleBookmark(PageRef("7.26", "x.html", "X")); a.toggleBookmark(PageRef("hardware", "y.html", "Y"))
        val json = a.bookmarksJson()
        val b = Prefs(ctx, "$name-b")
        try {
            b.toggleBookmark(PageRef("7.26", "x.html", "X"))
            assertEquals(1, b.importBookmarks(json))
            assertEquals(0, b.importBookmarks(json))
            assertEquals(2, b.bookmarks.value.size)
            assertNull(b.importBookmarks("{\"app\":\"other\"}"))
            assertNull(b.importBookmarks("not json"))
        } finally { ctx.getSharedPreferences("$name-b", 0).edit().clear().commit() }
    }
}
