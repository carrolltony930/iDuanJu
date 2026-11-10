package app.iduanju.core.hongguo

import app.iduanju.core.util.Bytes
import app.iduanju.core.util.GoURL
import app.iduanju.core.util.J
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** One time-coded official danmaku (iOS HGDanmaku). */
data class HGDanmaku(val time: Double, var text: String, var likes: Int = 0, var color: String = "#FFFFFF", val mode: Mode = Mode.scroll, var count: Int = 1) {
    enum class Mode { scroll, top, bottom }
    val id: String get() = String.format("%.3f|", time) + text
}

data class HGDanmakuResult(val items: List<HGDanmaku>, val truncated: Boolean = false, val error: String = "") {
    val notice: String get() = when { error.isNotEmpty() -> error; truncated -> "弹幕较多，仅载入前 ${items.size} 条"; items.isEmpty() -> "本集暂无弹幕"; else -> "" }
}

/** Signing for `/novel/commentapi/comment/list/{vid}/v1/` (X-Gorgon nonce variant / X-Ladon / X-Argus) — byte-for-byte port of iOS HGCommentSign. */
object HGCommentSign {
    const val NONCE_BYTES = 11
    private fun rotl64(x: ULong, n: Int): ULong { val s = ((n % 64) + 64) % 64; return if (s == 0) x else (x shl s) or (x shr (64 - s)) }
    private fun rotr64(x: ULong, n: Int) = rotl64(x, -n)
    private fun rotl8(x: Int, n: Int): Int { val s = n and 7; return if (s == 0) x else ((x shl s) or (x shr (8 - s))) and 0xff }
    private fun reverse8(x: Int): Int {
        var v = x and 0xff
        v = ((v and 0xF0) shr 4) or ((v and 0x0F) shl 4)
        v = ((v and 0xCC) shr 2) or ((v and 0x33) shl 2)
        v = ((v and 0xAA) shr 1) or ((v and 0x55) shl 1)
        return v and 0xff
    }
    fun pad(d: ByteArray): ByteArray { val p = 16 - d.size % 16; return d + ByteArray(p) { p.toByte() } }
    private fun uvarint(value: ULong): ByteArray {
        var v = value; val out = ArrayList<Byte>()
        while (true) { val b = (v and 0x7FUL).toInt(); v = v shr 7; if (v != 0UL) out.add((b or 0x80).toByte()) else { out.add(b.toByte()); return out.toByteArray() } }
    }

    fun newDeviceId(): String = (1_000_000_000_000_000_000UL + (java.security.SecureRandom().nextLong().toULong() % 8_000_000_000_000_000_000UL)).toString()

    fun baseParams(did: String, iid: String) = mapOf(
        "aid" to "8662", "app_name" to "novelread", "version_code" to "73532", "version_name" to "7.3.5.32",
        "manifest_version_code" to "73532", "update_version_code" to "73532", "channel" to "update_64",
        "device_platform" to "android", "os" to "android", "ssmix" to "a", "device_type" to "25053RT47C", "device_brand" to "Redmi",
        "language" to "zh", "os_api" to "36", "os_version" to "16", "resolution" to "1280*2772", "dpi" to "520", "ac" to "wifi",
        "device_id" to did, "iid" to iid)

    fun buildQuery(stampMs: Long, did: String, iid: String): String = GoURL.encode(baseParams(did, iid) + ("_rticket" to stampMs.toString()))

