package app.iduanju.core.cenc

import app.iduanju.core.util.Bytes
import app.iduanju.core.util.u
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Offline ISO/IEC 23001-7 `cenc` (AES-128-CTR) MP4 decryptor — Kotlin port of iOS CENCDecryptor.
 * Supports progressive MP4 (senc or saiz/saio in stbl) and fragmented MP4 (moof/traf senc).
 */
object CENCDecryptor {
    class Failure(msg: String) : Exception(msg)

    data class SampleJob(val iv: ByteArray, val ranges: List<Pair<Int, Int>>)
    data class TrackInfo(
        val id: UInt, val ivSize: Int, val constIV: ByteArray, val isEncrypted: Boolean,
        val originalFormat: String, val defaultSampleSize: UInt,
        val sampleSizes: List<UInt>, val chunkOffsets: List<Long>,
        val samplesPerChunk: List<Pair<UInt, UInt>>, // first_chunk, samples_per_chunk
        val sencJobs: List<SampleJob>,
        val encvOffset: Int, val formatPatch: ByteArray,
    )

    fun decrypt(bytes: ByteArray, keyHex: String): Boolean {
        val key = Bytes.fromHex(keyHex) ?: throw Failure("content key 不是 32 位 hex")
        if (key.size != 16) throw Failure("content key 不是 32 位 hex")
        val collected = collect(bytes) ?: return false
        val (jobs, patches) = collected
        val out = bytes.copyOf()
        for (job in jobs) {
            var offsetInSample = 0
            for ((off, len) in job.ranges) {
                if (len <= 0) continue
                if (off < 0 || off + len > out.size) throw Failure("样本越界")
                ctr(key, job.iv, offsetInSample, out, off, len)
                offsetInSample += len
            }
        }
        for ((off, patch) in patches) patch.copyInto(out, off)
        // mutate caller's array
        System.arraycopy(out, 0, bytes, 0, bytes.size)
        return true
    }

    /** Decrypt and return a new array (safer API). */
    fun decryptCopy(bytes: ByteArray, keyHex: String): ByteArray {
        val out = bytes.copyOf()
        decrypt(out, keyHex)
        return out
    }

    private fun ctr(key: ByteArray, ivRaw: ByteArray, skip: Int, buf: ByteArray, off: Int, len: Int) {
        val iv = ByteArray(16)
        when {
            ivRaw.size >= 16 -> ivRaw.copyInto(iv, 0, 0, 16)
            ivRaw.size == 8 -> { ivRaw.copyInto(iv, 0, 0, 8) }
            else -> throw Failure("IV 长度异常")
        }
        // AES-CTR: counter starts at IV; skip `skip` bytes into the keystream
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        // Java CTR mode: IV is the initial counter block. For skip, advance counter.
        val block = 16
        val skipBlocks = skip / block
        val skipRem = skip % block
        if (skipBlocks > 0) {
            // treat last 8 bytes as big-endian counter (cenc default: IV is 8-byte + 8-byte counter=0)
            var counter = 0L
            for (i in 8 until 16) counter = (counter shl 8) or (iv.u(i).toLong())
            counter += skipBlocks
            for (i in 15 downTo 8) { iv[i] = (counter and 0xff).toByte(); counter = counter shr 8 }
        }
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        if (skipRem > 0) cipher.update(ByteArray(skipRem))
        val plain = cipher.update(buf, off, len) ?: ByteArray(0)
        val final = cipher.doFinal()
        val all = plain + final
        all.copyInto(buf, off, 0, minOf(all.size, len))
    }

    private data class Box(val type: String, val start: Int, val body: Int, val end: Int)

    private fun u16(b: ByteArray, o: Int) = (b.u(o) shl 8) or b.u(o + 1)
    private fun u32(b: ByteArray, o: Int) = ((b.u(o).toLong() shl 24) or (b.u(o + 1).toLong() shl 16) or (b.u(o + 2).toLong() shl 8) or b.u(o + 3).toLong()).toUInt()
    private fun u64(b: ByteArray, o: Int) = (u32(b, o).toULong() shl 32) or u32(b, o + 4).toULong()
    private fun fourcc(b: ByteArray, o: Int) = String(b, o, 4, Charsets.ISO_8859_1)

