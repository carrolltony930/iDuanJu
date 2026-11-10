package app.iduanju.huangguo

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.iduanju.IdjApp
import app.iduanju.core.huangguo.CoverCrypto
import app.iduanju.core.huangguo.HuangguoClient
import app.iduanju.core.huangguo.HuangguoSub
import app.iduanju.ui.home.FeedGrid
import app.iduanju.ui.theme.IdjThemeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.ResponseBody.Companion.toResponseBody

/** SharedPreferences-backed map for HuangguoClient's mirror memory (stv.huangguo.*). */
class PrefsMap(ctx: Context) : AbstractMutableMap<String, String>() {
    private val sp = ctx.getSharedPreferences("idj", Context.MODE_PRIVATE)
    override val entries: MutableSet<MutableMap.MutableEntry<String, String>>
        get() = sp.all.filter { it.key.startsWith("stv.huangguo.") && it.value is String }.map { java.util.AbstractMap.SimpleEntry(it.key, it.value as String) as MutableMap.MutableEntry<String, String> }.toMutableSet()
    override fun get(key: String): String? = sp.getString(key, null)
    override fun put(key: String, value: String): String? { val o = get(key); sp.edit().putString(key, value).apply(); return o }
    override fun remove(key: String): String? { val o = get(key); sp.edit().remove(key).apply(); return o }
}

/** Coil/OkHttp interceptor: decodes 黄果 obfuscated covers (iOS CoverStore + CoverCrypto). */
object CoverDecryptInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val r = chain.proceed(chain.request())
        val kind = CoverCrypto.kind(chain.request().url.toString())
        if (kind == CoverCrypto.Kind.plain || !r.isSuccessful) return r
        val body = r.body ?: return r
        val bytes = runCatching { CoverCrypto.decrypt(body.bytes(), kind) }.getOrElse { return r.newBuilder().code(502).body(ByteArray(0).toResponseBody()).build() }
        return r.newBuilder().body(bytes.toResponseBody(body.contentType())).removeHeader("Content-Length").build()
    }
}

/**
 * 「黄果」 home page. Adult source: shown only once the adult gate is unlocked (我的 → 片源管理).
 * Chips: 视频站 categories + AI 站 channels + App 源 tabs (iOS splits them across 黄果 / AI剧 pages).
 */
@Composable
fun HuangguoFeedPage(onOpen: (String) -> Unit) {
    val c = IdjThemeColors
    if (!IdjApp.instance.cms.adultUnlocked) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text("黄果为成人片源，默认隐藏。\n在「我的 → 片源管理」开启成人内容后显示。", color = c.secondary, fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 20.sp)
        }
        return
    }
    val hg = IdjApp.instance.huangguo
    val base = HuangguoClient.videoChannels.map { "video/" + it.slug to it.title } + HuangguoClient.aiChannels.map { "ai/" + it.slug to "AI·" + it.title }
    var cats by remember { mutableStateOf(base) }
    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) { hg.legacyChannels() } }.getOrNull()?.let { tabs -> cats = base + tabs.map { "legacy/" + it.slug to "App·" + it.title } }
    }
    FeedGrid("huangguo", cats, { cat, page ->
        val sub = HuangguoSub.valueOf(cat.substringBefore('/')); val slug = cat.substringAfter('/')
        val ch = (HuangguoClient.videoChannels + HuangguoClient.aiChannels).firstOrNull { it.sub == sub && it.slug == slug }
            ?: app.iduanju.core.huangguo.HuangguoChannel(sub, slug, slug)
        hg.list(ch, page)
    }, onOpen)
}
