package app.iduanju.core.hongguo

data class HGSeries(
    var seriesId: String = "",
    var title: String = "",
    var cover: String = "",
    var subtitle: String? = null,
    var author: String? = null,
    var episodeCount: Int = 0,
    var duration: Int = 0,
    var playCount: Long = 0,
    var followCount: Long = 0,
    var onlineTime: Long = 0,
    var orientation: String? = null,
    var isNew: Boolean = false,
    var tags: List<String> = emptyList(),
    var rank: Int = 0,
)

data class HGEpisode(val ep: Int, val vid: String, val duration: Int = 0, val title: String = "", val page: String? = null, val media: String? = null)

data class HGCelebrity(val id: String = "", val name: String = "", val avatar: String = "", val role: String = "", val intro: String = "")

data class HGSeriesDetail(
    var seriesId: String = "",
    var title: String = "",
    var cover: String = "",
    var desc: String = "",
    var author: String = "",
    var actors: List<String> = emptyList(),
    var onlineTime: Long = 0,
    var totalEps: Int = 0,
    var episodes: List<HGEpisode> = emptyList(),
    var relatedSeries: List<HGSeries> = emptyList(),
    var cast: List<HGCelebrity> = emptyList(),
    var tags: List<String> = emptyList(),
    var favoriteCount: Long = 0,
    var episodeLabel: String = "",
    var rating: Double = 0.0,
    var ratingCount: String = "",
    var hotText: String = "",
    var source: String = "红果短剧",
)

data class HGPage(
    val items: List<HGSeries>,
    val hasMore: Boolean,
    val nextOffset: Int? = null,
    val nextCursor: String? = null,
    val sessionId: String? = null,
)

data class HGStreamAudio(
    val codec: String = "", val profile: String = "", val language: String = "",
    val channels: Int = 0, val sampleRate: Int = 0, val bitrate: Int = 0,
)

enum class HGStreamKind { mp4Clear, mp4Cenc, hls }

data class HGStream(
    val streamId: String,
    val quality: String?,
    val url: String,
    val key: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val codec: String = "",
    val size: Int = 0,
    val bitrate: Int = 0,
    val audio: HGStreamAudio? = null,
    val kind: HGStreamKind = HGStreamKind.mp4Clear,
    val backupURL: String? = null,
    val headers: Map<String, String> = emptyMap(),
) {
    val id: String get() = streamId
}

data class HGResolvedVideo(
    val vid: String,
    val url: String? = null,
    val key: String? = null,
    val duration: Double = 0.0,
    val expiresAt: Long = 0,
    val err: String? = null,
    val streams: List<HGStream> = emptyList(),
    var note: String? = null,
)

enum class HGRankGenre(val label: String) {
    all("全部"), human("真人剧"), comic("漫剧"), ai("AI剧");
    val subs: List<HGRankSub>
        get() = when (this) {
            all -> listOf(HGRankSub.hot, HGRankSub.recommend, HGRankSub.new, HGRankSub.hotSearch, HGRankSub.mustWatch, HGRankSub.followed, HGRankSub.zhenguo, HGRankSub.subscribe)
            human -> listOf(HGRankSub.hot, HGRankSub.recommend, HGRankSub.new, HGRankSub.hotSearch, HGRankSub.mustWatch, HGRankSub.followed)
            comic -> listOf(HGRankSub.hot, HGRankSub.recommend, HGRankSub.new, HGRankSub.hotSearch)
            ai -> listOf(HGRankSub.hot, HGRankSub.recommend, HGRankSub.new, HGRankSub.mustWatch, HGRankSub.followed, HGRankSub.hotSearch)
        }
}

enum class HGRankSub(val label: String) {
    hot("热播"), recommend("推荐"), new("新剧"), hotSearch("热搜"),
    mustWatch("必看"), followed("收藏"), zhenguo("臻果"), subscribe("预约")
}

enum class HGContentFilter(val label: String) { all("全部"), human("真人剧"), comic("漫剧"), ai("AI剧") }

data class HGFilterOption(val id: String, val name: String)
data class HGFilterRow(val type: String, val name: String, val selectType: Int, val items: List<HGFilterOption>)
data class HGPanel(val rows: List<HGFilterRow>)
data class HGWebDetail(
    var title: String = "", var intro: String = "", var tags: List<String> = emptyList(),
    var cast: List<HGCelebrity> = emptyList(), var favoriteCount: Long = 0,
    var episodeLabel: String = "", var seriesGroupId: String = "",
    var rating: Double = 0.0, var ratingCount: String = "", var hotText: String = "",
)