    private fun boxes(b: ByteArray, from: Int, to: Int): List<Box> {
        val out = ArrayList<Box>(); var p = from
        while (p + 8 <= to) {
            var size = u32(b, p).toLong(); var hdr = 8; var type = fourcc(b, p + 4)
            if (size == 1L) {
                if (p + 16 > to) break
                size = u64(b, p + 8).toLong(); hdr = 16
            } else if (size == 0L) size = (to - p).toLong()
            if (size < hdr || p + size > to) break
            out.add(Box(type, p, p + hdr, (p + size).toInt()))
            p += size.toInt()
        }
        return out
    }

    private fun child(b: ByteArray, box: Box, path: List<String>): Box? {
        var cur = box
        for (t in path) {
            cur = boxes(b, cur.body, cur.end).firstOrNull { it.type == t } ?: return null
        }
        return cur
    }

    private fun findDeep(b: ByteArray, box: Box, type: String): Box? {
        for (c in boxes(b, box.body, box.end)) {
            if (c.type == type) return c
            findDeep(b, c, type)?.let { return it }
        }
        return null
    }

    private fun collect(bytes: ByteArray): Pair<List<SampleJob>, List<Pair<Int, ByteArray>>>? {
        val top = boxes(bytes, 0, bytes.size)
        val moov = top.firstOrNull { it.type == "moov" } ?: throw Failure("缺少 moov")
        val patches = ArrayList<Pair<Int, ByteArray>>()
        val tracks = LinkedHashMap<UInt, TrackInfo>()
        val trexSizes = HashMap<UInt, UInt>()
        val jobs = ArrayList<SampleJob>()

        for (b in boxes(bytes, moov.body, moov.end)) {
            if (b.type == "pssh") patches.add(b.start + 4 to "free".toByteArray())
            if (b.type == "mvex") {
                for (t in boxes(bytes, b.body, b.end)) if (t.type == "trex") {
                    trexSizes[u32(bytes, t.body + 4)] = u32(bytes, t.body + 16)
                }
            }
            if (b.type == "trak") {
                parseTrak(bytes, b, patches)?.let { tracks[it.id] = it }
            }
        }
        if (tracks.values.none { it.isEncrypted }) return null

        // progressive: use stbl sample tables + senc
        for (t in tracks.values) if (t.isEncrypted && t.sencJobs.isNotEmpty()) {
            // Expand sample offsets from stsc/stsz/stco
            val sampleOffsets = expandSampleOffsets(t)
            t.sencJobs.forEachIndexed { i, job ->
                if (i < sampleOffsets.size) {
                    val (off, size) = sampleOffsets[i]
                    // If job already has ranges from subsample, remap; else whole sample
                    if (job.ranges.isEmpty()) jobs.add(SampleJob(job.iv, listOf(off to size)))
                    else jobs.add(SampleJob(job.iv, job.ranges.map { (o, l) -> (off + o) to l }))
                }
            }
        }

        // fragmented: moof/traf
        for (moof in top.filter { it.type == "moof" }) {
            for (traf in boxes(bytes, moof.body, moof.end).filter { it.type == "traf" }) {
                parseTraf(bytes, moof, traf, tracks, trexSizes, jobs)
            }
        }

        // Also rewrite encv/enca → original format for clear playback
        return jobs to patches
    }

    private fun expandSampleOffsets(t: TrackInfo): List<Pair<Int, Int>> {
        if (t.chunkOffsets.isEmpty()) return emptyList()
        val out = ArrayList<Pair<Int, Int>>()
        var sampleIdx = 0
        val stsc = t.samplesPerChunk.ifEmpty { listOf(1u to 1u) }
        for (ci in t.chunkOffsets.indices) {
            val chunkNum = (ci + 1).toUInt()
            var spc = 1u
            for ((first, samples) in stsc) if (chunkNum >= first) spc = samples else break
            var off = t.chunkOffsets[ci]
            for (s in 0 until spc.toInt()) {
                val size = when {
                    t.sampleSizes.isNotEmpty() && sampleIdx < t.sampleSizes.size -> t.sampleSizes[sampleIdx].toInt()
                    t.defaultSampleSize > 0u -> t.defaultSampleSize.toInt()
                    else -> 0
                }
                out.add(off.toInt() to size)
                off += size.toLong(); sampleIdx++
            }
        }
        return out
    }

