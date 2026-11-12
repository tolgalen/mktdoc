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
}
