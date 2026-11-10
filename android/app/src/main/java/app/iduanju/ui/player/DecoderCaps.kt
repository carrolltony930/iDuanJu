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

    /**
     * Same check ExoPlayer applies to the decoder it will actually use (first in Media3's ordered list):
     * any-decoder matching let a 1080p HEVC stream through on the emulator (c2 decoder said yes, ExoPlayer
     * picked OMX.google.hevc.decoder → NO_EXCEEDS_CAPABILITIES → ERROR_CODE_DECODING_FAILED).
     */
    @Synchronized
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun canPlay(s: HGStream): Boolean {
        val m = mime(s.codec) ?: return true
        if (s.width <= 0 || s.height <= 0) return true
        val k = "$m ${s.width}x${s.height}"
        return memo.getOrPut(k) {
            val first = runCatching { androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(m, false, false).firstOrNull() }.getOrNull()
            if (first != null) runCatching {
                first.isVideoSizeAndRateSupportedV21(s.width, s.height, 30.0) || first.isVideoSizeAndRateSupportedV21(s.height, s.width, 30.0)
            }.getOrDefault(false)
            else infos.any { ci ->
                ci.supportedTypes.any { it.equals(m, true) } && runCatching {
                    val vc = ci.getCapabilitiesForType(m).videoCapabilities
                    vc.areSizeAndRateSupported(s.width, s.height, 30.0) || vc.areSizeAndRateSupported(s.height, s.width, 30.0)
                }.getOrDefault(false)
            }
        }
    }

    fun pick(streams: List<HGStream>): HGStream? = (streams.firstOrNull { canPlay(it) } ?: streams.firstOrNull()).also { p ->
        android.util.Log.i("idj", "DecoderCaps.pick " + streams.joinToString { "${it.quality}/${it.codec}/${it.width}x${it.height}" } + " -> " + p?.let { "${it.quality}/${it.codec}" })
    }
}
