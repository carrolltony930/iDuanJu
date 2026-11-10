package app.iduanju.core

import app.iduanju.core.hongguo.HGPlayerDevice
import app.iduanju.core.hongguo.HGRankGenre
import app.iduanju.core.hongguo.HGRankSub
import app.iduanju.core.hongguo.HGSign
import app.iduanju.core.hongguo.HongguoClient
import app.iduanju.core.util.Bytes
import app.iduanju.core.util.J
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Soft-reject research on the pure video_model endpoint (2026-11-11).
 *   ./gradlew :core:test -Plive=1 -Pprobe=1 --tests '*SoftRejectProbe*' [-PhgDevice=did,iid  (control arm, runtime only)]
 * Each arm = one fresh identity, N sequential requests 1.5 s apart; sequence printed as P/F/C…/H…/E.
 * No identity is written to source or disk.
 */
class SoftRejectProbeTest {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val n = (System.getProperty("idj.probeN") ?: "6").toInt()

    private fun digits(n: Int, first: IntRange = 1..9): String {
        val sb = StringBuilder().append(first.random()); while (sb.length < n) sb.append((0..9).random()); return sb.toString()
    }

    data class Reg(val did: String, val iid: String, val cdid: String, val openudid: String, val raw: String)

    /** device_register with the same 7.3.9.32 / Xiaomi 23127PN0CC profile the pure player request uses. */
    private fun registerPureProfile(): Reg {
        val cdid = UUID.randomUUID().toString(); val openudid = Bytes.hex(Bytes.random(8))
        val q = "aid=8662&app_name=novelread&version_code=73932&version_name=7.3.9.32&device_platform=android&os=android&ssmix=a" +
            "&language=zh&channel=xiaomi_8662_64&device_type=23127PN0CC&device_brand=Xiaomi&resolution=1200*2670&dpi=460" +
            "&host_abi=arm64-v8a&manifest_version_code=73932&update_version_code=73932&ac=wifi&app_type=normal&cpu_support64=true" +
            "&os_api=34&os_version=14&cdid=$cdid&openudid=$openudid"
        val header = mapOf(
            "display_name" to "红果短剧", "aid" to 8662, "channel" to "xiaomi_8662_64", "package" to "com.phoenix.read",
            "app_version" to "7.3.9.32", "version_code" to 73932, "manifest_version_code" to 73932, "update_version_code" to 73932,
            "sdk_version" to "3.9.5", "sdk_target_version" to 34, "os" to "Android", "os_version" to "14", "os_api" to 34,
            "device_model" to "23127PN0CC", "device_brand" to "Xiaomi", "device_manufacturer" to "Xiaomi", "cpu_abi" to "arm64-v8a",
            "release_build" to "UKQ1.230804.001", "density_dpi" to 460, "display_density" to "xxhdpi", "resolution" to "2670x1200",
            "language" to "zh", "timezone" to 8, "region" to "CN", "tz_name" to "Asia/Shanghai", "cdid" to cdid, "openudid" to openudid,
            "clientudid" to UUID.randomUUID().toString(), "google_aid" to "", "req_id" to UUID.randomUUID().toString(),
        )
        val body = J.encodeSorted(mapOf("magic_tag" to "ss_app_log", "header" to header, "_gen_time" to System.currentTimeMillis() / 1000))
        val rb = Request.Builder().url("https://log.snssdk.com/service/2/device_register/?$q").post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        for ((k, v) in HGSign.basicHeaders(q, body)) rb.header(k, v)
        rb.header("User-Agent", UA).header("sdk-version", "2")
        val raw = http.newCall(rb.build()).execute().use { it.body?.string() ?: "" }
        val j = J.parse(raw)
        val did = J.firstNonEmpty(J.str(j.opt("device_id_str")), J.str(j.opt("device_id")))
        val iid = J.firstNonEmpty(J.str(j.opt("install_id_str")), J.str(j.opt("install_id")))
        return Reg(did, iid, cdid, openudid, raw.take(160))
    }

