package app.iduanju.core

import app.iduanju.core.update.AppRelease
import org.junit.Assert.*
import org.junit.Test

class AppReleaseTest {
    private fun rel(tag: String, apk: Boolean = true, draft: Boolean = false) = """{"tag_name":"$tag","name":"n $tag","draft":$draft,"prerelease":true,
        "body":"### 修复\n- **a**\n\n### 校验\n```\nSHA256 x\n```","html_url":"https://github.com/x/$tag",
        "assets":[${if (apk) """{"name":"iDuanJu-$tag.apk","size":123,"digest":"sha256:ABCD","browser_download_url":"https://dl/$tag.apk"}""" else """{"name":"x.ipa"}"""}]}"""

    @Test fun parseAndCompare() {
        assertEquals(listOf(1, 5, 4), AppRelease.parseVersion("v1.5.4-android"))
        assertEquals(listOf(1, 5, 3), AppRelease.parseVersion("1.5.3-android"))
        assertNull(AppRelease.parseVersion("android-1"))
        assertTrue(AppRelease.compare(listOf(1, 5, 10), listOf(1, 5, 9)) > 0)
        assertEquals(0, AppRelease.compare(listOf(1, 5), listOf(1, 5, 0)))
        assertTrue(AppRelease.compare(listOf(1, 6), listOf(1, 5, 9)) > 0)
    }

    @Test fun picksNewestAndroidWithApk() {
        val json = "[" + listOf(rel("v1.5b3-45d3971"), rel("v1.5.3-android"), rel("v1.5.10-android", apk = false),
            rel("v1.6.0-android", draft = true), rel("v1.5.4-android"), rel("v1.5.2-android")).joinToString(",") + "]"
        val r = AppRelease.newestAndroid(json)!!
        assertEquals("v1.5.4-android", r.tag)
        assertEquals("1.5.4", r.versionName)
        assertEquals("https://dl/v1.5.4-android.apk", r.apkUrl)
        assertEquals("abcd", r.sha256)
        assertEquals("修复\n- a", AppRelease.plainNotes(r.notes))
    }

    @Test fun noneWhenEmpty() { assertNull(AppRelease.newestAndroid("[]")) }
}
