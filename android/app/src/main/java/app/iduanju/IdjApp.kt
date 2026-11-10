package app.iduanju

import android.app.Application
import app.iduanju.core.hongguo.HGPlayerDevice
import app.iduanju.core.hongguo.HongguoClient
import app.iduanju.data.LibraryStore
import app.iduanju.data.SettingsStore

class IdjApp : Application(), coil.ImageLoaderFactory {
    lateinit var settings: SettingsStore
        private set
    lateinit var hongguo: HongguoClient
        private set
    lateinit var library: LibraryStore
        private set
    lateinit var cms: app.iduanju.data.CMSStore
    lateinit var plugins: app.iduanju.plugin.PluginStore
        private set
    lateinit var tg: app.iduanju.tg.TGStore
        private set
    lateinit var manga: app.iduanju.manga.MangaStore
        private set
    lateinit var huangguo: app.iduanju.core.huangguo.HuangguoClient
        private set

    /** Covers: 黄果 CDNs obfuscate images (AES / XOR) — decoded in an OkHttp interceptor. */
    override fun newImageLoader(): coil.ImageLoader = coil.ImageLoader.Builder(this)
        .okHttpClient { okhttp3.OkHttpClient.Builder().addInterceptor(app.iduanju.huangguo.CoverDecryptInterceptor).build() }
        .components { add(app.iduanju.plugin.PluginImageFetcher.Factory()) }
        .crossfade(true).build()

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SettingsStore(this)
        HGPlayerDevice.storageDir = filesDir
        app.iduanju.core.hongguo.HGNet.playerUsesSystemProxy = settings.playerUsesSystemProxy
        HGPlayerDevice.overrideProvider = {
            settings.playerDeviceOverride()?.let { it }
        }
        hongguo = HongguoClient(filesDir)
        library = LibraryStore(this)
        cms = app.iduanju.data.CMSStore(this)
        plugins = app.iduanju.plugin.PluginStore(this)
        tg = app.iduanju.tg.TGStore(this)
        manga = app.iduanju.manga.MangaStore(this)
        huangguo = app.iduanju.core.huangguo.HuangguoClient(filesDir, app.iduanju.huangguo.PrefsMap(this))
        app.iduanju.core.hls.HLSAdFilter.enabled = settings.adStripEnabled
    }

    companion object {
        lateinit var instance: IdjApp
            private set
    }
}