    /** AppLog launch event + log_settings, as the official cold start does after device_register. */
    private fun activate(r: Reg): String {
        val q = "aid=8662&app_name=novelread&version_code=73932&version_name=7.3.9.32&device_platform=android&os=android" +
            "&channel=xiaomi_8662_64&device_type=23127PN0CC&device_brand=Xiaomi&device_id=${r.did}&iid=${r.iid}&cdid=${r.cdid}&openudid=${r.openudid}&ac=wifi"
        val now = System.currentTimeMillis()
        val header = mapOf("aid" to 8662, "app_version" to "7.3.9.32", "version_code" to 73932, "channel" to "xiaomi_8662_64",
            "device_id" to r.did, "install_id" to r.iid, "os" to "Android", "os_api" to 34, "device_model" to "23127PN0CC",
            "package" to "com.phoenix.read", "cdid" to r.cdid, "openudid" to r.openudid, "timezone" to 8)
        val sid = UUID.randomUUID().toString()
        val bodies = listOf(
            "https://log.snssdk.com/service/2/app_log/" to J.encodeSorted(mapOf("magic_tag" to "ss_app_log", "header" to header,
                "launch" to listOf(mapOf("datetime" to java.time.LocalDateTime.now().toString().replace('T', ' ').take(19), "session_id" to sid,
                    "local_time_ms" to now, "tea_event_index" to 1)), "time_sync" to mapOf("local_time" to now / 1000, "server_time" to now / 1000))),
            "https://log.snssdk.com/service/2/log_settings/" to J.encodeSorted(mapOf("magic_tag" to "ss_app_log", "header" to header, "_gen_time" to now / 1000)),
        )
        return bodies.joinToString(" ") { (u, b) ->
            val rb = Request.Builder().url("$u?$q").post(b.toRequestBody("application/json; charset=utf-8".toMediaType()))
            for ((k, v) in HGSign.basicHeaders(q, b)) rb.header(k, v)
            rb.header("User-Agent", UA).header("sdk-version", "2")
            try { http.newCall(rb.build()).execute().use { "${u.substringAfter("/2/").trimEnd('/')}=${it.code}/${(it.body?.string() ?: "").take(50)}" } } catch (e: Exception) { "err" }
        }
    }

    private fun seq(c: HongguoClient, vid: String, did: String, iid: String, cdid: String? = null, oud: String? = null): String =
        (0 until n).joinToString("") { i -> if (i > 0) Thread.sleep(1500); c.probePure(vid, 3, did, iid, cdid, oud) }

    @Test
    fun matrix() {
        assumeTrue(System.getProperty("idj.live") == "1" && System.getProperty("idj.probe") == "1")
        val c = HongguoClient(File("build/hg-probe").also { it.mkdirs() })
        val sid = c.rank(HGRankSub.hot, HGRankGenre.all).items.first().seriesId
        val vid = c.detail(sid, web = false).episodes.first().vid
        println("[probe] vid=$vid n=$n endpoint=pure video_model level=3")
        System.getProperty("idj.hgDevice")?.split(",")?.takeIf { it.size == 2 }?.let { p ->
            println("[probe] control(override, runtime only)          ${seq(c, vid, p[0].trim(), p[1].trim())}")
        }
        repeat(3) {
            val m = HGPlayerDevice.mint()
            println("[probe] mint15 iid=did+4096 #$it (did ${m.deviceId.take(3)}…)  ${seq(c, vid, m.deviceId, m.iid)}")
        }
        run {
            val m = HGPlayerDevice.mint()
            println("[probe] mint15 random cdid/openudid              ${seq(c, vid, m.deviceId, m.iid, UUID.randomUUID().toString(), Bytes.hex(Bytes.random(8)))}")
        }
        run {
            val d = digits(19); val i = digits(19)
            println("[probe] random19 unregistered                      ${seq(c, vid, d, i)}")
        }
        repeat(2) {
            val r = registerPureProfile()
            println("[probe] register(7.3.9.32 Xiaomi) #$it did.len=${r.did.length} iid.len=${r.iid.length}")
            if (r.did.isEmpty() || r.iid.isEmpty()) { println("[probe]   register failed: ${r.raw}"); return@repeat }
            println("[probe]   registered, no activation               ${seq(c, vid, r.did, r.iid, r.cdid, r.openudid)}")
        }
        run {
            val r = registerPureProfile()
            if (r.did.isEmpty()) { println("[probe] register failed: ${r.raw}"); return@run }
            println("[probe] register + activate: ${activate(r)}")
            Thread.sleep(3000)
            println("[probe]   registered+app_log+log_settings          ${seq(c, vid, r.did, r.iid, r.cdid, r.openudid)}")
        }
        run {
            val m = HGPlayerDevice.mint()
            val r = Reg(m.deviceId, m.iid, UUID.randomUUID().toString(), Bytes.hex(Bytes.random(8)), "")
            println("[probe] mint + activate: ${activate(r)}")
            Thread.sleep(3000)
            println("[probe]   mint15+app_log+log_settings              ${seq(c, vid, r.did, r.iid, r.cdid, r.openudid)}")
        }
        run {
            // long warm-up: does one fresh mint start being served after K rejects (2026-10-20 batch-era FFF→PPP)?
            val m = HGPlayerDevice.mint()
            val s = (0 until 16).joinToString("") { i -> if (i > 0) Thread.sleep(3000); c.probePure(vid, 3, m.deviceId, m.iid) }
            println("[probe] mint15 warm-up 16×3s                       $s")
        }
    }