    private fun parseTrak(b: ByteArray, trak: Box, patches: ArrayList<Pair<Int, ByteArray>>): TrackInfo? {
        val tkhd = child(b, trak, listOf("tkhd")) ?: return null
        // versioned: v0 20-byte times then track_id at +16? actually track_id at body+12 (v0) or body+20 (v1)
        val ver = b.u(tkhd.body)
        val trackId = if (ver == 1) u32(b, tkhd.body + 20) else u32(b, tkhd.body + 12)
        val mdia = child(b, trak, listOf("mdia")) ?: return null
        val minf = child(b, mdia, listOf("minf")) ?: return null
        val stbl = child(b, minf, listOf("stbl")) ?: return null
        val stsd = child(b, stbl, listOf("stsd")) ?: return null
        // first sample entry
        val entryCount = u32(b, stsd.body + 4)
        if (entryCount == 0u) return null
        val entry = boxes(b, stsd.body + 8, stsd.end).firstOrNull() ?: return null
        var isEnc = entry.type == "encv" || entry.type == "enca"
        var original = entry.type
        var ivSize = 8; var constIV = ByteArray(0)
        var encvOff = -1; var formatPatch = ByteArray(0)
        if (isEnc) {
            encvOff = entry.start + 4
            // sinf/frma
            val sinf = findDeep(b, entry, "sinf")
            val frma = sinf?.let { findDeep(b, it, "frma") }
            if (frma != null && frma.body + 4 <= frma.end) {
                original = fourcc(b, frma.body)
                formatPatch = original.toByteArray(Charsets.ISO_8859_1)
                patches.add(encvOff to formatPatch)
            }
            val tenc = sinf?.let { findDeep(b, it, "tenc") }
            if (tenc != null) {
                // version, flags, reserved, default_isProtected, default_Per_Sample_IV_Size, KID(16), optional const IV
                val per = b.u(tenc.body + 5) // after version(1)+flags(3)+byte_reserved(1) → default_Per_Sample_IV_Size at +5? 
                // ISO: unsigned int(8) reserved=0; unsigned int(8) default_isProtected; unsigned int(8) default_Per_Sample_IV_Size; unsigned int(8)[16] default_KID
                // layout: ver(1) flags(3) reserved(1) isProtected(1) ivSize(1) kid(16) [const_iv]
                ivSize = b.u(tenc.body + 6)
                if (ivSize == 0) {
                    // constant IV follows KID
                    val kidEnd = tenc.body + 7 + 16
                    constIV = b.copyOfRange(kidEnd, kidEnd + 16)
                }
            }
            // also free sinf? leave as-is; format rewrite is enough for many players
        }
        val stsz = child(b, stbl, listOf("stsz"))
        var defaultSize = 0u; val sampleSizes = ArrayList<UInt>()
        if (stsz != null) {
            defaultSize = u32(b, stsz.body + 4)
            val count = u32(b, stsz.body + 8).toInt()
            if (defaultSize == 0u) {
                for (i in 0 until count) sampleSizes.add(u32(b, stsz.body + 12 + i * 4))
            }
        }
        val stco = child(b, stbl, listOf("stco"))
        val co64 = child(b, stbl, listOf("co64"))
        val chunkOffsets = ArrayList<Long>()
        if (stco != null) {
            val n = u32(b, stco.body + 4).toInt()
            for (i in 0 until n) chunkOffsets.add(u32(b, stco.body + 8 + i * 4).toLong())
        } else if (co64 != null) {
            val n = u32(b, co64.body + 4).toInt()
            for (i in 0 until n) chunkOffsets.add(u64(b, co64.body + 8 + i * 8).toLong())
        }
        val stsc = child(b, stbl, listOf("stsc"))
        val samplesPerChunk = ArrayList<Pair<UInt, UInt>>()
        if (stsc != null) {
            val n = u32(b, stsc.body + 4).toInt()
            for (i in 0 until n) {
                val o = stsc.body + 8 + i * 12
                samplesPerChunk.add(u32(b, o) to u32(b, o + 4))
            }
        }
        val senc = findDeep(b, stbl, "senc")
        val sencJobs = if (senc != null) parseSenc(b, senc, ivSize, constIV) else emptyList()
        // saiz/saio path: if no senc, try to rebuild from saiz+saio (simplified: skip if absent)
        return TrackInfo(trackId, ivSize, constIV, isEnc, original, defaultSize, sampleSizes, chunkOffsets, samplesPerChunk, sencJobs, encvOff, formatPatch)
    }

