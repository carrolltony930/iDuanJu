package app.iduanju.core.hongguo

import app.iduanju.core.util.Bytes
import app.iduanju.core.util.J
import app.iduanju.core.util.ProviderException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Port of HGDevice.swift: anonymous device_register, persisted on-device. */
data class HGDevice(
    var deviceId: String,
    var iid: String,
    var cdid: String,
    var openudid: String,
    var sessionEntropy: String,
    /** Unix ms of device_register; 0 = file from an older build (age unknown → stale). */
    var registeredAt: Long = 0L,
) {
    /** video_model soft-rejects a registered device ~9–10 min after device_register (probe 2026-11-11). */
    val isStaleForPlayback: Boolean
        get() = registeredAt <= 0L || System.currentTimeMillis() - registeredAt > PLAYBACK_MAX_AGE_MS

    val entropy: ByteArray get() = Bytes.fromHex(sessionEntropy) ?: ByteArray(20)

    fun save(dir: File) {
        dir.mkdirs()
        File(dir, "hongguo_device.json").writeText(
            JSONObject(mapOf(
                "deviceId" to deviceId, "iid" to iid, "cdid" to cdid,
                "openudid" to openudid, "sessionEntropy" to sessionEntropy, "registeredAt" to registeredAt,
            )).toString()
        )
    }

    companion object {
        const val PLAYBACK_MAX_AGE_MS = 7 * 60 * 1000L
        const val appVersion = "7.3.3.32"
        const val versionCode = "73332"
        const val ua =
            "com.phoenix.read/72133 (Linux; U; Android 10; zh_CN_#Hans; EML-AL00; Build/HUAWEIEML-AL00; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)"

        fun load(dir: File): HGDevice? {
            val f = File(dir, "hongguo_device.json")
            if (!f.exists()) return null
            return try {
                val j = JSONObject(f.readText())
                val d = HGDevice(
                    j.optString("deviceId"), j.optString("iid"), j.optString("cdid"),
                    j.optString("openudid"), j.optString("sessionEntropy"), j.optLong("registeredAt", 0L),
                )
                if (d.deviceId.isEmpty() || d.iid.isEmpty() || d.cdid.isEmpty()) null else d
            } catch (_: Exception) { null }
        }

        fun reset(dir: File) { File(dir, "hongguo_device.json").delete() }

        private fun randomDigits(n: Int): String {
            val sb = StringBuilder(); sb.append(Bytes.randInt(1, 10))
            while (sb.length < n) sb.append(Bytes.randInt(0, 10))
            return sb.toString()
        }

        fun register(http: OkHttpClient, dir: File): HGDevice {
            var dev = HGDevice(
                randomDigits(19), randomDigits(19), UUID.randomUUID().toString().lowercase(),
                Bytes.hex(Bytes.random(8)), Bytes.hex(Bytes.random(20)),
            )
            val query = "aid=8662&app_name=novelread&version_code=$versionCode&version_name=$appVersion" +
                "&device_platform=android&os=android&ssmix=a&language=zh&channel=ry_hongguo_offlinestore_04" +
                "&device_type=sdk_gphone64_arm64&device_brand=google&resolution=1080*2400&dpi=420&host_abi=arm64-v8a" +
                "&manifest_version_code=$versionCode&update_version_code=$versionCode" +
                "&ac=wifi&app_type=normal&cpu_support64=true" +
                "&device_id=${dev.deviceId}&iid=${dev.iid}&cdid=${dev.cdid}&openudid=${dev.openudid}"
            val header = mapOf(
                "display_name" to "红果短剧", "aid" to 8662, "channel" to "ry_hongguo_offlinestore_04",
                "package" to "com.phoenix.read", "app_version" to appVersion, "version_code" to 73332,
                "manifest_version_code" to 73332, "update_version_code" to 73332, "sdk_version" to "3.9.5",
                "sdk_target_version" to 35, "os" to "Android", "os_version" to "15", "os_api" to 35,
                "device_model" to "sdk_gphone64_arm64", "device_brand" to "google", "device_manufacturer" to "Google",
                "cpu_abi" to "arm64-v8a", "release_build" to "AE3A.240806.043", "density_dpi" to 420,
                "display_density" to "xhdpi", "resolution" to "1080x2400", "language" to "zh", "timezone" to 8,
                "region" to "CN", "tz_name" to "Asia/Shanghai", "cdid" to dev.cdid, "openudid" to dev.openudid,
                "clientudid" to UUID.randomUUID().toString().lowercase(), "google_aid" to "",
                "req_id" to UUID.randomUUID().toString().lowercase(),
            )
            val body = J.encodeSorted(mapOf("magic_tag" to "ss_app_log", "header" to header, "_gen_time" to (System.currentTimeMillis() / 1000)))
            val rb = body.toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder().url("https://log.snssdk.com/service/2/device_register/?$query").post(rb)
            for ((k, v) in HGSign.basicHeaders(query, body)) req.header(k, v)
            req.header("User-Agent", ua).header("sdk-version", "2")
            val resp = http.newCall(req.build()).execute()
            val data = resp.body?.bytes() ?: ByteArray(0)
            val j = J.parse(data)
            val did = J.firstNonEmpty(J.str(j.opt("device_id_str")), J.str(j.opt("device_id")))
            var iid = J.firstNonEmpty(J.str(j.opt("install_id_str")), J.str(j.opt("install_id")))
            if (iid.isEmpty() || iid == "0") iid = J.str(j.opt("iid"))
            if (did.isEmpty() || did == "0" || iid.isEmpty() || iid == "0") {
                throw ProviderException.upstream("device_register 返回无效设备 ID")
            }
            dev = dev.copy(deviceId = did, iid = iid, registeredAt = System.currentTimeMillis())
            dev.save(dir)
            return dev
        }

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true).build()
    }
}
