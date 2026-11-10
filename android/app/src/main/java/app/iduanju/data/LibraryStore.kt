package app.iduanju.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONArray
import org.json.JSONObject

/** Watch progress (iOS WatchProgress) — local only, SharedPreferences JSON. */
data class WatchProgress(
    val seriesId: String, val title: String, val cover: String,
    val episodeIndex: Int, val episodeNumber: Int, val seconds: Double, val duration: Double, val updatedAt: Long,
) {
    val fraction: Float get() = if (duration > 0) (seconds / duration).toFloat().coerceIn(0f, 1f) else 0f
    val resumeLabel: String get() = "看到第${episodeNumber}集"
}

/** iOS LibraryStore: history (continue watching) + favourites. */
class LibraryStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("library", Context.MODE_PRIVATE)
    val history = mutableStateListOf<WatchProgress>()
    val favorites = mutableStateMapOf<String, Triple<String, String, Long>>()

    init {
        runCatching {
            val a = JSONArray(sp.getString("history", "[]"))
            for (i in 0 until a.length()) a.getJSONObject(i).let {
                history.add(WatchProgress(it.getString("sid"), it.optString("title"), it.optString("cover"), it.optInt("idx"),
                    it.optInt("ep", 1), it.optDouble("sec", 0.0), it.optDouble("dur", 0.0), it.optLong("at")))
            }
            val f = JSONObject(sp.getString("fav", "{}"))
            f.keys().forEach { k -> f.getJSONObject(k).let { favorites[k] = Triple(it.optString("title"), it.optString("cover"), it.optLong("at")) } }
        }
    }

    val searchHistory = mutableStateListOf<String>().apply {
        runCatching { val a = JSONArray(sp.getString("search", "[]")); for (i in 0 until a.length()) add(a.getString(i)) }
    }
    fun addSearch(q: String) { searchHistory.remove(q); searchHistory.add(0, q); while (searchHistory.size > 20) searchHistory.removeAt(20); saveSearch() }
    fun removeSearch(q: String) { searchHistory.remove(q); saveSearch() }
    fun clearSearch() { searchHistory.clear(); saveSearch() }
    private fun saveSearch() { sp.edit().putString("search", JSONArray(searchHistory.toList()).toString()).apply() }

    val continueWatching: List<WatchProgress> get() = history.sortedByDescending { it.updatedAt }
    fun progress(sid: String): WatchProgress? = history.firstOrNull { it.seriesId == sid }

    fun record(p: WatchProgress) {
        history.removeAll { it.seriesId == p.seriesId }
        history.add(0, p)
        while (history.size > 200) history.removeAt(history.size - 1)
        save()
    }
    fun clearHistory() { history.clear(); save() }
    fun isFavorite(sid: String) = favorites.containsKey(sid)
    fun toggleFavorite(sid: String, title: String, cover: String) {
        if (favorites.remove(sid) == null) favorites[sid] = Triple(title, cover, System.currentTimeMillis())
        save()
    }

    private fun save() {
        val a = JSONArray()
        history.forEach { a.put(JSONObject().put("sid", it.seriesId).put("title", it.title).put("cover", it.cover).put("idx", it.episodeIndex)
            .put("ep", it.episodeNumber).put("sec", it.seconds).put("dur", it.duration).put("at", it.updatedAt)) }
        val f = JSONObject()
        favorites.forEach { (k, v) -> f.put(k, JSONObject().put("title", v.first).put("cover", v.second).put("at", v.third)) }
        sp.edit().putString("history", a.toString()).putString("fav", f.toString()).apply()
    }
}
