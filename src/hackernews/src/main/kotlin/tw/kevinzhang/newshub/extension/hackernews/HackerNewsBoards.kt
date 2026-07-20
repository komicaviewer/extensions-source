package tw.kevinzhang.newshub.extension.hackernews

import tw.kevinzhang.extension_api.model.Board

internal enum class HackerNewsFeed(
    val endpoint: String,
    val boardUrl: String,
    val displayName: String,
    val description: String,
) {
    TOP("topstories.json", "https://news.ycombinator.com/news", "Top Stories", "Top-ranked Hacker News stories"),
    NEW("newstories.json", "https://news.ycombinator.com/newest", "New Stories", "Newest Hacker News stories"),
    BEST("beststories.json", "https://news.ycombinator.com/best", "Best Stories", "Best recent Hacker News stories"),
    ASK("askstories.json", "https://news.ycombinator.com/ask", "Ask HN", "Questions submitted to Ask HN"),
    SHOW("showstories.json", "https://news.ycombinator.com/show", "Show HN", "Projects submitted to Show HN"),
    JOBS("jobstories.json", "https://news.ycombinator.com/jobs", "Jobs", "Jobs posted on Hacker News"),
}

internal object HackerNewsBoards {
    const val SOURCE_ID = "tw.kevinzhang.newshub.extension.hackernews"

    val all: List<Board> = HackerNewsFeed.entries.map { feed ->
        Board(
            sourceId = SOURCE_ID,
            url = feed.boardUrl,
            name = feed.displayName,
            description = feed.description,
        )
    }

    fun feedFor(board: Board): HackerNewsFeed {
        require(board.sourceId == SOURCE_ID) { "Unexpected source id: ${board.sourceId}" }
        return requireNotNull(HackerNewsFeed.entries.firstOrNull { it.boardUrl == board.url }) {
            "Unknown Hacker News board: ${board.url}"
        }
    }
}
