package app.iduanju.core

import app.iduanju.core.hls.HLSAdFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HLSAdFilterTest {
    @Test
    fun stripsAdUrl() {
        val body = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXTINF:10.0,
            https://cdn.example.com/seg0.ts
            #EXTINF:2.0,
            https://cdn.example.com/ads/preroll.ts
            #EXTINF:10.0,
            https://cdn.example.com/seg1.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val r = HLSAdFilter.filter(body)
        assertEquals(1, r.removed)
        assertFalse(r.playlist.contains("preroll"))
        assertTrue(r.playlist.contains("seg0.ts"))
        assertTrue(r.playlist.contains("seg1.ts"))
    }

    @Test
    fun leavesMasterAlone() {
        val body = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000
            high.m3u8
        """.trimIndent()
        val r = HLSAdFilter.filter(body)
        assertEquals(0, r.removed)
        assertEquals(body, r.playlist)
    }
}
