package app.iduanju.core.hongguo

import app.iduanju.core.util.Bytes
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Port of HGPlayerDevice.swift. Default ring = this install's own minted 15-digit device
 * (no hard-coded IDs). Soft-reject recovery remints. Optional override via [overrideProvider].
 */
object HGPlayerDevice {
    data class Device(val deviceId: String, val iid: String, val source: String? = null, val mintedAt: String? = null)

    private const val IID_OFFSET = 4096L
    private const val CHUNK_MAX = 3
    private val index = AtomicInteger(0)
    private val minted = AtomicReference<Device?>(null)
    @Volatile private var mintLoaded = false
    @Volatile var storageDir: File? = null
    /** Optional override like public Swift `stv.hg.purePlayerDevice` = "did,iid". Never hardcode. */
    @Volatile var overrideProvider: (() -> Pair<String, String>?)? = null

    fun queryPart(): String {
        overrideProvider?.invoke()?.let { (d, i) -> if (d.isNotEmpty() && i.isNotEmpty()) return "iid=$i&device_id=$d" }
        var d = current()
        if (d.deviceId.isEmpty() || d.iid.isEmpty()) d = mintedDevice() ?: mint()
        return "iid=${d.iid}&device_id=${d.deviceId}"
    }

    fun current(): Device {
        overrideProvider?.invoke()?.let { (d, i) ->
            if (d.isNotEmpty() && i.isNotEmpty()) return Device(d, i, "override")
        }
        val ring = deviceRing()
        if (ring.isEmpty()) return mint()
        var i = index.get(); if (i >= ring.size) { index.set(0); i = 0 }
        return ring[i]
    }

    fun remint(): Device {
        val d = mint(); saveFile(d)
        minted.set(d); mintLoaded = true; index.set(0)
        return d
    }

    fun rotate(): Device = remint()
    fun devicesForChunk(): Int = CHUNK_MAX
    fun deviceRing(): List<Device> = listOf(mintedDevice() ?: mint())

    fun mintedDevice(): Device? {
        if (mintLoaded) return minted.get()
        loadFile()?.let { minted.set(it); mintLoaded = true; return it }
        val d = mint(); saveFile(d); minted.set(d); mintLoaded = true; return d
    }

    fun mint(): Device {
        val digits = Bytes.random(15)
        val sb = StringBuilder(); sb.append(5 + (digits[0].toInt() and 0xff) % 5)
        for (i in 1 until 15) sb.append((digits[i].toInt() and 0xff) % 10)
        val s = sb.toString()
        val n = s.toLongOrNull() ?: (500_000_000_000_000L + (System.currentTimeMillis() % 499_999_999_999_999L))
        val did = if (s.toLongOrNull() != null) s else n.toString()
        val iid = (if (s.toLongOrNull() != null) s.toLong() else n) + IID_OFFSET
        return Device(did, iid.toString(), "mint", Instant.now().toString())
    }

    private fun file(): File? = storageDir?.let { File(it, "hongguo_player_device.json") }
    private fun bak(): File? = file()?.let { File(it.path + ".bak") }

    private fun loadFile(): Device? {
        for (c in listOfNotNull(file(), bak())) {
            if (!c.exists()) continue
            try {
                val j = JSONObject(c.readText())
                val did = j.optString("device_id"); val iid = j.optString("iid")
                if (did.isNotEmpty() && iid.isNotEmpty())
                    return Device(did, iid, j.optString("source").ifEmpty { "file" }, j.optString("minted_at").ifEmpty { null })
            } catch (_: Exception) {}
        }
        return null
    }

    private fun saveFile(d: Device) {
        val f = file() ?: return
        f.parentFile?.mkdirs()
        if (f.exists()) bak()?.writeBytes(f.readBytes())
        f.writeText(JSONObject(mapOf("device_id" to d.deviceId, "iid" to d.iid, "source" to d.source, "minted_at" to d.mintedAt)).toString())
    }

    fun resetForTests() { index.set(0); minted.set(null); mintLoaded = false }
}