    fun xGorgon(rawQuery: String, stamp: Long, nonce: ByteArray): String {
        val n0 = nonce[0].toInt() and 0xff; val n1 = nonce[1].toInt() and 0xff
        val head2 = (((n0 % 7) + 1) shl 5) and 0xff
        val head3 = 0xE0 or (n1 and 0x0F)
        val seed = IntArray(20)
        val digest = Bytes.md5(rawQuery)
        for (i in 0 until 4) seed[i] = digest[i].toInt() and 0xff
        seed[12] = 0; seed[13] = 1; seed[14] = 7; seed[15] = 4
        val tb = Bytes.u32BE(stamp.toUInt())
        for (i in 0 until 4) seed[16 + i] = tb[i].toInt() and 0xff
        val key = intArrayOf(0x4A, 0x00, 0x16, head3, 0x47, 0x6C, 0x00, head2)
        val box = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) { j = (j + box[i] + key[i and 7]) and 0xFF; box[i] = box[j] }
        j = 0
        for (offset in 0 until 20) {
            val i = (offset + 1) and 0xFF
            j = (j + box[i]) and 0xFF
            box[i] = box[j]
            seed[offset] = seed[offset] xor box[(box[i] + box[j]) and 0xFF]
        }
        for (i in 0 until 20) seed[i] = reverse8(rotl8(seed[i], 4) xor seed[(i + 1) % 20]) xor 0xEB
        return Bytes.hex(byteArrayOf(0x84.toByte(), 0x04, head2.toByte(), head3.toByte(), 0, 0) + ByteArray(20) { seed[it].toByte() })
    }

    fun ladon(stamp: Long, prefix: ByteArray, aid: String = "8662"): String {
        val digest = Bytes.hex(Bytes.md5(prefix + aid.toByteArray())).toByteArray()
        var left = Bytes.readU64LE(digest, 0); var right = Bytes.readU64LE(digest, 8)
        val queue = ArrayDeque(listOf(Bytes.readU64LE(digest, 16), Bytes.readU64LE(digest, 24)))
        val keys = ArrayList<ULong>()
        for (i in 0 until 34) {
            keys.add(left)
            val mixed = (rotr64(right, 8) + left) xor i.toULong()
            queue.addLast(mixed)
            left = mixed xor rotl64(left, 3)
            right = queue.removeFirst()
        }
        val data = pad("$stamp-1611921764-3019".toByteArray())
        var off = 0
        while (off < data.size) {
            var bl = Bytes.readU64LE(data, off); var br = Bytes.readU64LE(data, off + 8)
            for (k in keys) { br = k xor (bl + rotr64(br, 8)); bl = br xor rotl64(bl, 3) }
            Bytes.writeU64LE(bl, data, off); Bytes.writeU64LE(br, data, off + 8)
            off += 16
        }
        return Base64.getEncoder().encodeToString(prefix + data)
    }

    private val argusKey = Bytes.fromHex("ac1adaae95a7af94a5114ab3b3a97dd80050aa0a39314c40528caec95256c28c")!!

    fun argus(rawQuery: String, stamp: Long, nonce: ByteArray): String {
        val key = argusKey
        val parts = java.io.ByteArrayOutputStream()
        fun varint(field: Long, value: ULong) { parts.write(uvarint(field.toULong() * 8UL)); parts.write(uvarint(value)) }
        fun bytes(field: Long, v: ByteArray) { parts.write(uvarint(field.toULong() * 8UL + 2UL)); parts.write(uvarint(v.size.toULong())); parts.write(v) }
        fun nb(i: Int) = (nonce[i].toInt() and 0xff).toLong()
        val n3 = nb(6) or (nb(7) shl 8) or (nb(8) shl 16) or (nb(9) shl 24)
        varint(1, 0x20200929UL * 2UL)
        varint(2, 2UL)
        varint(3, n3.toULong())
        bytes(4, "3019".toByteArray())
        bytes(6, "1611921764".toByteArray())
        bytes(7, "6.8.1.32".toByteArray())
        bytes(8, "v04.07.01-ml-android".toByteArray())
        varint(9, 135135744UL)
        bytes(10, ByteArray(8))
        varint(12, stamp.toULong() * 2UL)
        val bodyHash = Medusa.sm3(ByteArray(16))
        val queryHash = Medusa.sm3(rawQuery.toByteArray())
        bytes(13, bodyHash.copyOfRange(0, 6))
        bytes(14, queryHash.copyOfRange(0, 6))
        val nested = java.io.ByteArrayOutputStream()
        nested.write(0x08); nested.write((2 + (nb(10).toInt() % 49) * 2) and 0xff)
        for (f in listOf(2UL, 3UL, 4UL)) { nested.write(uvarint(f * 8UL)); nested.write(uvarint(1388734UL)) }
        bytes(15, nested.toByteArray())
        bytes(20, "none".toByteArray())
        varint(21, 738UL)

        val data = pad(parts.toByteArray())
        val derived = Medusa.sm3(key + byteArrayOf(0x3C, 0xCC.toByte(), 0x56, 0x7B) + key)
        val rounds = ULongArray(72)
        for (i in 0 until 4) rounds[i] = Bytes.readU64LE(derived, i * 8)
        val c = 0x3DC94C3A046D678BUL
        for (i in 4 until 72) {
            var mixed = rotr64(rounds[i - 1], 3) xor rounds[i - 3]
            mixed = mixed xor rotr64(mixed, 1)
            rounds[i] = rounds[i - 4].inv() xor mixed xor ((c shr ((i - 4) % 62)) and 1UL) xor 3UL
        }
        var off = 0
        while (off < data.size) {
            var l = Bytes.readU64LE(data, off); var r = Bytes.readU64LE(data, off + 8)
            for (k in rounds) { val next = l xor (rotl64(r, 1) and rotl64(r, 8)) xor rotl64(r, 2) xor k; l = r; r = next }
            Bytes.writeU64LE(l, data, off); Bytes.writeU64LE(r, data, off + 8)
            off += 16
        }
        val xor = byteArrayOf(0xD0.toByte(), 0x4F, 0xFD.toByte(), 0xFF.toByte(), 0xD0.toByte(), 0x4F, 0xFD.toByte(), 0xFF.toByte())
        for (i in data.indices) data[i] = (data[i].toInt() xor xor[i % 8].toInt()).toByte()
        val reversed = (xor + data).reversedArray()
        val container = pad(byteArrayOf(0xA6.toByte(), 0xE7.toByte(), 0x83.toByte(), 0xEE.toByte(), 0x70, 0x01, 0x10, 0x09, 0x18) + reversed + byteArrayOf(0x56, 0x7B))
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(Bytes.md5(key.copyOfRange(0, 16)), "AES"), IvParameterSpec(Bytes.md5(key.copyOfRange(16, 32))))
        return Base64.getEncoder().encodeToString(byteArrayOf(0x3C, 0xCC.toByte()) + cipher.doFinal(container))
    }

    /** nonce: 11 bytes — [0:2] Gorgon, [2:6] Ladon, [6:11] Argus. */
    fun headers(rawQuery: String, stamp: Long, nonce: ByteArray): Map<String, String> = mapOf(
        "X-Gorgon" to xGorgon(rawQuery, stamp, nonce), "X-Khronos" to stamp.toString(), "X-SS-Req-Ticket" to (stamp * 1000).toString(),
        "X-Ladon" to ladon(stamp, nonce.copyOfRange(2, 6)), "X-Argus" to argus(rawQuery, stamp, nonce))
}

