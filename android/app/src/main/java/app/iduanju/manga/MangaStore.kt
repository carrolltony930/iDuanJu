package app.iduanju.manga

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import app.iduanju.core.manga.*
import org.json.JSONArray
import org.json.JSONObject

/** iOS MangaStore: favourites, 继续阅读 (recent) and per-title progress. Offline chapters are not ported. */
class MangaStore(ctx: Context) {
    data class Progress(val item: MangaItem, val chapterId: String, val chapterTitle: String, val page: Int, val pages: Int, val at: Long)
    private val sp = ctx.getSharedPreferences("idj", Context.MODE_PRIVATE)
    val favorites = mutableStateListOf<MangaItem>()
    val progress = mutableStateMapOf<String, Progress>()
    /** Items handed between screens by id (nav routes carry only the id). */
    val cache = HashMap<String, MangaItem>()
    val providers: List<MangaProvider> = listOf(BaoziProvider(), MangaDexProvider())
    fun provider(source: String) = providers.firstOrNull { it.source == source } ?: providers[0]

    init {
        runCatching { val a = JSONArray(sp.getString("stv.manga.favorites", "[]")); for (i in 0 until a.length()) favorites.add(MangaItem.from(a.getJSONObject(i))) }
        runCatching { val a = JSONArray(sp.getString("stv.manga.progress", "[]")); for (i in 0 until a.length()) a.getJSONObject(i).let { o ->
            val it = MangaItem.from(o.getJSONObject("item")); progress[it.id] = Progress(it, o.optString("chapterId"), o.optString("chapterTitle"), o.optInt("page"), o.optInt("pages"), o.optLong("at")) } }
    }
    val recent get() = progress.values.sortedByDescending { it.at }
    fun isFavorite(m: MangaItem) = favorites.any { it.id == m.id }
    fun toggleFavorite(m: MangaItem) { if (isFavorite(m)) favorites.removeAll { it.id == m.id } else favorites.add(0, m); sp.edit().putString("stv.manga.favorites", JSONArray(favorites.map { it.json() }).toString()).apply() }
    fun record(m: MangaItem, c: MangaChapter, page: Int, of: Int) {
        progress[m.id] = Progress(m, c.chapterId, c.title, page, of, System.currentTimeMillis())
        sp.edit().putString("stv.manga.progress", JSONArray(recent.take(100).map { p -> JSONObject().put("item", p.item.json()).put("chapterId", p.chapterId).put("chapterTitle", p.chapterTitle).put("page", p.page).put("pages", p.pages).put("at", p.at) }).toString()).apply()
    }
    fun put(m: MangaItem): String { cache[m.id] = m; return m.id }
}
