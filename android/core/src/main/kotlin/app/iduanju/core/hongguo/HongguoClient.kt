package app.iduanju.core.hongguo

import app.iduanju.core.util.Bytes
import app.iduanju.core.util.GoURL
import app.iduanju.core.util.J
import app.iduanju.core.util.ProviderException
import app.iduanju.core.util.u
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import java.io.ByteArrayOutputStream

/**
 * Direct-to-upstream port of iOS HongguoClient (rank / detail / resolve).
 * Soft reject = HTTP 200 + 0 bytes. Player session uses Proxy.NO_PROXY.
 */
object HGNet {
    @Volatile var playerUsesSystemProxy: Boolean = false
}

class HongguoClient(
    val dataDir: File,
    private val http: OkHttpClient = HGDevice.defaultHttp(),
) {
    private val playerHttpDirect: OkHttpClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
    private val playerHttpSystem: OkHttpClient = playerHttpDirect.newBuilder().proxy(null).build()
    /** iOS parity: the player session bypasses proxies. [HGNet.playerUsesSystemProxy] is a developer escape hatch (emulator egress). */
    private val playerHttp: OkHttpClient get() = if (HGNet.playerUsesSystemProxy) playerHttpSystem else playerHttpDirect

    init { HGDetailExtras.parser = this }

    @Volatile private var device: HGDevice? = null
    /** Diagnostics: which identity served the last successful pure resolve (override / registered / mint). */
    @Volatile var lastPureRoute: String = ""
    private val vmDeviceId = vmID()
    private val vmInstallId = vmID()
    private val resolveCache = LinkedHashMap<String, Pair<HGResolvedVideo, Long>>()

    init {
        dataDir.mkdirs()
        HGPlayerDevice.storageDir = dataDir
    }

    companion object {
        private const val UA = HGDevice.ua
        private const val URL_BATCH = "https://api5-normal-sinfonlinea.fqnovel.com/novel/player/multi_video_model/player/v1?"
        private const val URL_BATCH_ABOFF = "https://api5-normal-sinfonlinea.fqnovel.com/novel/player/multi_video_model/v1?"
        private const val URL_DETAIL = "https://api5-normal-sinfonlinea.fqnovel.com/novel/player/multi_video_detail/preload/v1?"
        private const val URL_BOOK_DIR = "https://api5-normal-sinfonlinea.fqnovel.com/reading/bookapi/directory/all_items/v?"
        private const val URL_CATEGORY = "https://api5-normal-sinfonlineb.fqnovel.com/reading/distribution/category/landpage/v:version/?"
        const val URL_RANK_CELL = "https://api5-normal-sinfonlinec.fqnovel.com/reading/bookapi/bookmall/cell/change/v:version/?"
        internal const val URL_PLAN = "https://api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/plan/v?"
        private const val URL_VIDEO_MODEL = "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/video_model/v1/"
        private const val VIDEO_MODEL_UA =
            "com.phoenix.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)"
        private const val PURE_HOST = "api5-normal-sinfonlinea.fqnovel.com"
        private const val PURE_PATH = "/novel/player/video_model/v1/"
        private const val PURE_VN = "7.3.9.32"
        private const val PURE_VC = "73932"
        private const val PURE_UA =
            "com.phoenix.read/73932 (Linux; U; Android 14; zh_CN; 23127PN0CC; Build/UKQ1.230804.001; Cronet/TTNetVersion:8d40f833 2026-03-03 QuicVersion:21ac1950 2025-11-18)"
        private const val BYTEVC2 = "该集仅返回 ByteVC2 编码，Android 无法解码"
        private const val RESOLVE_TTL_MS = 180_000L
        @Volatile private var preferredBatchABOff = true
        private fun vmID(): String { val r = java.util.concurrent.ThreadLocalRandom.current().nextLong(0, 8_000_000_000_000_000_000L); return (1_000_000_000_000_000_000L + kotlin.math.abs(r)).toString() }
        fun nowMs() = System.currentTimeMillis()

        private val rankSelected = mapOf(
            HGRankGenre.all to "all", HGRankGenre.human to "human",
            HGRankGenre.comic to "comic_series_rank", HGRankGenre.ai to "ai_playlet",
        )
        private val rankSubIds = mapOf(
            HGRankGenre.all to mapOf(
                HGRankSub.subscribe to "ranklist_subscribe", HGRankSub.recommend to "ranklist_hot_sc",
                HGRankSub.hot to "ranklist_hot_play_sc", HGRankSub.zhenguo to "ranklist_prestige",
                HGRankSub.new to "ranklist_new_rank_sc", HGRankSub.mustWatch to "ranklist_must_watch",
                HGRankSub.followed to "ranklist_followed", HGRankSub.hotSearch to "ranklist_hot_search_sc",
            ),
            HGRankGenre.human to mapOf(
                HGRankSub.recommend to "human_hot_sc", HGRankSub.hot to "human_hot_play",
                HGRankSub.new to "human_new_rank", HGRankSub.hotSearch to "human_hot_search",
                HGRankSub.mustWatch to "human_must_watch", HGRankSub.followed to "human_followed",
            ),
            HGRankGenre.comic to mapOf(
                HGRankSub.recommend to "comic_series_hot_rank", HGRankSub.hot to "comic_series_hot_play",
                HGRankSub.new to "comic_series_new_rank", HGRankSub.hotSearch to "comic_series_hot_search",
            ),
            HGRankGenre.ai to mapOf(
                HGRankSub.recommend to "ai_playlet_hot_sc", HGRankSub.hot to "ai_playlet_hot_play",
                HGRankSub.new to "ai_playlet_new_rank", HGRankSub.mustWatch to "ai_playlet_must_watch",
                HGRankSub.followed to "ai_playlet_followed", HGRankSub.hotSearch to "ai_playlet_hot_search",
            ),
        )
        private val qualityMap = mapOf(1 to "1080p", 10 to "720p", 20 to "540p", 30 to "480p", 40 to "360p")
    }

    private class PlayerEmptyBody : Exception("HTTP 200 empty (soft reject)")

    private val deviceLock = Any()

    /** Serialized: the player and next-episode prefetch run on different threads and must not register twice. */
    fun ensureDevice(): HGDevice {
        device?.let { return it }
        synchronized(deviceLock) {
            device?.let { return it }
            HGDevice.load(dataDir)?.let { device = it; return it }
            // device_register occasionally answers device_id 0 (seen right after a burst); one short retry.
            val d = try { HGDevice.register(http, dataDir) } catch (e: Exception) {
                Thread.sleep(2000); HGDevice.register(http, dataDir)
            }
            device = d
            return d
        }
    }

    fun resetDevice() { synchronized(deviceLock) { device = null; HGDevice.reset(dataDir) } }

    @Volatile private var lastAutoReregisterMs = 0L
    private val autoReregisterMinIntervalMs = 30_000L

    private fun hasPureOverride(): Boolean =
        HGPlayerDevice.overrideProvider?.invoke()?.let { it.first.isNotEmpty() && it.second.isNotEmpty() } == true

    /**
     * Proactive renewal before video_model: a registered device only plays for ~10 min after device_register, so a
     * device restored on a cold start (or kept across a long session) is already dead. Same as tapping 「重置」.
     */
    private fun refreshDeviceIfStaleForPlayback() {
        if (hasPureOverride()) return
        val old: HGDevice
        synchronized(deviceLock) {
            old = (device ?: HGDevice.load(dataDir)) ?: return
            if (!old.isStaleForPlayback) return
            val now = System.currentTimeMillis()
            if (now - lastAutoReregisterMs < autoReregisterMinIntervalMs) return
            lastAutoReregisterMs = now
            device = null; HGDevice.reset(dataDir)
        }
        runCatching { ensureDevice() }.onSuccess {
            System.err.println("[HG] device …${old.deviceId.takeLast(4)} stale -> re-registered …${it.deviceId.takeLast(4)}")
        }
    }

    /**
     * Automatic 「重置红果设备注册」: video_model soft-rejects (HTTP 200 + 0 bytes) a registered device after it has
     * been used for a while, and nothing renewed it, so every later play came back empty until a manual reset.
     * Drops the device only if it is still the one that failed (prefetch may already have replaced it); throttled.
     * Returns true when a different, usable device is now in place.
     */
    private fun reregisterAfterSoftReject(failedDid: String): Boolean {
        if (hasPureOverride()) return false                    // user-pinned device: never replace it
        synchronized(deviceLock) {
            val cur = device ?: HGDevice.load(dataDir)
            if (cur != null && cur.deviceId != failedDid) return true
            val now = System.currentTimeMillis()
            if (now - lastAutoReregisterMs < autoReregisterMinIntervalMs) return false
            lastAutoReregisterMs = now
            device = null; HGDevice.reset(dataDir)
        }
        return try {
            val d = ensureDevice()
            System.err.println("[HG] soft reject on device …${failedDid.takeLast(4)} -> re-registered …${d.deviceId.takeLast(4)}")
            d.deviceId != failedDid
        } catch (e: Exception) {
            System.err.println("[HG] auto re-register failed: $e"); false
        }
    }

    private fun playerQuery(rt: Long): String =
        HGPlayerDevice.queryPart() +
            "&ac=wifi&channel=ry_hongguo_offlinestore_04&aid=8662&app_name=novelread" +
            "&version_code=72133&version_name=7.2.133&device_platform=android&os=android&ssmix=a&device_type=EML-AL00&device_brand=HUAWEI" +
            "&language=zh&os_api=29&os_version=10&manifest_version_code=72133&resolution=1080*2340&dpi=480&update_version_code=72133&_rticket=$rt"

    /** Which identity the detail preload carries: override > registered list device ("registered") or minted ("mint"). */
    @Volatile var detailIdentity: String = "registered"
    @Volatile var lastDetailRoute: String = ""

    private fun detailIdsPart(): String {
        HGPlayerDevice.overrideProvider?.invoke()?.let { (d, i) -> if (d.isNotEmpty() && i.isNotEmpty()) return "iid=$i&device_id=$d" }
        if (detailIdentity == "oneshot") HGOneShotDevice.register(http)?.let { return "iid=${it.iid}&device_id=${it.did}" }
        if (detailIdentity == "registered") {
            val d = try { ensureDevice() } catch (_: Exception) { null }
            if (d != null) return "iid=${d.iid}&device_id=${d.deviceId}"
        }
        return HGPlayerDevice.queryPart()
    }

    private fun detailQuery(rt: Long): String =
        detailIdsPart() +
            "&ac=wifi&channel=ry_hongguo_offlinestore_04&aid=8662&app_name=novelread" +
            "&version_code=73332&version_name=7.3.3.32&device_platform=android&os=android&ssmix=a&device_type=sdk_gphone64_arm64&device_brand=google" +
            "&language=en&os_api=35&os_version=15&manifest_version_code=73332&resolution=1080*2400&dpi=420&update_version_code=73332" +
            "&_rticket=$rt&&host_abi=arm64-v8a&dragon_device_type=phone&pv_player=73332&compliance_status=0&need_personal_recommend=1" +
            "&player_so_load=1&is_android_pad_screen=0&rom_version=sdk_gphone64_arm64-userdebug+15+AE3A.240806.043+12960925+dev-keys" +
            "&cdid=22daeba8-ef23-4742-abbe-c41bc3d6d4c8"

    fun listQuery(d: HGDevice, rt: Long): String {
        val vc = HGDevice.versionCode
        return "iid=${d.iid}&device_id=${d.deviceId}&ac=wifi&channel=ry_hongguo_offlinestore_04&aid=8662&app_name=novelread" +
            "&version_code=$vc&version_name=${HGDevice.appVersion}&device_platform=android&os=android&ssmix=a" +
            "&device_type=sdk_gphone64_arm64&device_brand=google&language=zh&os_api=35&os_version=15" +
            "&manifest_version_code=$vc&resolution=1080*2400&dpi=420&update_version_code=$vc&_rticket=$rt" +
            "&host_abi=arm64-v8a&dragon_device_type=phone&pv_player=$vc&compliance_status=0&need_personal_recommend=1&player_so_load=1" +
            "&is_android_pad_screen=0&rom_version=sdk_gphone64_arm64-userdebug+15+AE3A.240806.043+12960925+dev-keys&cdid=${d.cdid}"
    }

    private fun commonHeaders(rb: Request.Builder) {
        rb.header("User-Agent", UA)
            .header("Accept", "application/json; charset=utf-8")
            .header("x-ss-dp", "8662").header("comment-source", "1506").header("lc", "101")
            .header("sdk-version", "2").header("passport-sdk-version", "5051452")
            .header("x-vc-bdturing-sdk-version", "4.0.3.cn")
            .header("x-tt-store-region", "cn-cq").header("x-tt-store-region-src", "did")
            .header("x-xs-from-web", "0")
            .header("x-reading-request", "${nowMs()}-${(100_000_000..999_999_999).random()}")
    }

    private fun send(req: Request, retries: Int = 2): JSONObject {
        var last: Exception = ProviderException.upstream("请求失败")
        for (i in 0..retries) {
            if (i > 0) Thread.sleep(i * 800L)
            try {
                val resp = http.newCall(req).execute()
                val data = try { resp.body?.bytes() ?: ByteArray(0) } finally { resp.close() }
                if (data.isEmpty()) {
                    last = ProviderException.upstream("HTTP ${resp.code} 空响应（上游软拒绝）")
                    continue
                }
                return J.parse(data)
            } catch (e: Exception) { last = e }
        }
        throw last
    }

    fun list(url: String, method: String = "GET", signed: Boolean = false): JSONObject {
        val rb = Request.Builder().url(url)
        if (method == "POST") rb.post(ByteArray(0).toRequestBody(null)) else rb.get()
        commonHeaders(rb)
        if (signed) {
            val d = device ?: throw ProviderException.invalid("device")
            for ((k, v) in HGSign.fullHeaders(url, d)) rb.header(k, v)
        }
        return send(rb.build())
    }

    private fun gzip(body: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(body) }
        return bos.toByteArray()
    }

    private fun postJSON(base: String, query: String, body: ByteArray, retries: Int = 2): JSONObject {
        val gz = gzip(body)
        val now = nowMs()
        val rb = Request.Builder().url(base + query)
            .post(gz.toRequestBody("application/json; charset=utf-8".toMediaType()))
        commonHeaders(rb)
        rb.header("Accept", "application/json; charset=utf-8,application/x-protobuf")
            .header("x-ss-req-ticket", now.toString())
            .header("Content-Encoding", "gzip")
            .header("x-ss-stub", Bytes.hex(Bytes.md5(gz)).uppercase())
        return send(rb.build(), retries)
    }

    private fun ok(j: JSONObject, what: String): JSONObject {
        val code = J.str(j.opt("code"))
        if (code != "0") throw ProviderException.upstream("$what code=$code ${J.str(j.opt("message"))}")
        return J.dict(j.opt("data"))
    }

    // ---- search (list.go buildSearchTabURL / parseSearchJSONForTab) ----
    @Volatile private var lastSearchQuery = ""
    @Volatile private var lastSearchAt = 0L
    @Volatile private var searchEntered = false

    private fun selectorId(f: HGContentFilter): String? = when (f) {
        HGContentFilter.human -> "content_type_short_play"; HGContentFilter.comic -> "content_type_dynamic_comic"
        HGContentFilter.ai -> "content_type_ai_dynamic_comic"; HGContentFilter.all -> null
    }

    private fun contentFilterOf(vd: JSONObject): HGContentFilter? {
        val det = J.dict(vd.opt("video_detail"))
        return when (J.firstPositive(vd.opt("content_type"), det.opt("content_type"))) {
            1L -> HGContentFilter.human
            1004L -> if (J.firstNonEmpty(J.str(det.opt("video_category_type")), J.str(vd.opt("video_category_type"))) == "ai_video") HGContentFilter.ai else HGContentFilter.comic
            else -> null
        }
    }

    /** cursor = previous nextCursor ("offset|passback"). Series-landing expansion (系列剧卡) not yet ported. */
    fun search(q: String, contentFilter: HGContentFilter = HGContentFilter.all, cursor: String? = null): HGPage {
        val d = ensureDevice()
        var offset = 0; var passback = ""
        if (!cursor.isNullOrEmpty()) {
            val parts = cursor.split("|", limit = 2)
            offset = parts[0].toIntOrNull() ?: 0
            if (parts.size > 1) passback = parts[1]
        }
        val last = lastSearchQuery.ifEmpty { q }
        val interval = if (lastSearchAt > 0) maxOf(0L, nowMs() - lastSearchAt) else 0L
        val first = offset == 0 && !searchEntered
        if (offset == 0) { searchEntered = true; lastSearchQuery = q; lastSearchAt = nowMs() }
        val sid = d.cdid.ifEmpty { "0" }
        var u = "https://api5-normal-sinfonlinec.fqnovel.com/reading/bookapi/search/tab/v?" +
            "bookshelf_search_plan=4&live_room_id=0&user_is_login=0&bookstore_tab=-1&biz_config_ctx_infos&last_book_id=0" +
            "&last_search_page_query=" + GoURL.queryEscape(last) +
            "&clicked_content=page_search_button&report_info&use_lynx=false&last_book_consume_time=0&product_id=0&line_words_num=0" +
            "&tab_name&last_consume_interval=0&pad_column_cover=0&last_chapter_id=0&only_feed=false&offset=$offset" +
            "&from_rs=false&only_large_card=false&query=" + GoURL.queryEscape(q) +
            "&count=0&search_source=0&search_source_id=clks%23%23%23&use_correct=false&last_search_page_interval=$interval" +
            "&seed_product_id=0&from_half_screen=false&is_first_enter_search=$first" +
            "&iid=${d.iid}&device_id=${d.deviceId}&ac=wifi&channel=vivo_8662_64&aid=8662&app_name=novelread" +
            "&version_code=73532&version_name=7.3.5.32&device_platform=android&os=android&ssmix=a" +
            "&device_type=NX809J&device_brand=Nubia&language=zh&os_api=31&os_version=12" +
            "&manifest_version_code=73532&resolution=1280*720&dpi=240&update_version_code=73532&_rticket=${nowMs()}" +
            "&normal_session_cnt_in_day=1&gender=2&cold_start_session_cnt_in_day=1&host_abi=arm64-v8a&dragon_device_type=pad" +
            "&sys_mini_window=0&pv_player=73532&app_mini_window=0&normal_session_id=" + GoURL.queryEscape("$sid#1") +
            "&compliance_status=0&har_status=1&cold_start_session_id=" + GoURL.queryEscape(sid) +
            "&cold_start_session_cnt_in_life=1&charging=0&normal_session_cnt_in_life=1&is_power_save_mode=0&app_dark_mode=0" +
            "&screen_brightness=102&battery_pct=80&down_speed=30000&sys_dark_mode=0&need_personal_recommend=1&player_so_load=1" +
            "&font_scale=100&is_android_pad_screen=1&network_type=4&rom_version=Nubia-user+12.0.0+20171130.276299+release-keys" +
            "&current_volume=33&cdid=${d.cdid}&client_ab_info=" +
            GoURL.queryEscape("""{"middle_style_from_video":false,"result_style_from_video":false}""")
        selectorId(contentFilter)?.let { u += "&selected_items=" + GoURL.queryEscape(it) }
        if (passback.isNotEmpty()) u += "&passback=" + GoURL.queryEscape(passback)

        var lastErr: Exception = ProviderException.upstream("搜索失败")
        for (attempt in 0 until 3) {
            if (attempt > 0) Thread.sleep(1600)
            val j = try { list(u, signed = true) } catch (e: Exception) { lastErr = e; continue }
            val code = J.str(j.opt("code"))
            if (code != "0") {
                lastErr = ProviderException.upstream("search code=$code ${J.str(j.opt("message"))}")
                if (code == "100103") continue
                break
            }
            for (tab in J.arr(j.opt("search_tabs"))) {
                val tm = J.dict(tab)
                if (!(J.int(tm.opt("tab_type")) == 11 || J.str(tm.opt("title")) == "综合")) continue
                val items = ArrayList<HGSeries>(); val seen = HashSet<String>()
                for (c in J.arr(tm.opt("data"))) {
                    val cm = J.dict(c)
                    var vds = J.arr(cm.opt("video_data"))
                    if (vds.isEmpty()) (cm.opt("video_data") as? JSONObject)?.let { vds = listOf(it) }
                    val fallbackSid = firstID(cm, "book_id", "search_result_id")
                    for (v in vds) {
                        val vm = J.dict(v)
                        if (contentFilter != HGContentFilter.all && contentFilterOf(vm) != contentFilter) continue
                        val s = parseVideoData(vm)
                        if (s.seriesId.isEmpty()) s.seriesId = fallbackSid
                        if (s.seriesId.isEmpty() && s.title.isEmpty()) continue
                        if (s.seriesId.isNotEmpty() && !seen.add(s.seriesId)) continue
                        items.add(s)
                    }
                }
                val next = J.int(tm.opt("next_offset"))
                return HGPage(items, J.bool(tm.opt("has_more")), next, "$next|${J.str(tm.opt("passback"))}")
            }
            lastErr = ProviderException.upstream("search 缺少综合栏")
        }
        throw lastErr
    }

    // ---- panel / category (list.go) ----
    @Volatile private var panelCache: Pair<Long, HGPanel>? = null

    fun panel(): HGPanel {
        panelCache?.let { (at, p) -> if (nowMs() - at < 600_000) return p }
        val d = ensureDevice()
        val u = URL_CATEGORY + "need_selector_panel=true&req_type=only_panel&req_scene=default&" + listQuery(d, nowMs())
        val data = ok(list(u, method = "POST"), "panel")
        val rows = J.arr(data.opt("selector_rows")).map { r ->
            val rm = J.dict(r)
            HGFilterRow(J.str(rm.opt("type")), J.str(rm.opt("row_name")), J.int(rm.opt("selection_type")),
                J.arr(rm.opt("items")).map { HGFilterOption(J.str(J.dict(it).opt("selector_item_id")), J.str(J.dict(it).opt("show_name"))) })
        }
        return HGPanel(rows).also { panelCache = nowMs() to it }
    }

    fun category(filterIds: List<String>, offset: Int = 0, sessionId: String? = null): HGPage {
        val d = ensureDevice()
        var u = URL_CATEGORY + "offset=$offset&need_selector_panel=false&limit=12&session_id=" + GoURL.queryEscape(sessionId ?: "") +
            "&client_req_type=3&req_type=only_content"
        if (filterIds.isNotEmpty()) u += "&filter_ids=" + GoURL.queryEscape(filterIds.joinToString(","))
        u += "&" + listQuery(d, nowMs())
        val data = ok(list(u, method = "POST"), "category")
        val next = J.int(data.opt("next_offset"))
        val sess = J.str(data.opt("session_id")).ifEmpty { sessionId ?: "" }
        val more = J.bool(data.opt("has_more")) && next > offset && sess.isNotEmpty()
        val items = J.arr(data.opt("video_data")).map { parseVideoData(J.dict(it)) }
        return HGPage(items, more, next, null, sess)
    }

    // ---- rank ----
    fun rank(sub: HGRankSub, genre: HGRankGenre, offset: Int = 0, seenIds: List<String> = emptyList()): HGPage {
        val selected = rankSelected[genre] ?: throw ProviderException.invalid("${genre.label} 没有 ${sub.label} 榜")
        val subId = rankSubIds[genre]?.get(sub) ?: throw ProviderException.invalid("${genre.label} 没有 ${sub.label} 榜")
        val d = ensureDevice()
        val u = URL_RANK_CELL + "background_selected_items&celebrity_user_id&cell_id=7470092475068071998" +
            "&client_req_type=2&client_template=2&filter_ids=" + GoURL.queryEscape(seenIds.joinToString(",")) +
            "&offset=$offset&panel_selected_items&rank_version=${(System.currentTimeMillis() / 1000)}&screen_width_px=1080" +
            "&selected_items=$selected&sub_selected_items=$subId&tab_type=26&unlimited_selector_change_type=1&" +
            listQuery(d, nowMs())
        val data = ok(list(u), "rank")
        val items = ArrayList<HGSeries>()
        var rank = offset
        for (c in J.arr(J.dict(data.opt("cell_view")).opt("cell_data"))) {
            for (v in J.arr(J.dict(c).opt("video_data"))) {
                rank += 1
                val s = parseVideoData(J.dict(v)); s.rank = rank; items.add(s)
            }
        }
        return HGPage(items, J.bool(data.opt("has_more")), J.int(data.opt("next_offset")), null, J.str(data.opt("session_id")).ifEmpty { null })
    }

    // ---- detail ----
    fun detail(seriesId: String, web: Boolean = true): HGSeriesDetail {
        val body = mapOf(
            "biz_param" to mapOf(
                "detail_page_version" to 0, "disable_digg_stat" to false, "disable_video_relate_book" to false,
                "image_shrink_datas_str" to "W3siaW1hZ2VfdHlwZSI6MywiaW1hZ2Vfd2lkdGgiOjEwNzksInNocmlua190eXBlIjozfSx7ImltYWdlX3R5cGUiOjQsImltYWdlX3dpZHRoIjo5NCwic2hyaW5rX3R5cGUiOjR9XQ==",
                "need_all_video_definition" to false, "need_mp4_align" to false, "screen_width_px" to "1079", "source" to 7,
                "use_os_player" to false, "use_server_dns" to false,
            ),
            "dr_scene" to "preload", "series_id" to seriesId,
        )
        // no retry on soft-rejected detail preload (iOS bb23241); preload / directory / landing / web run concurrently (iOS async let)
        val pool = java.util.concurrent.ForkJoinPool.commonPool()
        val detailF = java.util.concurrent.CompletableFuture.supplyAsync({ try { postJSON(URL_DETAIL, detailQuery(nowMs()), J.encodeSorted(body), retries = 0) } catch (_: Exception) { null } }, pool)
        val dirF = java.util.concurrent.CompletableFuture.supplyAsync({ try { bookDirectory(seriesId) } catch (_: Exception) { null } }, pool)
        val landedF = java.util.concurrent.CompletableFuture.supplyAsync({ try { HGDetailExtras.seriesLanding(this, seriesId, maxPages = 2) } catch (_: Exception) { null } }, pool)
        val webF = if (web) java.util.concurrent.CompletableFuture.supplyAsync({ try { HGDetailExtras.webDetail(seriesId) } catch (_: Exception) { null } }, pool) else null
        val detailJ = detailF.get()
        val dirJ = dirF.get()
        val landed = landedF.get()
        val webD = webF?.get()
        var vd = JSONObject()
        if (detailJ != null && J.str(detailJ.opt("code")) == "0") {
            vd = J.dict(J.dict(J.dict(detailJ.opt("data")).opt(seriesId)).opt("video_data"))
        }
        val info = J.dict(J.dict(dirJ?.opt("data")).opt("book_info"))
        if (vd.length() == 0) {
            if (dirJ == null) throw ProviderException.upstream("详情获取失败")
            lastDetailRoute = "directory"
            val data = J.dict(dirJ.opt("data"))
            val eps = ArrayList<HGEpisode>()
            J.arr(data.opt("item_data_list")).forEachIndexed { i, raw ->
                val m = J.dict(raw)
                val vid = J.firstNonEmpty(J.str(m.opt("item_id")), J.str(m.opt("vid")))
                if (vid.isEmpty()) return@forEachIndexed
                val n = J.int(m.opt("chapter_index"))
                eps.add(HGEpisode(if (n > 0) n else i + 1, vid, 0, J.str(m.opt("title"))))
            }
            val title = J.firstNonEmpty(J.str(info.opt("book_name")), J.str(info.opt("original_book_name")))
            val cover = J.str(info.opt("thumb_url"))
            val total = maxOf(J.int(info.opt("serial_count")), eps.size)
            val related = HGDetailExtras.loadRelatedSeries(seriesId, title, cover, total, JSONObject(), landed)
            return HGDetailExtras.enrich(HGSeriesDetail(
                seriesId = seriesId, title = title, cover = cover, desc = J.str(info.opt("abstract")),
                author = J.str(info.opt("author")), actors = names(info.opt("roles"), info.opt("role")),
                onlineTime = J.unix(info.opt("create_time")), totalEps = total, episodes = eps, relatedSeries = related,
            ), webD)
        }
        lastDetailRoute = "preload"
        val eps = ArrayList<HGEpisode>()
        for (e in J.arr(vd.opt("video_list"))) {
            val em = J.dict(e); val vid = J.str(em.opt("vid")); if (vid.isEmpty()) continue
            eps.add(HGEpisode(J.int(em.opt("vid_index")), vid, J.int(em.opt("duration")), J.str(em.opt("title"))))
        }
        var actors = celebrities(vd)
        if (actors.isEmpty()) actors = names(info.opt("roles"), info.opt("role"))
        val title = J.str(vd.opt("series_title")); val cover = J.str(vd.opt("series_cover")); val total = J.int(vd.opt("episode_cnt"))
        val related = HGDetailExtras.loadRelatedSeries(seriesId, title, cover, maxOf(total, eps.size), vd, landed)
        return HGDetailExtras.enrich(HGSeriesDetail(
            seriesId = seriesId, title = title, cover = cover,
            desc = J.firstNonEmpty(J.str(vd.opt("series_intro")), J.str(info.opt("abstract"))),
            author = J.firstNonEmpty(J.str(info.opt("author")), J.str(info.opt("source"))),
            actors = actors, onlineTime = J.unix(info.opt("create_time")),
            totalEps = total, episodes = eps, relatedSeries = related,
        ), webD)
    }

    private fun bookDirectory(sid: String): JSONObject {
        val now = nowMs()
        val rb = Request.Builder().url(URL_BOOK_DIR + playerQuery(now) + "&book_id=$sid")
            .header("User-Agent", UA)
            .header("Accept", "application/json; charset=utf-8,application/x-protobuf")
            .header("x-ss-dp", "8662").header("x-ss-req-ticket", now.toString())
            .header("x-khronos", (now / 1000).toString()).header("sdk-version", "2")
            .header("passport-sdk-version", "5051452")
        val j = send(rb.build(), retries = 1)
        val code = J.str(j.opt("code"))
        if (!(code == "0" || code.uppercase() == "SUCCESS" || J.str(j.opt("message")).uppercase() == "SUCCESS")) {
            throw ProviderException.upstream("directory code=$code")
        }
        return j
    }

    // ---- resolve ----
    fun resolve(vid: String): HGResolvedVideo {
        synchronized(resolveCache) {
            resolveCache[vid]?.let { (v, at) ->
                val urlAlive = (v.expiresAt <= 0) || (v.expiresAt > System.currentTimeMillis() / 1000 + 60)
                if (System.currentTimeMillis() - at < RESOLVE_TTL_MS && urlAlive) return v
                resolveCache.remove(vid)
            }
        }
        val r = resolveFresh(vid)
        if (r.err == null && r.streams.isNotEmpty()) {
            synchronized(resolveCache) { resolveCache[vid] = r to System.currentTimeMillis() }
        }
        return r
    }

    fun resolveFresh(vid: String): HGResolvedVideo {
        var soft = false
        val hasOverride = hasPureOverride()
        // iOS 45d3971: make sure a device_register device exists before video_model (purePlayerIds prefers it).
        refreshDeviceIfStaleForPlayback()
        runCatching { ensureDevice() }
        val pureDid = purePlayerIds().first
        val (first, firstSoft) = resolvePureLevels(vid, hasOverride)
        first?.let { return it }
        soft = firstSoft
        // Soft reject on the registered device = it went stale upstream. Re-register once and retry instead of
        // falling through to the legacy dialects (minted devices, soft-rejected anyway).
        if (soft && reregisterAfterSoftReject(pureDid)) {
            resolvePureLevels(vid, hasOverride).first?.let { return it }
        }
        for (level in listOf(3, 4)) {
            try {
                val r = resolveBatch(vid, level) ?: continue
                if (r.err == BYTEVC2 && level == 3) continue
                return r
            } catch (_: PlayerEmptyBody) {
                soft = true; System.err.println("[HG] resolveBatch soft-reject level=$level")
            } catch (e: Exception) {
                System.err.println("[HG] resolveBatch error level=$level: $e")
            }
        }
        for (level in listOf(3, 4)) {
            try {
                val r = resolveSingle(vid, level)
                if (r.err == BYTEVC2 && level == 3) continue
                return r
            } catch (e: Exception) {
                if (e.message?.contains("空响应") == true || e.message?.contains("软拒绝") == true) soft = true
                System.err.println("[HG] resolveSingle error level=$level: ${e.message}")
                if (level == 4) break
            }
        }
        if (soft) throw ProviderException.upstream("取流被上游空包拒绝（软拒绝）")
        throw ProviderException.upstream("取链失败")
    }

    /** Pure video_model level 3 then 4; an empty body means the device is rejected, so stop there. (result, softRejected) */
    private fun resolvePureLevels(vid: String, hasOverride: Boolean): Pair<HGResolvedVideo?, Boolean> {
        for (level in listOf(3, 4)) {
            try {
                val r = resolvePure(vid, level)
                if (r.err == BYTEVC2 && level == 3) continue
                lastPureRoute = if (hasOverride) "override" else if (device != null) "registered" else "mint"
                return r to false
            } catch (_: PlayerEmptyBody) {
                System.err.println("[HG] resolvePure empty level=$level"); return null to true
            } catch (e: Exception) {
                System.err.println("[HG] resolvePure error level=$level: $e")
            }
        }
        return null to false
    }

    /** Pure player device: override via HGPlayerDevice.overrideProvider, else local mint (public hygiene). */
    private fun purePlayerIds(): Pair<String, String> {
        HGPlayerDevice.overrideProvider?.invoke()?.let { if (it.first.isNotEmpty() && it.second.isNotEmpty()) return it }
        // iOS 45d3971: video_model only accepts devices that went through device_register (a locally minted
        // did/iid gets 200 + 0 bytes, a registered one returns video_model). Prefer the registered one.
        (device ?: HGDevice.load(dataDir))?.let { return it.deviceId to it.iid }
        val d = HGPlayerDevice.current()
        return d.deviceId to d.iid
    }

    private fun stableUUID(seed: String): String {
        val h = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray()).copyOfRange(0, 16)
        h[6] = ((h.u(6) and 0x0f) or 0x40).toByte()
        h[8] = ((h.u(8) and 0x3f) or 0x80).toByte()
        val hex = Bytes.hex(h)
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20, 32)}"
    }

    private fun purePlayerPairs(did: String, iid: String, cdidIn: String? = null, openudidIn: String? = null): List<Pair<String, String>> {
        val cdid = cdidIn ?: stableUUID("hongguo-player-cdid:$did")
        val openudid = openudidIn ?: Bytes.hex(MessageDigest.getInstance("SHA-256").digest("hongguo-player-openudid:$did".toByteArray()).copyOfRange(0, 8))
        val vc = PURE_VC
        return listOf(
            "iid" to iid, "device_id" to did, "ac" to "wifi", "channel" to "xiaomi_8662_64", "aid" to "8662",
            "app_name" to "novelread", "cdid" to cdid, "version_code" to vc, "version_name" to PURE_VN,
            "device_platform" to "android", "os" to "android", "ssmix" to "a", "device_type" to "23127PN0CC",
            "device_brand" to "Xiaomi", "language" to "zh", "os_api" to "34", "os_version" to "14",
            "manifest_version_code" to vc, "resolution" to "1200*2670", "dpi" to "460", "update_version_code" to vc,
            "host_abi" to "arm64-v8a", "dragon_device_type" to "phone", "pv_player" to vc, "compliance_status" to "0",
            "need_personal_recommend" to "1", "player_so_load" to "1", "is_android_pad_screen" to "0", "openudid" to openudid,
        )
    }

    /**
     * Signed POST for the pure video_model endpoint, same as iOS `resolvePure`: the exact wire query
     * (pairs + ts + _rticket) and body are signed with Argus 551 / Ladon only (HGPlaybackSign).
     * The legacy Gorgon / Medusa / Helios set is soft-rejected here (HTTP 200 + 0 bytes).
     */
    private fun pureRequest(body: ByteArray, pairs: List<Pair<String, String>>): Request {
        val nowMs = nowMs()
        val ts = (nowMs / 1000).toUInt()
        val q = HGPlayerSign.encodeQuery(pairs + listOf("ts" to ts.toString(), "_rticket" to nowMs.toString()))
        val rb = Request.Builder().url("https://$PURE_HOST$PURE_PATH?$q")
            .post(body.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .header("Content-Type", "application/json; charset=UTF-8").header("Accept", "application/json")
        for ((k, v) in HGPlaybackSign.headers(q, body, ts)) if (v.isNotEmpty()) rb.header(k, v)
        rb.header("User-Agent", PURE_UA).header("x-ss-dp", "8662").header("lc", "101")
            .header("x-reading-request", "$nowMs-${(nowMs * 1_000_000L) and 0x7fffffff}")
        return rb.build()
    }

    /**
     * Diagnostics only (soft-reject research): one pure video_model request for an explicit identity.
     * Returns "P" (video_model present), "F" (HTTP 200 + 0 bytes soft reject), "C<code>", "H<status>" or "E".
     */
    fun probePure(vid: String, level: Int, did: String, iid: String, cdid: String? = null, openudid: String? = null): String {
        val body = J.encodeSorted(mapOf("video_id" to vid, "biz_param" to mapOf(
            "video_id_type" to 0, "need_all_video_definition" to true, "video_platform" to 3, "device_level" to level)))
        return try {
            playerHttp.newCall(pureRequest(body, purePlayerPairs(did, iid, cdid, openudid))).execute().use { resp ->
                val data = resp.body?.bytes() ?: ByteArray(0)
                when {
                    resp.code != 200 -> "H${resp.code}"
                    data.isEmpty() -> "F"
                    else -> {
                        val j = J.parse(data)
                        val code = J.str(j.opt("code"))
                        if (code.isNotEmpty() && code != "0") "C$code"
                        else if (videoModelJSON(J.dict(j.opt("data")).opt("video_model")) != null) "P" else "C?"
                    }
                }
            }
        } catch (e: Exception) { "E" }
    }

    private fun resolvePure(vid: String, level: Int, ids: HGOneShotDevice.Identity? = null): HGResolvedVideo {
        val bodyObj = mapOf(
            "video_id" to vid,
            "biz_param" to mapOf(
                "video_id_type" to 0, "need_all_video_definition" to true, "video_platform" to 3,
                "device_level" to if (level >= 4) 4 else 3,
            ),
        )
        val body = J.encodeSorted(bodyObj)
        var last: Exception = PlayerEmptyBody()
        for (attempt in 0 until (if (ids != null) 1 else 2)) {
            if (attempt > 0) Thread.sleep(500)
            val (did, iid) = ids?.let { it.did to it.iid } ?: purePlayerIds()
            try {
                playerHttp.newCall(pureRequest(body, purePlayerPairs(did, iid, ids?.cdid, ids?.openudid))).execute().use { resp ->
                    val status = resp.code
                    val data = resp.body?.bytes() ?: ByteArray(0)
                    if (status == 429 || status >= 500) { last = ProviderException.upstream("video_model HTTP $status"); return@use }
                    if (status != 200) throw ProviderException.upstream("video_model HTTP $status")
                    if (data.isEmpty()) {
                        System.err.println("[HG] resolvePure HTTP 200 empty level=$level did=$did bytes=0")
                        throw PlayerEmptyBody()
                    }
                    val j = J.parse(data)
                    for (c in listOf(j.opt("code"), J.dict(j.opt("BaseResp")).opt("StatusCode"))) {
                        if (c != null && J.str(c) != "0") throw ProviderException.upstream("video_model code=${J.str(c)} ${J.str(j.opt("message"))}")
                    }
                    val raw = videoModelJSON(J.dict(j.opt("data")).opt("video_model"))
                        ?: throw ProviderException.upstream("video_model 缺失")
                    val r = materialize(raw, vid)
                    System.err.println("[HG] resolvePure ok level=$level streams=${r.streams.size} did=$did")
                    return r
                }
            } catch (e: PlayerEmptyBody) { throw e }   // device rejected: retrying the same device only burns a request
            catch (e: Exception) { last = e }
        }
        throw last
    }

    private fun playerBatchBases() = if (preferredBatchABOff) listOf(URL_BATCH_ABOFF, URL_BATCH) else listOf(URL_BATCH, URL_BATCH_ABOFF)

    private fun postPlayerBatch(base: String, query: String, body: ByteArray): JSONObject {
        var last: Exception = PlayerEmptyBody()
        for (i in 0..2) {
            if (i > 0) Thread.sleep(i * 1000L)
            val now = System.currentTimeMillis()
            val headers = if (base == URL_BATCH_ABOFF) HGSign.genericSessionHeaders(query, body, now)
            else HGSign.videoModelHeaders(query, body, now)
            val root = base.dropLast(1)
            val rb = Request.Builder().url("$root?$query")
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("User-Agent", UA).header("Accept", "application/json")
                .header("X-XS-From-Web", "0").header("Sdk-Version", "2")
            for ((k, v) in headers) rb.header(k, v)
            try {
                val resp = playerHttp.newCall(rb.build()).execute()
                val data = try { resp.body?.bytes() ?: ByteArray(0) } finally { resp.close() }
                if (data.isEmpty()) {
                    last = PlayerEmptyBody()
                    System.err.println("[HG] player batch HTTP ${resp.code} empty form=${if (base == URL_BATCH_ABOFF) "ab_off" else "ab_on"} attempt=${i + 1}")
                    continue
                }
                return J.parse(data)
            } catch (e: Exception) { last = e }
        }
        throw last
    }

    private fun resolveBatch(vid: String, level: Int): HGResolvedVideo? {
        val body = mapOf(
            "biz_param" to mapOf(
                "detail_page_version" to 0, "device_level" to level, "disable_digg_stat" to false,
                "disable_video_relate_book" to false, "need_all_video_definition" to true, "need_mp4_align" to false,
                "use_os_player" to false, "use_server_dns" to true, "use_server_dns_scene" to "cold_start_stage",
                "video_platform" to 1024,
            ),
            "dr_scene" to "player", "mixed_video_id_map" to mapOf("1004" to listOf(vid)),
        )
        val bodyData = J.encodeSorted(body)
        val attempts = HGPlayerDevice.devicesForChunk()
        var lastEmpty: Exception? = null
        for (attempt in 0 until attempts) {
            for (base in playerBatchBases()) {
                try {
                    val j = postPlayerBatch(base, playerQuery(nowMs()), bodyData)
                    preferredBatchABOff = (base == URL_BATCH_ABOFF)
                    val ent = J.dict(J.dict(j.opt("data")).opt(vid))
                    val raw = videoModelJSON(ent.opt("video_model")) ?: run {
                        System.err.println("[HG] player batch code=${J.str(j.opt("code"))} no video_model"); return null
                    }
                    val r = materialize(raw, vid)
                    System.err.println("[HG] player batch ok form=${if (base == URL_BATCH_ABOFF) "ab_off" else "ab_on"} level=$level streams=${r.streams.size}")
                    return r
                } catch (_: PlayerEmptyBody) { lastEmpty = PlayerEmptyBody(); continue }
                catch (e: Exception) { throw e }
            }
            if (attempt + 1 >= attempts) break
            val d = HGPlayerDevice.remint()
            System.err.println("[HG] player device remint after both forms empty -> ${d.deviceId}")
        }
        if (lastEmpty != null) throw PlayerEmptyBody()
        return null
    }

    private fun resolveSingle(vid: String, level: Int): HGResolvedVideo {
        val bodyObj = mapOf(
            "video_id" to vid, "content_type" to 1,
            "biz_param" to mapOf("need_all_video_definition" to true, "video_platform" to 3, "device_level" to level),
        )
        val body = J.encodeSorted(bodyObj)
        val now = System.currentTimeMillis()
        val q = GoURL.encode(mapOf(
            "aid" to "8662", "app_name" to "novelread", "version_code" to "73532", "version_name" to "7.3.5.32",
            "manifest_version_code" to "73532", "update_version_code" to "73532", "channel" to "update_64",
            "device_platform" to "android", "os" to "android", "ssmix" to "a", "device_type" to "25053RT47C", "device_brand" to "Redmi",
            "language" to "zh", "os_api" to "36", "os_version" to "16", "resolution" to "1280*2772", "dpi" to "520", "ac" to "wifi",
            "device_id" to vmDeviceId, "iid" to vmInstallId, "_rticket" to now.toString(),
        ))
        val rb = Request.Builder().url("$URL_VIDEO_MODEL?$q")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Accept", "application/json").header("User-Agent", VIDEO_MODEL_UA)
            .header("X-XS-From-Web", "0").header("Sdk-Version", "2")
        for ((k, v) in HGSign.videoModelHeaders(q, body, now)) rb.header(k, v)
        playerHttp.newCall(rb.build()).execute().use { resp ->
            val data = resp.body?.bytes() ?: ByteArray(0)
            if (data.isEmpty()) {
                System.err.println("[HG] resolveSingle HTTP ${resp.code} empty (soft reject)")
                throw ProviderException.upstream("HTTP ${resp.code} 空响应（上游软拒绝）")
            }
            val j = J.parse(data)
            for (c in listOf(j.opt("code"), J.dict(j.opt("BaseResp")).opt("StatusCode"))) {
                if (c != null && J.str(c) != "0") throw ProviderException.upstream("video_model code=${J.str(c)}")
            }
            val raw = videoModelJSON(J.dict(j.opt("data")).opt("video_model"))
                ?: throw ProviderException.upstream("video_model 缺失")
            return materialize(raw, vid)
        }
    }

    private fun videoModelJSON(v: Any?): JSONObject? = when (v) {
        is JSONObject -> v
        is String -> try { JSONObject(v) } catch (_: Exception) { null }
        else -> null
    }

    fun materialize(vm: JSONObject, vid: String): HGResolvedVideo {
        if (hasInlineList(vm)) return parseVideoModel(vm, vid, keySeed(vm))
        // fallback_api path omitted for M1 brevity when inline list present; still handle empty
        return parseVideoModel(vm, vid, keySeed(vm))
    }

    private fun hasInlineList(vm: JSONObject): Boolean {
        val vl = vm.opt("video_list")
        return when (vl) {
            is JSONObject -> vl.length() > 0
            is org.json.JSONArray -> vl.length() > 0
            else -> false
        }
    }

    private fun keySeed(vm: JSONObject): ByteArray? {
        val s = J.str(vm.opt("key_seed")); if (s.isEmpty()) return null
        return Bytes.b64decode(s)
    }

    fun parseVideoModel(vm: JSONObject, vid: String, keySeed: ByteArray? = null): HGResolvedVideo {
        val duration = J.double(vm.opt("video_duration"))
        val expire = J.int64(vm.opt("url_expire"))
        val entries = ArrayList<JSONObject>()
        when (val vl = vm.opt("video_list")) {
            is JSONObject -> { val it = vl.keys(); while (it.hasNext()) { val k = it.next(); (vl.opt(k) as? JSONObject)?.let { entries.add(it) } } }
            is org.json.JSONArray -> for (i in 0 until vl.length()) (vl.opt(i) as? JSONObject)?.let { entries.add(it) }
        }
        data class Choice(val s: HGStream, val height: Int, val h264: Boolean)
        val choices = ArrayList<Choice>(); var vc2 = 0
        for (v in entries) {
            val meta = J.dict(v.opt("video_meta")); val enc = J.dict(v.opt("encrypt_info"))
            val codec = J.firstNonEmpty(J.str(meta.opt("codec_type")), J.str(v.opt("codec_type"))).lowercase()
            if (codec == "bytevc2" || J.str(v.opt("gear_des_key")).lowercase().contains("bytevc2")) { vc2++; continue }
            val primary = mediaURL(v.opt("main_url"), keySeed) ?: mediaURL(v.opt("backup_url"), keySeed) ?: continue
            var key = ""
            val spade = J.firstNonEmpty(J.str(enc.opt("spade_a")), J.str(v.opt("spade_a")))
            if (spade.isNotEmpty() || J.bool(enc.opt("encrypt")) || J.str(enc.opt("encryption_method")) == "cenc-aes-ctr") {
                key = HGSign.contentKey(spade) ?: continue
            }
            val w = if (J.int(meta.opt("vwidth")) > 0) J.int(meta.opt("vwidth")) else J.int(v.opt("vwidth"))
            val h = if (J.int(meta.opt("vheight")) > 0) J.int(meta.opt("vheight")) else J.int(v.opt("vheight"))
            var height = J.firstNonEmpty(J.str(meta.opt("definition")), J.str(v.opt("definition"))).lowercase().replace("p", "").toIntOrNull() ?: 0
            if (height == 0 && w > 0 && h > 0) height = minOf(w, h)
            if (height == 0) height = qualityMap[J.int(v.opt("quality_type"))]?.replace("p", "")?.toIntOrNull() ?: 0
            val q = if (height > 0) "${height}p" else null
            val size = J.firstPositive(v.opt("size"), v.opt("file_size")).toInt()
            val s = HGStream(
                streamId = "${q ?: ""}|$codec", quality = q, url = primary, key = key, width = w, height = h,
                codec = codec, size = size, bitrate = J.int(v.opt("bitrate")),
                kind = if (key.isEmpty()) HGStreamKind.mp4Clear else HGStreamKind.mp4Cenc,
            )
            choices.add(Choice(s, height, codec == "h264" || codec == "avc1"))
        }
        choices.sortWith(compareByDescending<Choice> { it.height }.thenByDescending { it.h264 }.thenByDescending { it.s.bitrate })
        if (choices.isEmpty()) {
            return HGResolvedVideo(vid, null, null, duration, expire,
                if (entries.isNotEmpty() && vc2 == entries.size) BYTEVC2 else "没有可用的媒体/密钥", emptyList())
        }
        val seen = HashSet<String>(); val streams = ArrayList<HGStream>()
        for (c in choices) if (seen.add(c.s.id)) streams.add(c.s)
        val top = choices.first().s
        return HGResolvedVideo(vid, top.url, top.key.ifEmpty { null }, duration, expire, null, streams)
    }

    fun mediaURL(value: Any?, seed: ByteArray? = null): String? {
        var address = J.str(value)
        if (!address.startsWith("http")) {
            Bytes.b64decode(address)?.let { address = String(it, Charsets.UTF_8) }
        }
        return if (address.startsWith("http://") || address.startsWith("https://")) address else null
    }

    // ---- parsing ----
    fun looksSID(s: String): Boolean = s.length in 15..25 && s.all { it.isDigit() }

    fun firstID(m: JSONObject, vararg keys: String): String {
        for (k in keys) {
            val s = J.str(m.opt(k)).trim()
            if (looksSID(s)) return s
        }
        return ""
    }

    fun parseVideoData(vd: JSONObject): HGSeries {
        val det = J.dict(vd.opt("video_detail"))
        val s = HGSeries(
            title = J.firstNonEmpty(J.str(vd.opt("raw_book_name")), J.str(det.opt("series_title")), J.str(vd.opt("title"))),
            cover = J.str(vd.opt("cover")),
        )
        s.subtitle = J.str(vd.opt("sub_title")).ifEmpty { null }
        s.author = J.str(vd.opt("copyright")).ifEmpty { null }
        s.episodeCount = J.int(vd.opt("episode_cnt"))
        s.duration = J.int(vd.opt("duration"))
        s.playCount = J.firstPositive(vd.opt("series_play_cnt"), vd.opt("play_cnt"), vd.opt("read_count"), det.opt("series_play_cnt"), det.opt("play_count"))
        s.followCount = J.firstPositive(vd.opt("followed_cnt"), vd.opt("follow_cnt"), vd.opt("collect_cnt"), det.opt("followed_cnt"))
        s.onlineTime = J.unix(vd.opt("create_time")).let { if (it == 0L) J.unix(vd.opt("first_online_time")) else it }
        if (s.episodeCount == 0) s.episodeCount = J.int(det.opt("episode_cnt"))
        (vd.opt("category_schema") as? String)?.let { schema ->
            (J.parseAny(schema) as? org.json.JSONArray)?.let { arr ->
                s.tags = (0 until arr.length()).mapNotNull { i ->
                    val m = arr.optJSONObject(i) ?: return@mapNotNull null
                    J.firstNonEmpty(J.str(m.opt("name")), J.str(m.opt("category_name"))).ifEmpty { null }
                }
            }
        }
        val al = J.dict(vd.opt("album_info"))
        if (al.length() > 0) {
            if (s.title.isEmpty()) s.title = J.str(al.opt("title"))
            val n = J.int(al.opt("episode_cnt")); if (n > 0) s.episodeCount = n
            if (s.cover.isEmpty()) s.cover = J.str(al.opt("cover"))
            s.seriesId = firstID(al, "album_id_str", "album_id")
        }
        if (s.seriesId.isEmpty()) s.seriesId = firstID(vd, "series_id", "seriesId", "series_id_str", "recommend_group_id")
        return s
    }

    fun celebrities(vd: JSONObject): List<String> {
        for (k in listOf("celebrities", "celebrity_list", "actors")) {
            val names = J.arr(vd.opt(k)).mapNotNull { e ->
                when (e) {
                    is String -> e
                    else -> {
                        val m = J.dict(e)
                        J.firstNonEmpty(J.str(m.opt("name")), J.str(m.opt("user_name")), J.str(m.opt("celebrity_name")), J.str(m.opt("role_name"))).ifEmpty { null }
                    }
                }
            }
            if (names.isNotEmpty()) return names.distinct()
        }
        return emptyList()
    }

    fun names(vararg vals: Any?): List<String> {
        val out = ArrayList<String>()
        for (v in vals) {
            when (v) {
                is org.json.JSONArray -> for (i in 0 until v.length()) {
                    val e = v.opt(i)
                    val n = (e as? String) ?: J.firstNonEmpty(J.str(J.dict(e).opt("name")), J.str(J.dict(e).opt("role_name")))
                    if (n.isNotEmpty() && n !in out) out.add(n)
                }
                is String -> if (v.isNotEmpty()) {
                    val cleaned = v.trim().trim('[', ']')
                    for (p in cleaned.split(Regex("[,/、|]"))) {
                        val n = p.trim(' ', '"', '\'')
                        if (n.isNotEmpty() && n !in out) out.add(n)
                    }
                }
            }
        }
        return out
    }
}
