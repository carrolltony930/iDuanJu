package app.iduanju.core.hls

/**
 * Port of STV/Playback/HLSAdFilter.swift — strip common short-drama ad segments from HLS playlists.
 * Same rules as iOS unit tests.
 */
object HLSAdFilter {
    data class Result(val playlist: String, val removed: Int)

    private val adHints = listOf(
        "advertisement", "adservice", "ad_segment", "adsegment", "/ads/", "/ad/",
        "adurl", "ad_url", "googleads", "doubleclick", "adcdn", "ad-media",
        "preroll", "midroll", "postroll", "adbreak", "ad_break",
    )

    /** Filter an M3U8 body. Master playlists are returned unchanged. */
    fun filter(body: String): Result {
        val lines = body.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) return Result(body, 0)
        val out = ArrayList<String>()
        var removed = 0
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXTINF:")) {
                val dur = line.substringAfter("#EXTINF:").substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                val uri = (i + 1 until lines.size).firstOrNull { !lines[it].startsWith("#") && lines[it].isNotBlank() }
                    ?.let { lines[it] } ?: ""
                val isAd = isAdSegment(dur, uri, lines, i)
                if (isAd) {
                    removed++
                    // skip EXTINF + optional tags until next URI consumed
                    i++
                    while (i < lines.size && (lines[i].startsWith("#") || lines[i].isBlank())) {
                        if (lines[i].startsWith("#EXTINF") || lines[i].startsWith("#EXT-X-ENDLIST") ||
                            lines[i].startsWith("#EXT-X-MEDIA-SEQUENCE") || lines[i].startsWith("#EXT-X-TARGETDURATION") ||
                            lines[i].startsWith("#EXT-X-VERSION") || lines[i].startsWith("#EXTM3U")
                        ) break
                        i++
                    }
                    if (i < lines.size && !lines[i].startsWith("#")) i++ // skip URI
                    continue
                }
            }
            out.add(line); i++
        }
        return Result(out.joinToString("\n"), removed)
    }

    private fun isAdSegment(dur: Double, uri: String, lines: List<String>, idx: Int): Boolean {
        val u = uri.lowercase()
        if (adHints.any { u.contains(it) }) return true
        // look at preceding tags for EXT-X-DATERANGE / SCTE / DISCONTINUITY + short duration
        var j = idx - 1
        while (j >= 0 && lines[j].startsWith("#")) {
            val t = lines[j].lowercase()
            if (t.contains("scte35") || t.contains("daterange") && t.contains("ad") ||
                t.contains("x-cue-out") || t.contains("x-asset-list")
            ) return true
            j--
        }
        // very short segments (<1.5s) right after discontinuity often ads
        if (dur > 0 && dur < 1.5) {
            var k = idx - 1
            while (k >= 0) {
                if (lines[k].startsWith("#EXT-X-DISCONTINUITY")) return true
                if (lines[k].startsWith("#EXTINF")) break
                k--
            }
        }
        return false
    }
}