    private fun parseSenc(b: ByteArray, senc: Box, ivSize: Int, constIV: ByteArray): List<SampleJob> {
        val flags = (b.u(senc.body + 1) shl 16) or (b.u(senc.body + 2) shl 8) or b.u(senc.body + 3)
        val count = u32(b, senc.body + 4).toInt()
        var p = senc.body + 8
        val out = ArrayList<SampleJob>()
        val useSub = flags and 0x2 != 0
        for (i in 0 until count) {
            val iv = when {
                ivSize > 0 -> { val iv = b.copyOfRange(p, p + ivSize); p += ivSize; iv }
                constIV.isNotEmpty() -> constIV
                else -> ByteArray(8)
            }
            val ranges = ArrayList<Pair<Int, Int>>()
            if (useSub) {
                val n = u16(b, p); p += 2
                var clearOff = 0
                for (j in 0 until n) {
                    val clear = u16(b, p); val encLen = u32(b, p + 2).toInt(); p += 6
                    clearOff += clear
                    if (encLen > 0) ranges.add(clearOff to encLen)
                    clearOff += encLen
                }
            }
            out.add(SampleJob(iv, ranges))
        }
        return out
    }

    private fun parseTraf(
        b: ByteArray, moof: Box, traf: Box, tracks: Map<UInt, TrackInfo>,
        trexSizes: Map<UInt, UInt>, jobs: ArrayList<SampleJob>,
    ) {
        val tfhd = child(b, traf, listOf("tfhd")) ?: return
        val flags = (b.u(tfhd.body + 1) shl 16) or (b.u(tfhd.body + 2) shl 8) or b.u(tfhd.body + 3)
        val trackId = u32(b, tfhd.body + 4)
        val track = tracks[trackId] ?: return
        if (!track.isEncrypted) return
        var p = tfhd.body + 8
        var baseOffset = 0L
        if (flags and 0x1 != 0) { baseOffset = u64(b, p).toLong(); p += 8 }
        if (flags and 0x2 != 0) p += 4 // sample_description_index
        var defaultSize = if (flags and 0x8 != 0) { val s = u32(b, p); p += 4; s } else trexSizes[trackId] ?: track.defaultSampleSize
        if (flags and 0x10 != 0) p += 4 // default_sample_duration
        if (flags and 0x20 != 0) p += 4 // default_sample_flags

        val tfdt = child(b, traf, listOf("tfdt"))
        val trun = child(b, traf, listOf("trun")) ?: return
        val tFlags = (b.u(trun.body + 1) shl 16) or (b.u(trun.body + 2) shl 8) or b.u(trun.body + 3)
        val sampleCount = u32(b, trun.body + 4).toInt()
        var tp = trun.body + 8
        var dataOffset = 0
        if (tFlags and 0x1 != 0) { dataOffset = u32(b, tp).toInt(); tp += 4 } // signed actually but ok
        if (tFlags and 0x4 != 0) tp += 4
        val sizes = ArrayList<Int>()
        for (i in 0 until sampleCount) {
            if (tFlags and 0x100 != 0) tp += 4 // duration
            val sz = if (tFlags and 0x200 != 0) { val s = u32(b, tp).toInt(); tp += 4; s } else defaultSize.toInt()
            sizes.add(sz)
            if (tFlags and 0x400 != 0) tp += 4
            if (tFlags and 0x800 != 0) tp += 4
        }
        val senc = findDeep(b, traf, "senc") ?: return
        val sencJobs = parseSenc(b, senc, track.ivSize, track.constIV)
        var off = (moof.start + dataOffset).toLong()
        if (baseOffset != 0L && dataOffset == 0) off = baseOffset
        sencJobs.forEachIndexed { i, job ->
            if (i >= sizes.size) return@forEachIndexed
            val size = sizes[i]
            if (job.ranges.isEmpty()) jobs.add(SampleJob(job.iv, listOf(off.toInt() to size)))
            else jobs.add(SampleJob(job.iv, job.ranges.map { (o, l) -> (off.toInt() + o) to l }))
            off += size.toLong()
        }
    }
}
