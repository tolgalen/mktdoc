package com.tolgalen.mktdoc.data

import com.tolgalen.mktdoc.pdf.PdfExporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PdfLinkTest {
    private val byRel = mapOf("/introduction" to 0, "/container" to 1, "/container/veth" to 2)

    @Test fun internalLinkWithPrefixAndFragment() {
        assertEquals(1, PdfExporter.linkTarget("https://manual.mikrotik.com/docs/7.25/container#setup", "/docs/7.25", byRel))
        assertEquals(2, PdfExporter.linkTarget("https://manual.mikrotik.com/docs/7.25/container/veth/", "/docs/7.25", byRel))
    }

    @Test fun currentVersionPrefixIsAlsoAccepted() {
        assertEquals(0, PdfExporter.linkTarget("https://manual.mikrotik.com/docs/introduction", "/docs/7.26", byRel))
    }

    @Test fun externalOrMissingLinksAreNotInternal() {
        assertNull(PdfExporter.linkTarget("https://example.com/docs/7.25/container", "/docs/7.25", byRel))
        assertNull(PdfExporter.linkTarget("https://manual.mikrotik.com/docs/7.25/not-exported", "/docs/7.25", byRel))
        assertNull(PdfExporter.linkTarget("not a uri", "/docs/7.25", byRel))
    }
}