    /** Round 2: a pure-profile registered device is served exactly once (PFFFFF). Characterise that allowance. */
    @Test
    fun firstPass() {
        assumeTrue(System.getProperty("idj.live") == "1" && System.getProperty("idj.probe") == "2")
        val c = HongguoClient(File("build/hg-probe").also { it.mkdirs() })
        val sid = c.rank(HGRankSub.hot, HGRankGenre.all).items.first().seriesId
        val eps = c.detail(sid, web = false).episodes
        val vid = eps.first().vid
        println("[probe2] vid=$vid eps=${eps.size}")
        val fresh = (0 until 6).joinToString("") { i ->
            if (i > 0) Thread.sleep(1500)
            val r = registerPureProfile(); if (r.did.isEmpty()) "R" else c.probePure(vid, 3, r.did, r.iid, r.cdid, r.openudid)
        }
        println("[probe2] new registration per request ×6          $fresh")
        run {
            val r = registerPureProfile()
            val s = StringBuilder(c.probePure(vid, 3, r.did, r.iid, r.cdid, r.openudid))
            for (w in listOf(15, 45, 90)) { Thread.sleep(w * 1000L); s.append(" +${w}s:").append(c.probePure(vid, 3, r.did, r.iid, r.cdid, r.openudid)) }
            println("[probe2] same device, recovery after waits          $s")
        }
        run {
            val r = registerPureProfile()
            val s = eps.take(4).joinToString("") { e -> Thread.sleep(1500); c.probePure(e.vid, 3, r.did, r.iid, r.cdid, r.openudid) }
            println("[probe2] same device, 4 different episodes         $s")
        }
        run {
            val r = registerPureProfile(); Thread.sleep(20_000)
            println("[probe2] register, wait 20s, then 3 requests        ${seq3(c, vid, r)}")
        }
        run {
            val r = registerPureProfile()
            val s = StringBuilder(c.probePure(vid, 3, r.did, r.iid, r.cdid, r.openudid))
            Thread.sleep(1500); s.append(c.probePure(vid, 3, r.did, r.iid)) // derived cdid/openudid instead of the registered ones
            println("[probe2] registered cdid then derived cdid          $s")
        }
        run {
            val r = registerPureProfile()
            println("[probe2] register, first request with derived cdid  ${c.probePure(vid, 3, r.did, r.iid)}")
        }
        run {
            val r = registerPureProfile()
            println("[probe2] register, first request level 4           ${c.probePure(vid, 4, r.did, r.iid, r.cdid, r.openudid)}")
        }
    }

    private fun seq3(c: HongguoClient, vid: String, r: Reg) =
        (0 until 3).joinToString("") { i -> if (i > 0) Thread.sleep(1500); c.probePure(vid, 3, r.did, r.iid, r.cdid, r.openudid) }

    /** Round 3: throughput of "one fresh registration per resolve" (spacing $idj.probeGap s), with a control interleaved. */
    @Test
    fun oneShotThroughput() {
        assumeTrue(System.getProperty("idj.live") == "1" && System.getProperty("idj.probe") == "3")
        val c = HongguoClient(File("build/hg-probe").also { it.mkdirs() })
        val sid = c.rank(HGRankSub.hot, HGRankGenre.all).items.first().seriesId
        val eps = c.detail(sid, web = false).episodes
        val gap = (System.getProperty("idj.probeGap") ?: "6").toLong()
        val ctl = System.getProperty("idj.hgDevice")?.split(",")?.takeIf { it.size == 2 }
        val seen = HashSet<String>()
        val s = StringBuilder(); val cs = StringBuilder()
        for (i in 0 until 12) {
            if (i > 0) Thread.sleep(gap * 1000)
            val r = registerPureProfile()
            val dup = !seen.add(r.did)
            s.append(if (r.did.isEmpty()) "R" else c.probePure(eps[i % eps.size].vid, 3, r.did, r.iid, r.cdid, r.openudid)).append(if (dup) "*" else "")
            ctl?.let { cs.append(c.probePure(eps[i % eps.size].vid, 3, it[0].trim(), it[1].trim())) }
        }
        println("[probe3] gap=${gap}s oneshot=$s control=$cs")
    }

    companion object {
        const val UA = "com.phoenix.read/73932 (Linux; U; Android 14; zh_CN; 23127PN0CC; Build/UKQ1.230804.001; Cronet/TTNetVersion:8d40f833 2026-03-03 QuicVersion:21ac1950 2025-11-18)"
    }
}