/** Official danmaku for one 红果 episode (iOS HGDanmakuClient): book_id = seriesId, group_id = vid. Identity is a stable anonymous pair per install. */
class HGDanmakuClient(private val identity: () -> Pair<String, String>) {
    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    private val cache = LinkedHashMap<String, Pair<Long, HGDanmakuResult>>()
    @Volatile var fixture: ((String) -> String?)? = null

    fun fetch(seriesId: String, vid: String, fresh: Boolean = false): HGDanmakuResult {
        if (seriesId.isEmpty() || !seriesId.all { it.isDigit() } || vid.isEmpty() || !vid.all { it.isDigit() }) return HGDanmakuResult(emptyList(), error = "仅红果来源的剧集支持弹幕")
        val key = "$seriesId:$vid"
        synchronized(cache) { cache[key]?.let { (at, r) -> if (!fresh && System.currentTimeMillis() - at < (if (r.error.isEmpty()) 300_000 else 15_000)) return r } }
        val r = load(seriesId, vid)
        synchronized(cache) { cache[key] = System.currentTimeMillis() to r; if (cache.size > 64) cache.remove(cache.keys.first()) }
        return r
    }

    private fun load(seriesId: String, vid: String): HGDanmakuResult {
        val collected = ArrayList<HGDanmaku>(); val seen = HashSet<String>()
        var start = 0L; var duration = WINDOW_MS; var empty = 0; var truncated = false; var failure = ""
        for (page in 0 until MAX_PAGES) {
            if (Thread.currentThread().isInterrupted) break
            if (page > 0) Thread.sleep(120)
            val payload = try { requestWindow(vid, seriesId, start, duration) } catch (e: Exception) {
                failure = if (collected.isEmpty()) "弹幕接口暂不可用（已用官方签名请求），请稍后重试" else "后续弹幕加载中断，已保留已获取的部分"
                lastError = e.message; break
            }
            val p = parsePage(payload, vid)
            if (p.items.isEmpty()) empty++ else empty = 0
            for (it in p.items) {
                it.text = cleanControl(it.text)
                if (it.text.isEmpty() || it.text.length > MAX_TEXT) continue
                if (!seen.add(it.id)) continue
                collected.add(it)
                if (collected.size >= MAX_ITEMS) break
            }
            if (collected.size >= MAX_ITEMS) { truncated = true; break }
            val next = p.nextMs
            if (next == null) { if (p.hasMore == true) { truncated = true; if (collected.isEmpty()) failure = "弹幕接口未返回有效分页，请稍后重试" }; break }
            if (next <= start || next > 604_800_000 || empty >= MAX_EMPTY || page == MAX_PAGES - 1) { truncated = true; if (collected.isEmpty()) failure = "弹幕接口未返回有效分页，请稍后重试"; break }
            start = next
            duration = minOf(maxOf(duration, start + WINDOW_MS), MAX_WINDOW_MS)
        }
        collected.sortBy { it.time }
        return HGDanmakuResult(collected, truncated, failure)
    }

