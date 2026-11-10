package app.iduanju.core.hls

import java.net.URI

/**
 * 「去广告」 — faithful port of STV/Playback/HLSAdFilter.swift. Removes inserted ad segments from an HLS
 * **media** playlist. Rules (never a blanket DISCONTINUITY drop — 如意 cuts every 5 segments):
 * 1. a segment whose URI contains `adjump`;
 * 2. a DISCONTINUITY-bounded block on another host / directory than the main content, 5–30 s and ≤ 10 segments;
 * 3. when the main content is AES-128, a `METHOD=NONE` block of 8–30 s;
 * 4. segment URIs repeated within the episode (< 25 % of duration).
 * Safety: never removes more than 40 % of the playlist's duration; master playlists pass through.
 */
object HLSAdFilter {
    @Volatile var enabled: Boolean = true

    private class Segment {
        val tags = ArrayList<String>()
        var extinf = ""; var duration = 0.0; var uri = ""; var url: URI? = null
        var discontinuity = false; var keyLine: String? = null; var mapLine: String? = null
        val encrypted get() = keyLine?.let { !it.uppercase().contains("METHOD=NONE") } ?: false
    }

    data class Result(val text: String, val removed: Int, val removedSeconds: Double, val reasons: List<String>)

    private fun resolve(base: URI, s: String): URI? = try { base.resolve(URI(s.replace(" ", "%20"))) } catch (_: Exception) { null }

    fun filter(text: String, base: URI): Result {
        val lines = text.split("\n").map { it.trim() }
        if (lines.none { it.uppercase().startsWith("#EXTINF") } || lines.any { it.uppercase().startsWith("#EXT-X-STREAM-INF") })
            return Result(text, 0, 0.0, emptyList())
        val header = ArrayList<String>(); val trailer = ArrayList<String>()
        val segs = ArrayList<Segment>()
        var cur = Segment(); var key: String? = null; var map: String? = null; var pendingDisc = false; var started = false
        for (line in lines) {
            val up = line.uppercase()
            when {
                up.startsWith("#EXT-X-KEY") -> { key = line; continue }
                up.startsWith("#EXT-X-MAP") -> { map = line; continue }
                up.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE") -> { header.add(line); continue }
                up.startsWith("#EXT-X-DISCONTINUITY") -> { pendingDisc = true; continue }
                up.startsWith("#EXT-X-ENDLIST") -> { trailer.add(line); continue }
                up.startsWith("#EXTINF") -> {
                    started = true; cur.extinf = line
                    cur.duration = line.drop(8).split(",").firstOrNull()?.trim()?.toDoubleOrNull() ?: 0.0
                    continue
                }
            }
            if (line.isEmpty()) continue
            if (line.startsWith("#")) {
                if (!started && cur.extinf.isEmpty() && segs.isEmpty() && !isSegmentTag(up)) header.add(line) else cur.tags.add(line)
                continue
            }
            cur.uri = line; cur.url = resolve(base, line); cur.discontinuity = pendingDisc
            cur.keyLine = key; cur.mapLine = map
            segs.add(cur); cur = Segment(); pendingDisc = false
        }
        if (segs.size < 3) return Result(text, 0, 0.0, emptyList())
        val total = segs.sumOf { it.duration }
        val drop = LinkedHashSet<Int>(); val reasons = ArrayList<String>()

        fun host(s: Segment) = s.url?.host?.lowercase() ?: ""
        fun dir(s: Segment) = s.url?.path?.let { p -> val i = p.lastIndexOf('/'); if (i >= 0) p.substring(0, i + 1) else "" } ?: ""
        fun <K> major(idx: List<Int>, k: (Segment) -> K): Pair<K, Double>? {
            val w = LinkedHashMap<K, Double>()
            for (i in idx) w[k(segs[i])] = (w[k(segs[i])] ?: 0.0) + maxOf(segs[i].duration, 0.001)
            return w.maxByOrNull { it.value }?.let { it.key to it.value }
        }
        val all = segs.indices.toList()
        val mainHost = major(all, ::host)?.first ?: ""
        val mainDir = major(all, ::dir)
        val dirReliable = (mainDir?.second ?: 0.0) >= total * 0.5
        val mainEncrypted = major(all) { it.encrypted }?.first ?: false

        for (i in all) if (segs[i].uri.lowercase().contains("adjump")) drop.add(i)
        if (drop.isNotEmpty()) reasons.add("adjump×${drop.size}")

        val blocks = ArrayList<List<Int>>(); var b = ArrayList<Int>()
        for (i in all) { if (segs[i].discontinuity && b.isNotEmpty()) { blocks.add(b); b = ArrayList() }; b.add(i) }
        if (b.isNotEmpty()) blocks.add(b)
        if (blocks.size > 1) for (blk in blocks) {
            val dur = blk.sumOf { segs[it].duration }
            val bHost = major(blk, ::host)?.first ?: ""
            val bDir = major(blk, ::dir)?.first ?: ""
            val foreign = bHost != mainHost || (dirReliable && bDir != mainDir?.first)
            if (foreign && dur in 5.0..30.0 && blk.size <= 10) { drop.addAll(blk); reasons.add("foreign $bHost$bDir ${dur.toInt()}s"); continue }
            if (mainEncrypted && blk.all { !segs[it].encrypted } && dur in 8.0..30.0) { drop.addAll(blk); reasons.add("clear-in-AES ${dur.toInt()}s") }
        }
        val seen = LinkedHashMap<String, MutableList<Int>>()
        for (i in all) seen.getOrPut(segs[i].url?.toString() ?: segs[i].uri) { ArrayList() }.add(i)
        val repeated = seen.values.filter { it.size > 1 }.flatten()
        if (repeated.isNotEmpty()) {
            val rd = repeated.sumOf { segs[it].duration }
            if (rd < total * 0.25) { drop.addAll(repeated); reasons.add("repeated×${repeated.size}") }
        }
        val removedSeconds = drop.sumOf { segs[it].duration }
        if (drop.isEmpty() || removedSeconds > total * 0.4 || drop.size >= segs.size)
            return Result(text, 0, 0.0, if (drop.isEmpty()) emptyList() else listOf("skipped: too much (${removedSeconds.toInt()}s)"))

        val out = ArrayList(header)
        var lastKey: String? = null; var lastMap: String? = null; var disc = false; var first = true
        for ((i, s) in segs.withIndex()) {
            if (i in drop) { if (!first) disc = true; continue }
            if (!first && (disc || s.discontinuity)) out.add("#EXT-X-DISCONTINUITY")
            disc = false
            val k = s.keyLine
            if (k != null && k != lastKey) { out.add(k); lastKey = k }
            else if (k == null && lastKey != null) { out.add("#EXT-X-KEY:METHOD=NONE"); lastKey = null }
            val m = s.mapLine
            if (m != null && m != lastMap) { out.add(m); lastMap = m }
            out.addAll(s.tags)
            out.add(s.extinf.ifEmpty { "#EXTINF:${s.duration}," })
            out.add(s.uri)
            first = false
        }
        out.addAll(trailer)
        return Result(out.joinToString("\n") + "\n", drop.size, removedSeconds, reasons)
    }

    private fun isSegmentTag(up: String) =
        up.startsWith("#EXT-X-BYTERANGE") || up.startsWith("#EXT-X-PROGRAM-DATE-TIME") || up.startsWith("#EXT-X-GAP") || up.startsWith("#EXT-X-BITRATE")

    fun apply(text: String, base: URI): String = if (!enabled) text else filter(text, base).text
}
