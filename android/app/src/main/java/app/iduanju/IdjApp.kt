package app.iduanju

import android.app.Application
import app.iduanju.core.hongguo.HGPlayerDevice
import app.iduanju.core.hongguo.HongguoClient
import app.iduanju.data.LibraryStore
import app.iduanju.data.SettingsStore

class IdjApp : Application() {
    lateinit var settings: SettingsStore
        private set
    lateinit var hongguo: HongguoClient
        private set
    lateinit var library: LibraryStore
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SettingsStore(this)
        HGPlayerDevice.storageDir = filesDir
        HGPlayerDevice.overrideProvider = {
            settings.playerDeviceOverride()?.let { it }
        }
        hongguo = HongguoClient(filesDir)
        library = LibraryStore(this)
    }

    companion object {
        lateinit var instance: IdjApp
            private set
    }
}