    @Volatile var lastError: String? = null

    private fun requestWindow(vid: String, bookId: String, start: Long, duration: Long): JSONObject {
        val stampMs = System.currentTimeMillis(); val stamp = stampMs / 1000
        val nonce = Bytes.random(HGCommentSign.NONCE_BYTES)
        val (did, iid) = identity()
        val q = HGCommentSign.buildQuery(stampMs, did, iid)
        val url = "$BASE/novel/commentapi/comment/list/$vid/v1/?$q"
        val body = JSONObject().put("comment_source", 601).put("server_channel", 1000).put("group_id", vid).put("group_type", 30).put("comment_type", 20)
            .put("sort", 1).put("count", PAGE_COUNT).put("cursor", "").put("aid", 8662).put("compliance_status", 0)
            .put("business_param", JSONObject().put("book_id", bookId).put("start_offset_time", start).put("playlet_item_duration", duration)
                .put("need_danmaku_guide_type", JSONArray(listOf(1, 3, 4, 2))))
        val text = fixture?.invoke(url) ?: run {
            val rb = Request.Builder().url(url).post(body.toString().toByteArray().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("User-Agent", UA).header("Accept", "application/json").header("X-XS-From-Web", "0").header("Sdk-Version", "2")
                .header("Comment-Source", "601").header("Server-Channel", "1000").header("X-SS-STUB", "")
            HGCommentSign.headers(q, stamp, nonce).forEach { (k, v) -> rb.header(k, v) }
            http.newCall(rb.build()).execute().use { r ->
                if (r.code != 200) throw IllegalStateException("danmaku HTTP ${r.code}")
                r.body?.string()?.takeIf { it.isNotEmpty() } ?: throw IllegalStateException("danmaku 空响应")
            }
        }
        val j = J.parse(text)
        if (J.str(j.opt("code")) != "0" || J.dict(j.opt("data")).opt("data_list") !is JSONArray) throw IllegalStateException("danmaku code=${J.str(j.opt("code"))}${J.str(j.opt("Code"))} ${J.str(j.opt("message"))}")
        return j
    }

    data class Page(val items: List<HGDanmaku>, val nextMs: Long?, val hasMore: Boolean?)

    companion object {
        private const val BASE = "https://api5-normal-sinfonlineb.fqnovel.com"
        private const val UA = "com.phoenix.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)"
        private const val PAGE_COUNT = 90; private const val MAX_PAGES = 40; const val MAX_ITEMS = 3000; private const val MAX_TEXT = 200
        private const val WINDOW_MS = 3_600_000L; private const val MAX_WINDOW_MS = 24 * 3_600_000L; private const val MAX_EMPTY = 2

        fun parsePage(payload: JSONObject, vid: String): Page {
            val data = J.dict(payload.opt("data"))
            var nextMs: Long? = null; var hasMore: Boolean? = null
            J.str(J.dict(data.opt("extra")).opt("next_query_danmaku_list_time")).trim().toLongOrNull()?.let { nextMs = it }
            val info = J.dict(data.opt("common_list_info"))
            info.opt("has_more")?.let { f -> hasMore = if (f is Boolean) f else J.str(f).lowercase().takeIf { it.isNotEmpty() }?.let { it in setOf("1", "true", "yes") } }
            var cursor = J.dict(info.opt("cursor"))
            if (cursor.length() == 0) (info.opt("cursor") as? String)?.trim()?.takeIf { it.startsWith("{") }?.let { s -> runCatching { cursor = JSONObject(s) } }
            for (k in listOf("end_offset_time", "next_offset_time", "offset")) {
                val v = cursor.opt(k) ?: continue
                val c = J.int64(v)
                if (c == 0L && J.str(v) != "0") continue
                if (nextMs == null || c > nextMs!!) nextMs = c
                break
            }
            if (hasMore == false) nextMs = null
            val items = ArrayList<HGDanmaku>()
            for (r in J.arr(data.opt("data_list"))) {
                val row = J.dict(r); val comment = J.dict(row.opt("comment"))
                if (comment.length() == 0) continue
                val common = J.dict(comment.opt("common"))
                val gid = J.str(common.opt("group_id"))
                if (gid.isNotEmpty() && gid != vid) continue
                if (J.str(common.opt("status")) != "1") continue
                val content = J.dict(common.opt("content"))
                val expand = J.dict(comment.opt("expand"))
                val tRaw = expand.opt("offset_time") ?: comment.opt("offset_time")
                if (tRaw == null || tRaw == JSONObject.NULL || J.str(tRaw).isEmpty()) continue
                val ms = J.double(tRaw)
                if (ms < 0 || ms / 1000 > 604_800) continue
                var color = "#FFFFFF"
                run outer@{ for (h in listOf(content, common, expand, comment)) for (k in listOf("color", "font_color", "text_color", "col")) if (h.has(k)) { color = normalizeColor(h.opt(k)); return@outer } }
                val likes = maxOf(0, J.int(J.dict(comment.opt("stat")).opt("digg_count")))
                items.add(HGDanmaku(ms / 1000, J.str(content.opt("text")), likes, color, mode(row, comment)))
            }
            if ((nextMs ?: 1) <= 0) nextMs = null
            return Page(items, nextMs, hasMore)
        }

        fun normalizeColor(v: Any?): String {
            if (v is Number && v !is Boolean) { val i = v.toLong(); return if (i < 0) "#FFFFFF" else String.format("#%06X", i and 0xFFFFFF) }
            var s = (v as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return "#FFFFFF"
            if (s.startsWith("#")) {
                s = s.drop(1); if (s.length == 3) s = s.map { "$it$it" }.joinToString("")
                return if (s.length == 6 && s.toLongOrNull(16) != null) "#" + s.uppercase() else "#FFFFFF"
            }
            if (s.lowercase().startsWith("0x")) s.drop(2).toULongOrNull(16)?.let { return String.format("#%06X", (it and 0xFFFFFFUL).toLong()) }
            s.toULongOrNull()?.let { return String.format("#%06X", (it and 0xFFFFFFUL).toLong()) }
            return "#FFFFFF"
        }

        private fun mode(vararg holders: JSONObject): HGDanmaku.Mode {
            for (h in holders) for (k in listOf("mode", "danmaku_type", "position", "fix_type", "danmaku_position")) {
                val v = h.opt(k) ?: continue
                val t = J.str(v).lowercase()
                if (t in setOf("top", "2", "fix_top", "顶部")) return HGDanmaku.Mode.top
                if (t in setOf("bottom", "3", "fix_bottom", "底部")) return HGDanmaku.Mode.bottom
            }
            return HGDanmaku.Mode.scroll
        }

        fun cleanControl(s: String): String = String(s.codePoints().map { if (it < 0x20 || it == 0x7F || it == 0x2028 || it == 0x2029) ' '.code else it }.toArray(), 0, s.codePointCount(0, s.length)).trim()
    }
}

object DanmakuText {
    private val emoji = mapOf(
        "笑哭" to "😂", "偷笑" to "🤭", "爱慕" to "😍", "捂脸" to "🤦", "送心" to "💗", "鼓掌" to "👏", "害羞" to "😊", "大哭" to "😭",
        "呲牙" to "😁", "微笑" to "🙂", "机智" to "🤓", "赞" to "👍", "点赞" to "👍", "比心" to "🫶", "爱心" to "❤️", "心" to "❤️",
        "流泪" to "😢", "哭" to "😢", "可怜" to "🥺", "生气" to "😠", "发怒" to "😡", "惊讶" to "😮", "震惊" to "😱", "疑问" to "❓",
        "思考" to "🤔", "无语" to "😑", "白眼" to "🙄", "坏笑" to "😏", "得意" to "😎", "酷" to "😎", "憨笑" to "😄", "大笑" to "😆",
        "笑" to "😄", "开心" to "😄", "亲亲" to "😘", "飞吻" to "😘", "色" to "😍", "晕" to "😵", "抓狂" to "😫", "吐血" to "🤮",
        "打脸" to "🤕", "抱抱" to "🤗", "拥抱" to "🤗", "握手" to "🤝", "加油" to "💪", "强" to "💪", "玫瑰" to "🌹", "花" to "🌸",
        "666" to "6️⃣6️⃣6️⃣", "OK" to "👌", "ok" to "👌", "耶" to "✌️", "吃瓜" to "🍉", "狗头" to "🐶", "火" to "🔥", "灵机一动" to "💡",
        "尴尬" to "😅", "汗" to "😓", "擦汗" to "😓", "困" to "😪", "睡" to "😴", "再见" to "👋", "拜拜" to "👋", "鄙视" to "😒",
        "委屈" to "🥺", "难过" to "😞", "撇嘴" to "😕", "调皮" to "😜", "吐舌" to "😛", "奸笑" to "😏", "皱眉" to "😣", "泪奔" to "😭")
    fun display(s: String): String {
        if (!s.contains('[')) return s
        val out = StringBuilder(); var rest = s
        while (true) {
            val open = rest.indexOf('['); if (open < 0) break
            out.append(rest, 0, open)
            val close = rest.indexOf(']', open)
            if (close < 0 || close - open > 7) { out.append(rest.substring(open)); return out.toString() }
            val code = rest.substring(open + 1, close)
            out.append(emoji[code] ?: "[$code]")
            rest = rest.substring(close + 1)
        }
        return out.append(rest).toString()
    }
}

/** Merge duplicate / near-duplicate comments close together (iOS DanmakuMerger). */
object DanmakuMerger {
    fun normalize(s: String): String {
        val out = StringBuilder()
        val stripped = s.replace(Regex("\\[[^\\[\\]]{1,6}\\]"), "")
        stripped.lowercase().codePoints().forEach { cp ->
            val t = Character.getType(cp)
            if (Character.isWhitespace(cp) || t in PUNCT_SYM) return@forEach
            val ch = String(Character.toChars(cp))
            if (out.length >= 2 * ch.length && out.endsWith(ch + ch)) return@forEach
            out.append(ch)
        }
        return out.toString()
    }
    private val PUNCT_SYM = setOf(Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
        Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION,
        Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL, Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL).map { it.toInt() }.toSet()
    private fun bigrams(s: String): Set<String> = if (s.length < 2) setOf(s) else (0 until s.length - 1).map { s.substring(it, it + 2) }.toSet()
    private fun similar(a: String, b: String, ga: Set<String>, gb: Set<String>): Boolean {
        if (a == b) return true
        val short = minOf(a.length, b.length); if (short < 4) return false
        if (a.contains(b) || b.contains(a)) return short.toDouble() / maxOf(a.length, b.length) >= 0.75
        val inter = ga.intersect(gb).size; val uni = (ga + gb).size
        return uni > 0 && inter.toDouble() / uni >= 0.8
    }
    fun merge(items: List<HGDanmaku>, window: Double = 12.0): List<HGDanmaku> {
        class G(val item: HGDanmaku, val key: String, val grams: Set<String>, var best: Int)
        var open = ArrayList<G>(); val out = ArrayList<HGDanmaku>()
        for (it0 in items.sortedBy { it.time }) {
            val it = it0.copy()
            val keep = ArrayList<G>(); for (g in open) if (it.time - g.item.time > window) out.add(g.item) else keep.add(g); open = keep
            val key = normalize(it.text)
            if (key.isEmpty()) { out.add(it); continue }
            val grams = bigrams(key)
            val g = open.firstOrNull { similar(it.key, key, it.grams, grams) }
            if (g != null) {
                g.item.count += it.count; g.item.likes += it.likes
                if (it.likes > g.best) { g.best = it.likes; g.item.text = it.text; g.item.color = it.color }
                continue
            }
            open.add(G(it, key, grams, it.likes))
        }
        open.forEach { out.add(it.item) }
        return out.sortedBy { it.time }
    }
}

/** Lane scheduler (iOS DanmakuLayout / hongguo-open-player prioritizeDanmakuItems): collision-free tracks; higher-liked comments evict lower-liked blockers. */
object DanmakuLayout {
    data class Placed(val item: HGDanmaku, val text: String, val badge: String, val lane: Int, val width: Float)

    fun estimateWidth(s: String, font: Float): Float {
        var w = 8f
        s.codePoints().forEach { u ->
            when {
                u in 0x20..0x7E -> w += font * 0.56f
                u >= 0x1F000 || Character.getType(u) == Character.OTHER_SYMBOL.toInt() && u >= 0x2600 -> w += font * 1.15f
                u == 0xFE0F || u == 0x200D || u == 0x20E3 -> {}
                else -> w += font
            }
        }
        return w
    }

    fun requiredGap(wp: Float, wn: Float, screen: Float, duration: Double): Double =
        maxOf(duration * (wp + 12) / (screen + wp), duration * (wn + 12) / (screen + wn))

    fun layout(items: List<HGDanmaku>, rows0: Int, screen: Float, font: Float, duration: Double, likesPriority: Boolean): List<Placed> {
        val rows = maxOf(1, rows0)
        class C(val item: HGDanmaku, val text: String, val badge: String, val width: Float, val idx: Int)
        val cs = items.mapIndexed { i, it ->
            val text = DanmakuText.display(it.text); val badge = if (it.count > 1) "×${it.count}" else ""
            C(it, text, badge, estimateWidth(text, font) + if (badge.isEmpty()) 0f else estimateWidth(badge, font * 0.8f) + 6, i)
        }.sortedWith(compareBy<C> { it.item.time }.thenByDescending { it.item.likes }.thenBy { it.idx })
        val tracks = List(rows) { ArrayList<C>() }
        for (c in cs) {
            var free = -1; var bestSlack = Double.POSITIVE_INFINITY
            for (k in 0 until rows) {
                val prev = tracks[k].lastOrNull() ?: run { free = k; null } ?: break
                val gap = requiredGap(prev.width, c.width, screen, duration)
                if (c.item.time >= prev.item.time + gap) { val slack = c.item.time - (prev.item.time + gap); if (slack < bestSlack) { bestSlack = slack; free = k } }
            }
            if (free >= 0) { tracks[free].add(c); continue }
            if (!likesPriority || c.item.likes + c.item.count <= 1) continue
            val score = c.item.likes + c.item.count - 1
            var best = -1; var minScore = score; var maxBlock = Double.NEGATIVE_INFINITY
            for (k in 0 until rows) {
                val inc = tracks[k].lastOrNull() ?: continue
                val incScore = inc.item.likes + inc.item.count - 1
                if (incScore >= score) continue
                if (tracks[k].size >= 2) { val before = tracks[k][tracks[k].size - 2]; if (c.item.time < before.item.time + requiredGap(before.width, c.width, screen, duration)) continue }
                val block = inc.item.time + requiredGap(inc.width, c.width, screen, duration) - c.item.time
                if (incScore < minScore || (incScore == minScore && block > maxBlock)) { minScore = incScore; maxBlock = block; best = k }
            }
            if (best >= 0) { tracks[best].removeAt(tracks[best].size - 1); tracks[best].add(c) }
        }
        return tracks.flatMapIndexed { k, t -> t.map { Placed(it.item, it.text, it.badge, k, it.width) } }.sortedBy { it.item.time }
    }
}
