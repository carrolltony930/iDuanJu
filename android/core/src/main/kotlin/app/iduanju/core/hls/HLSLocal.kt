package app.iduanju.core.hls

import java.net.URI

/**
 * Prepares a media playlist for local playback (iOS serves the filtered playlist through its loopback proxy;
 * Android writes it to a file): master → best variant, every URI absolutised, then [HLSAdFilter].
 */
object HLSLocal {
    data class Prepared(val text: String, val variantUrl: String, val removed: Int, val removedSeconds: Double)

    private val uriAttr = Regex("""URI="([^"]+)"""")

    fun absolutize(text: String, base: URI): String = text.split("\n").joinToString("\n") { raw ->
        val line = raw.trimEnd('\r')
        when {
            line.isBlank() -> line
            line.startsWith("#") -> uriAttr.replace(line) { m -> "URI=\"" + (resolve(base, m.groupValues[1]) ?: m.groupValues[1]) + "\"" }
            else -> resolve(base, line.trim()) ?: line
        }
    }

    private fun resolve(base: URI, s: String): String? = try { base.resolve(URI(s.replace(" ", "%20"))).toString() } catch (_: Exception) { null }

    /** Picks the highest-BANDWIDTH variant of a master playlist, or null for a media playlist. */
    fun bestVariant(text: String, base: URI): String? {
        val lines = text.split("\n").map { it.trim() }
        var best: String? = null; var bw = -1L
        for ((i, l) in lines.withIndex()) {
            if (!l.uppercase().startsWith("#EXT-X-STREAM-INF")) continue
            val b = Regex("""BANDWIDTH=(\d+)""", RegexOption.IGNORE_CASE).find(l)?.groupValues?.get(1)?.toLongOrNull() ?: 0
            val u = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
            if (b > bw) { bw = b; best = resolve(base, u) }
        }
        return best
    }

    fun prepare(url: String, fetch: (String) -> String): Prepared {
        var u = url
        var text = fetch(u)
        repeat(2) { bestVariant(text, URI(u))?.let { v -> u = v; text = fetch(u) } ?: return@repeat }
        val abs = absolutize(text, URI(u))
        val r = if (HLSAdFilter.enabled) HLSAdFilter.filter(abs, URI(u)) else HLSAdFilter.Result(abs, 0, 0.0, emptyList())
        return Prepared(r.text, u, r.removed, r.removedSeconds)
    }
}
