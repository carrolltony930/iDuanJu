package app.iduanju.core.update

import org.json.JSONArray

/** One Android build published as a GitHub release (tag `v<x.y.z>-android`, one .apk asset). */
data class AppRelease(
    val tag: String,
    val version: List<Int>,
    val name: String,
    val notes: String,
    val apkUrl: String,
    val apkName: String,
    val apkSize: Long,
    /** Lower-case hex SHA-256 from the asset's `digest` ("sha256:…"), when GitHub provides it. */
    val sha256: String?,
    val htmlUrl: String,
) {
    val versionName get() = version.joinToString(".")

    companion object {
        const val REPO = "my-name-is-alan/iDuanJu"
        const val API = "https://api.github.com/repos/$REPO/releases?per_page=30"

        /** "v1.5.4-android" / "1.5.4-android" / "1.5.4" → [1, 5, 4]; null if there is no leading numeric version. */
        fun parseVersion(s: String): List<Int>? {
            val m = Regex("""^v?(\d+(?:\.\d+)*)""").find(s.trim()) ?: return null
            return m.groupValues[1].split('.').map { it.toIntOrNull() ?: return null }
        }

        fun compare(a: List<Int>, b: List<Int>): Int {
            for (i in 0 until maxOf(a.size, b.size)) {
                val d = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
                if (d != 0) return d
            }
            return 0
        }

        /** Newest non-draft `*-android` release that carries an .apk, from the GitHub releases list JSON. */
        fun newestAndroid(json: String): AppRelease? {
            val arr = JSONArray(json)
            val out = mutableListOf<AppRelease>()
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                val tag = r.optString("tag_name")
                if (r.optBoolean("draft") || !tag.endsWith("-android")) continue
                val ver = parseVersion(tag) ?: continue
                val assets = r.optJSONArray("assets") ?: continue
                val apk = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) } ?: continue
                out += AppRelease(
                    tag = tag, version = ver,
                    name = r.optString("name").ifBlank { tag },
                    notes = r.optString("body"),
                    apkUrl = apk.optString("browser_download_url"),
                    apkName = apk.optString("name"),
                    apkSize = apk.optLong("size"),
                    sha256 = apk.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase(),
                    htmlUrl = r.optString("html_url"),
                )
            }
            return out.maxWithOrNull { a, b -> compare(a.version, b.version) }
        }

        /** Strips markdown noise (headings, bold, code fences, the 校验 block) so the notes read cleanly in a dialog. */
        fun plainNotes(md: String): String = md.substringBefore("### 校验").lines()
            .filterNot { it.trim().startsWith("```") }
            .joinToString("\n") { it.replace(Regex("^#+\\s*"), "").replace("**", "").replace(Regex("^>\\s?"), "") }
            .replace(Regex("\n{3,}"), "\n\n").trim()
    }
}
