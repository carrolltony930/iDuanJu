package app.iduanju.ui.player

import android.media.MediaCodecList
import app.iduanju.core.hongguo.HGStream

/**
 * iOS always has HEVC in hardware; Android devices (and the emulator's software decoder) may not
 * handle 1080p HEVC. Pick the best stream a local decoder reports it can play at its size and ~30 fps.
 */
object DecoderCaps {
    private val infos by lazy { runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder } }.getOrDefault(emptyList()) }
    private val memo = HashMap<String, Boolean>()

    fun mime(codec: String): String? = when (codec.lowercase()) {
        "bytevc1", "hevc", "h265", "hvc1", "hev1" -> "video/hevc"
        "h264", "avc1", "avc" -> "video/avc"
        else -> null
    }

    @Synchronized
    fun canPlay(s: HGStream): Boolean {
        val m = mime(s.codec) ?: return true
        if (s.width <= 0 || s.height <= 0) return true
        val k = "$m ${s.width}x${s.height}"
        return memo.getOrPut(k) {
            infos.any { ci ->
                ci.supportedTypes.any { it.equals(m, true) } && runCatching {
                    val vc = ci.getCapabilitiesForType(m).videoCapabilities
                    vc.areSizeAndRateSupported(s.width, s.height, 30.0) || vc.areSizeAndRateSupported(s.height, s.width, 30.0)
                }.getOrDefault(false)
            }
        }
    }

    fun pick(streams: List<HGStream>): HGStream? = streams.firstOrNull { canPlay(it) } ?: streams.firstOrNull()
}
