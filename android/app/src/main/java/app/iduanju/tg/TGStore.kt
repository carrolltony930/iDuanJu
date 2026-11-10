package app.iduanju.tg

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import app.iduanju.core.tg.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** Subscribed public channels + cached pages (iOS TGStore, key stv.tg.channels). No channels are preset on Android. */
class TGStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("idj", Context.MODE_PRIVATE)
    val client = TGClient()
    val channels = mutableStateListOf<TGChannel>()
    val posts = mutableStateMapOf<String, List<TGPost>>()
    val before = mutableStateMapOf<String, Int?>()
    val errors = mutableStateMapOf<String, String>()
    val loading = mutableStateListOf<String>()

    init { runCatching { val a = JSONArray(sp.getString(KEY, "[]")); for (i in 0 until a.length()) channels.add(TGChannel.fromJSON(a.getJSONObject(i))) } }
    private fun save() = sp.edit().putString(KEY, JSONArray(channels.map { it.toJSON() }).toString()).apply()

    fun channel(name: String) = channels.firstOrNull { it.id == name.lowercase() }
    fun visible(adult: Boolean) = channels.filter { adult || !it.isAdult }
    fun add(raw: String, adult: Boolean = false): TGChannel? {
        val u = TGChannel.normalize(raw) ?: return null
        channel(u)?.let { return it }
        return TGChannel(u, isAdult = adult).also { channels.add(it); save() }
    }
    fun remove(id: String) { channels.removeAll { it.id == id }; posts.remove(id); save() }

    /** Merged newest-first feed. */
    fun feed(only: String?, adult: Boolean): List<TGPost> =
        visible(adult).filter { only == null || it.id == only }.flatMap { posts[it.id].orEmpty() }.filter { !it.isService }.sortedByDescending { it.date }
    fun hasMore(only: String?, adult: Boolean) = visible(adult).any { (only == null || it.id == only) && before[it.id] != null }

    suspend fun refresh(only: String?, adult: Boolean) = load(only, adult, more = false)
    suspend fun loadMore(only: String?, adult: Boolean) = load(only, adult, more = true)

    private suspend fun load(only: String?, adult: Boolean, more: Boolean) = coroutineScope {
        visible(adult).filter { (only == null || it.id == only) && it.id !in loading && (!more || before[it.id] != null) }.map { c ->
            async {
                loading.add(c.id)
                try {
                    val p = withContext(Dispatchers.IO) { client.page(c.username, if (more) before[c.id] else null) }
                    p.info?.let { i -> val idx = channels.indexOfFirst { it.id == c.id }; if (idx >= 0) { channels[idx] = channels[idx].copy(title = i.title, avatar = i.avatar, subscribers = i.subscribers, about = i.about); save() } }
                    val old = if (more) posts[c.id].orEmpty() else emptyList()
                    posts[c.id] = (old + p.posts).distinctBy { it.msgId }
                    before[c.id] = p.before
                    errors.remove(c.id)
                } catch (e: Exception) { errors[c.id] = e.message ?: "加载失败" } finally { loading.remove(c.id) }
            }
        }.awaitAll()
    }

    companion object { const val KEY = "stv.tg.channels" }
}
