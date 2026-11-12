package com.tolgalen.mktdoc.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    private val sha = "75f78574da28a82691e8591c29d5a2769731629953b05729e37f8a8bb4e628bc"
    private fun release(tag: String, apk: Boolean = true, pre: Boolean = false, draft: Boolean = false, digest: String? = "sha256:$sha", body: String = "notes") =
        JSONObject().put("tag_name", tag).put("name", "mktdoc ${tag.removePrefix("v")}").put("body", body).put("draft", draft).put("prerelease", pre)
            .put("published_at", "2026-11-11T19:25:41Z").put("html_url", "https://github.com/tolgalen/mktdoc/releases/tag/$tag")
            .put("assets", org.json.JSONArray().also { a ->
                if (apk) a.put(JSONObject().put("name", "mktdoc-$tag.apk").put("size", 11375911L)
                    .put("browser_download_url", "https://github.com/tolgalen/mktdoc/releases/download/$tag/mktdoc-$tag.apk").also { o -> if (digest != null) o.put("digest", digest) })
            })

    @Test fun parsesAReleaseWithApkAndDigest() {
        val r = AppRelease.fromGitHub(release("v1.0.0"))!!
        assertEquals("1.0.0", r.version)
        assertEquals(11375911L, r.apkSize)
        assertEquals(sha, r.sha256)
        assertTrue(r.apkUrl!!.endsWith("mktdoc-v1.0.0.apk"))
    }

    @Test fun takesTheChecksumFromTheNotesWhenThereIsNoDigest() {
        val r = AppRelease.fromGitHub(release("v1.1.0", digest = null, body = "Install\nSHA-256: `$sha`\n"))!!
        assertEquals(sha, r.sha256)
    }

    @Test fun skipsDraftsAndKeepsReleasesWithoutApk() {
        assertNull(AppRelease.fromGitHub(release("v2.0.0", draft = true)))
        assertNull(AppRelease.fromGitHub(release("v1.2.0", apk = false))!!.apkUrl)
    }

    @Test fun jsonRoundTrip() {
        val r = AppRelease.fromGitHub(release("v1.0.0"))!!
        assertEquals(r, AppRelease.fromJson(r.toJson()))
    }

    @Test fun picksTheNewestStableReleaseWithApk() {
        val list = listOf("v1.3.0-beta" to true, "v1.2.0" to false, "v1.1.0" to false, "v1.0.0" to false).map { (t, pre) -> AppRelease.fromGitHub(release(t, pre = pre))!! }
        assertEquals("1.2.0", Updates.newer(list, "1.0.0")?.version)
        assertEquals("1.2.0", Updates.newer(list, "1.1.5")?.version)
        assertNull(Updates.newer(list, "1.2.0"))
        assertNull(Updates.newer(list, "2.0"))
        assertNotNull(Updates.newer(list, "0.9"))
    }

    @Test fun notesAreShownAsPlainText() {
        val md = "# mktdoc 1.0.0\n\nFirst **release**.\n\n## Highlights\n- Offline `HTML` site\n\nSHA-256: `$sha`\n"
        val t = Updates.plainNotes(md)
        assertEquals("mktdoc 1.0.0\n\nFirst release.\n\nHighlights\n• Offline HTML site", t)
    }
}
