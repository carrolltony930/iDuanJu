package app.iduanju.core.util

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Loose accessors over org.json output (mirrors Swift `J`). Values are JSONObject / JSONArray / Number / String / Boolean / NULL. */
object J {
    fun str(v: Any?): String = when (v) {
        null, JSONObject.NULL -> ""
        is String -> if (v == "<nil>" || v == "null") "" else v
        is Boolean -> if (v) "true" else "false"
        is Double -> if (v == Math.floor(v) && !v.isInfinite() && Math.abs(v) < 1e15) v.toLong().toString() else v.toString()
        is Float -> str(v.toDouble())
        is Number -> v.toString()
        else -> ""
    }
    fun int(v: Any?): Int = int64(v).let { if (it > Int.MAX_VALUE || it < Int.MIN_VALUE) it.toInt() else it.toInt() }
    fun int64(v: Any?): Long = when (v) {
        is Number -> v.toLong()
        is String -> v.trim().toLongOrNull() ?: (v.trim().toDoubleOrNull()?.toLong() ?: 0L)
        is Boolean -> if (v) 1 else 0
        else -> 0L
    }
    fun double(v: Any?): Double = when (v) { is Number -> v.toDouble(); is String -> v.toDoubleOrNull() ?: 0.0; else -> 0.0 }
    fun bool(v: Any?): Boolean = when (v) {
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        is String -> v.lowercase().let { it == "true" || it == "1" }
        else -> false
    }
    fun dict(v: Any?): JSONObject = v as? JSONObject ?: JSONObject()
    fun arr(v: Any?): List<Any?> = (v as? JSONArray)?.let { a -> List(a.length()) { a.opt(it) } } ?: emptyList()
    fun firstNonEmpty(vararg vals: String): String {
        for (s in vals) { val t = s.trim(); if (t.isNotEmpty() && t != "<nil>" && t != "null") return t }
        return ""
    }
    fun firstPositive(vararg vals: Any?): Long { for (v in vals) { val n = int64(v); if (n > 0) return n }; return 0 }
    fun unix(v: Any?): Long {
        val i = when (v) { is Number -> v.toLong(); is String -> v.trim().toLongOrNull() ?: 0L; else -> 0L }
        if (i > 1_000_000_000_000L) return i / 1000
        if (i > 1_000_000_000L) return i
        return 0
    }
    fun parse(data: ByteArray): JSONObject = parse(String(data, Charsets.UTF_8))
    fun parse(s: String): JSONObject {
        val o = try { JSONTokener(s).nextValue() } catch (e: Exception) { throw ProviderException.upstream("非 JSON 对象响应") }
        return o as? JSONObject ?: throw ProviderException.upstream("非 JSON 对象响应")
    }
    fun parseAny(s: String): Any? = try { JSONTokener(s).nextValue() } catch (_: Exception) { null }
    fun JSONObject.opt2(k: String): Any? = if (has(k) && !isNull(k)) opt(k) else null

    /** Sorted-key JSON like Swift JSONSerialization(.sortedKeys, .withoutEscapingSlashes) / Go json.Marshal(map). */
    fun encodeSorted(obj: Any?): ByteArray = buildString { enc(obj, this) }.toByteArray(Charsets.UTF_8)
    private fun enc(o: Any?, sb: StringBuilder) {
        when (o) {
            null, JSONObject.NULL -> sb.append("null")
            is Map<*, *> -> {
                sb.append('{')
                o.keys.map { it.toString() }.sorted().forEachIndexed { i, k ->
                    if (i > 0) sb.append(','); encStr(k, sb); sb.append(':'); enc(o[k], sb)
                }
                sb.append('}')
            }
            is JSONObject -> enc(o.keys().asSequence().associateWith { o.opt(it) }, sb)
            is List<*> -> { sb.append('['); o.forEachIndexed { i, v -> if (i > 0) sb.append(','); enc(v, sb) }; sb.append(']') }
            is JSONArray -> enc(List(o.length()) { o.opt(it) }, sb)
            is String -> encStr(o, sb)
            is Boolean -> sb.append(if (o) "true" else "false")
            is Double, is Float -> sb.append(str(o))
            is Number -> sb.append(o.toString())
            else -> encStr(o.toString(), sb)
        }
    }
    private fun encStr(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\""); '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n"); '\r' -> sb.append("\\r"); '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b"); '\u000c' -> sb.append("\\f")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }
}
