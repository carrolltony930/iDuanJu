package app.iduanju.core.hongguo

import app.iduanju.core.util.Bytes
import app.iduanju.core.util.J
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

/**
 * "One-shot" pure-player identity (research 2026-11-11, see android/PROGRESS.md §soft-reject):
 * a device registered at log.snssdk.com with the SAME 7.3.9.32 / Xiaomi 23127PN0CC profile the pure
 * video_model request carries is served exactly once (P), then soft-rejected (200 + 0 bytes) for good.
 * Locally minted (never registered) devices are rejected from the first request. So, with no override
 * configured, each resolve registers a fresh identity. Nothing is hard-coded or persisted.
 */
object HGOneShotDevice {
    data class Identity(val did: String, val iid: String, val cdid: String, val openudid: String)

    const val UA = "com.phoenix.read/73932 (Linux; U; Android 14; zh_CN; 23127PN0CC; Build/UKQ1.230804.001; Cronet/TTNetVersion:8d40f833 2026-03-03 QuicVersion:21ac1950 2025-11-18)"

    @Volatile var enabled: Boolean = true

    fun register(http: OkHttpClient): Identity? {
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
        val rb = Request.Builder().url("https://log.snssdk.com/service/2/device_register/?$q")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        for ((k, v) in HGSign.basicHeaders(q, body)) rb.header(k, v)
        rb.header("User-Agent", UA).header("sdk-version", "2")
        val raw = try { http.newCall(rb.build()).execute().use { it.body?.string() ?: "" } } catch (e: Exception) { return null }
        val j = try { J.parse(raw) } catch (e: Exception) { return null }
        val did = J.firstNonEmpty(J.str(j.opt("device_id_str")), J.str(j.opt("device_id")))
        val iid = J.firstNonEmpty(J.str(j.opt("install_id_str")), J.str(j.opt("install_id")))
        if (did.isEmpty() || did == "0" || iid.isEmpty() || iid == "0") return null
        return Identity(did, iid, cdid, openudid)
    }
}
