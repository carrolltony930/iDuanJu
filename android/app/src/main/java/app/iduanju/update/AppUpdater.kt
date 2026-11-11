package app.iduanju.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import app.iduanju.core.update.AppRelease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Checks GitHub Releases for a newer `*-android` build, downloads its APK and hands it to the system installer.
 * Auto checks (app start) are throttled and silent on failure; 我的 → 检查更新 reports every outcome.
 */
class AppUpdater(private val ctx: Context) {
    sealed interface Phase {
        data object Idle : Phase
        data class Downloading(val read: Long, val total: Long) : Phase
        data class Ready(val file: File) : Phase
        data class Failed(val message: String) : Phase
    }

    private val sp = ctx.getSharedPreferences("idj", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val dir get() = File(ctx.cacheDir, "updates").apply { mkdirs() }

    /** Release shown in the update dialog (null = no dialog). */
    var offer by mutableStateOf<AppRelease?>(null)
    var phase by mutableStateOf<Phase>(Phase.Idle)
    var checking by mutableStateOf(false)
        private set

    val currentVersion: List<Int> by lazy {
        val name = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty()
        AppRelease.parseVersion(name) ?: emptyList()
    }

    /** Launch check: at most every [AUTO_INTERVAL_MS], skips an ignored version, never reports errors. */
    suspend fun autoCheck() {
        val now = System.currentTimeMillis()
        if (now - sp.getLong(KEY_LAST, 0) < AUTO_INTERVAL_MS) return
        val r = runCatching { fetchNewer() }.getOrNull() ?: return
        sp.edit().putLong(KEY_LAST, now).apply()
        if (r.tag != sp.getString(KEY_IGNORED, null)) present(r)
    }

    /** 我的 → 检查更新: always queries, ignores 忽略此版本, returns a footer message when there is no dialog. */
    suspend fun manualCheck(): String? {
        if (checking) return null
        checking = true
        return try {
            val r = fetchNewer()
            sp.edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
            if (r == null) "已是最新版本（${currentVersion.joinToString(".")}）" else { present(r); null }
        } catch (e: Exception) {
            "检查更新失败：${e.message ?: e.javaClass.simpleName}"
        } finally { checking = false }
    }

    fun ignore(r: AppRelease) { sp.edit().putString(KEY_IGNORED, r.tag).apply(); dismiss() }
    fun dismiss() { offer = null; if (phase !is Phase.Downloading) phase = Phase.Idle }

    private fun present(r: AppRelease) {
        offer = r
        val f = apkFile(r)
        phase = if (f.isFile && f.length() == r.apkSize) Phase.Ready(f) else Phase.Idle
    }

    private suspend fun fetchNewer(): AppRelease? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(AppRelease.API)
            .header("Accept", "application/vnd.github+json").header("User-Agent", "iDuanJu-Android").build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 403 || resp.code == 429) error("GitHub 请求过于频繁，请稍后再试")
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            AppRelease.newestAndroid(resp.body!!.string())
        }?.takeIf { AppRelease.compare(it.version, currentVersion) > 0 }
    }

    private fun apkFile(r: AppRelease) = File(dir, "iDuanJu-${r.versionName}-android.apk")

    suspend fun download(r: AppRelease) {
        if (phase is Phase.Downloading) return
        phase = Phase.Downloading(0, r.apkSize)
        phase = runCatching {
            withContext(Dispatchers.IO) {
                dir.listFiles()?.forEach { it.delete() } // old APKs
                val part = File(dir, "download.part")
                val md = MessageDigest.getInstance("SHA-256")
                http.newCall(Request.Builder().url(r.apkUrl).header("User-Agent", "iDuanJu-Android").build()).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val body = resp.body!!
                    val total = body.contentLength().takeIf { it > 0 } ?: r.apkSize
                    var read = 0L
                    var lastPost = 0L
                    body.byteStream().use { inp ->
                        part.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = inp.read(buf); if (n < 0) break
                                out.write(buf, 0, n); md.update(buf, 0, n); read += n
                                if (read - lastPost > 256 * 1024) { lastPost = read; withContext(Dispatchers.Main) { phase = Phase.Downloading(read, total) } }
                            }
                        }
                    }
                }
                val hex = md.digest().joinToString("") { "%02x".format(it) }
                if (r.sha256 != null && hex != r.sha256) { part.delete(); error("校验失败（SHA-256 不一致）") }
                val f = apkFile(r); part.renameTo(f); Phase.Ready(f)
            }
        }.getOrElse { Phase.Failed("下载失败：${it.message ?: it.javaClass.simpleName}") }
    }

    /** True if the system installer was opened; false if we sent the user to 「安装未知应用」 first. */
    fun install(file: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", file)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    companion object {
        private const val KEY_LAST = "update.lastCheck"
        private const val KEY_IGNORED = "update.ignoredTag"
        const val AUTO_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }
}
