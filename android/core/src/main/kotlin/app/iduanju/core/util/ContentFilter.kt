package app.iduanju.core.util

/**
 * Host-side content rule applied to every plugin / 采集站 result (plugins cannot opt out):
 * anything tagged or titled as involving minors is dropped. 1:1 with iOS ContentFilter.
 */
object ContentFilter {
    val cjk = listOf("未成年", "幼女", "幼童", "幼儿", "幼児", "儿童", "兒童", "萝莉", "蘿莉", "ロリ", "ショタ", "正太", "小学生", "小學生",
        "中学生", "中學生", "初中", "女子中学", "女子小学", "童女", "炼铜", "煉銅", "女高中生", "高中生", "女子校生", "女子高生", "女子中学生", "学生妹", "學生妹", "JK制服")
    val latin = listOf("loli", "lolis", "lolicon", "shota", "shotacon", "toddler", "toddlers", "underage", "under age", "preteen", "pre teen",
        "child", "children", "kid", "kids", "minor", "minors", "jailbait", "elementary school", "middle school", "junior high",
        "schoolgirl", "schoolgirls", "school girl", "school girls", "schoolboy", "school boy", "jk", "jc", "teen", "teens", "age regression",
        "high school girl", "highschool girl", "joshikousei", "joshi kousei", "jogakusei", "jogakuin", "joshikou", "joshi chuugakusei", "little girl", "little boy")
    private val latinRegex = Regex("(?<![a-z0-9])(" + latin.joinToString("|") { Regex.escape(it).replace(" ", "\\E\\s+\\Q") } + ")(?![a-z0-9])", RegexOption.IGNORE_CASE)

    fun isBlocked(texts: List<String>): Boolean {
        for (t in texts) {
            if (t.isEmpty()) continue
            val s = t.replace('-', ' ').replace('_', ' ')
            if (cjk.any { s.contains(it, ignoreCase = true) }) return true
            if (latinRegex.containsMatchIn(s)) return true
        }
        return false
    }
}
