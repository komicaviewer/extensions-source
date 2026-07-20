package tw.kevinzhang.newshub.extension.hackernews

internal data class HackerNewsItem(
    val id: Long = 0,
    val deleted: Boolean? = null,
    val type: String? = null,
    val by: String? = null,
    val time: Long? = null,
    val text: String? = null,
    val dead: Boolean? = null,
    val parent: Long? = null,
    val kids: List<Long>? = null,
    val url: String? = null,
    val score: Int? = null,
    val title: String? = null,
    val descendants: Int? = null,
)
