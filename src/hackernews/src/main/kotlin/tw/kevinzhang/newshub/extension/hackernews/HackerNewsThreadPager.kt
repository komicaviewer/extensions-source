package tw.kevinzhang.newshub.extension.hackernews

import com.google.gson.Gson
import com.google.gson.JsonParseException
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.encodeUtf8
import java.io.IOException
import java.util.ArrayDeque

/**
 * Turns Hacker News's item tree into stable, preorder pages.
 *
 * The cursor deliberately contains all traversal state. The host may retain it across an
 * extension process restart and may resend it after a failed append, so source-instance state
 * (or a time-limited server-side session) would make retrying unreliable.
 */
internal class HackerNewsThreadPager(
    private val api: HackerNewsApi,
    private val cursorCodec: HackerNewsCursorCodec = HackerNewsCursorCodec(),
) {
    suspend fun load(rootId: Long, pageToken: String?): HackerNewsItemPage {
        return if (pageToken == null) firstPage(rootId) else nextPage(rootId, pageToken)
    }

    private suspend fun firstPage(rootId: Long): HackerNewsItemPage {
        val root = api.getItem(rootId)
            ?: throw IOException("Hacker News story does not exist: $rootId")
        if (root.id != rootId || root.id <= 0) {
            throw IOException("Hacker News returned an invalid root item for $rootId")
        }
        val cursor = CursorState(
            rootId = rootId,
            frontier = ArrayDeque(),
            visited = linkedSetOf(rootId),
            examined = 0,
        )
        cursor.addChildren(root.kids)
        val comments = drain(cursor, FIRST_PAGE_COMMENT_LIMIT)
        return HackerNewsItemPage(
            items = listOf(root) + comments,
            nextPageToken = cursorCodec.encodeOrNull(cursor),
            root = root,
        )
    }

    private suspend fun nextPage(rootId: Long, pageToken: String): HackerNewsItemPage {
        val cursor = cursorCodec.decode(pageToken, expectedRootId = rootId)
        val comments = drain(cursor, PAGE_COMMENT_LIMIT)
        return HackerNewsItemPage(
            items = comments,
            nextPageToken = cursorCodec.encodeOrNull(cursor),
            root = null,
        )
    }

    private suspend fun drain(cursor: CursorState, pageSize: Int): List<HackerNewsItem> {
        val comments = ArrayList<HackerNewsItem>(pageSize)
        val examinedAtPageStart = cursor.examined
        // Bound requests as well as emitted comments. A malformed/deleted run of item IDs must
        // not turn one "load more" action into an unbounded traversal.
        while (
            comments.size < pageSize &&
            cursor.examined - examinedAtPageStart < pageSize &&
            cursor.frontier.isNotEmpty()
        ) {
            val itemId = cursor.frontier.removeFirst()
            cursor.examined = checkedIncrement(cursor.examined)
            val item = api.getItem(itemId) ?: continue

            // An unexpected object must not inject an arbitrary item into this thread. Its id was
            // already reserved in visited, so skipping it also prevents a retry loop.
            if (item.id != itemId || item.id <= 0) continue
            cursor.addChildren(item.kids)
            comments += item
        }
        return comments
    }

    private fun CursorState.addChildren(kids: List<Long>?) {
        // Deduplicate in API order first. Doing it after reversing would let a later duplicate
        // change the relative order of otherwise distinct siblings.
        val unseenChildren = kids.orEmpty().filter { childId ->
            childId > 0 && visited.add(childId)
        }
        // addFirst in reverse preserves the API's kid order while keeping DFS preorder.
        unseenChildren.asReversed().forEach(frontier::addFirst)
        check(frontier.size <= MAX_FRONTIER_IDS && visited.size <= MAX_VISITED_IDS) {
            "Hacker News thread is too large to encode safely"
        }
    }

    private fun checkedIncrement(value: Int): Int {
        check(value < MAX_EXAMINED_ITEMS) { "Hacker News traversal exceeded its safety limit" }
        return value + 1
    }

    private companion object {
        const val FIRST_PAGE_COMMENT_LIMIT = 49
        const val PAGE_COMMENT_LIMIT = 50
        const val MAX_FRONTIER_IDS = 10_000
        const val MAX_VISITED_IDS = 20_000
        const val MAX_EXAMINED_ITEMS = 1_000_000
    }
}

internal data class HackerNewsItemPage(
    val items: List<HackerNewsItem>,
    val nextPageToken: String?,
    val root: HackerNewsItem?,
)

/** A versioned, self-contained and strictly validated cursor for [HackerNewsThreadPager]. */
internal class HackerNewsCursorCodec(
    private val gson: Gson = Gson(),
) {
    fun encodeOrNull(cursor: CursorState): String? {
        if (cursor.frontier.isEmpty()) return null
        validate(cursor)
        val encoded = gson.toJson(
            CursorPayload(
                version = VERSION,
                rootId = cursor.rootId,
                frontier = cursor.frontier.toList(),
                visited = cursor.visited.toList(),
                examined = cursor.examined,
            ),
        ).encodeUtf8().base64Url()
        check(encoded.length <= MAX_TOKEN_CHARS) { "Hacker News page token is too large" }
        return encoded
    }

    fun decode(token: String, expectedRootId: Long): CursorState {
        require(expectedRootId > 0) { "Hacker News root id must be positive" }
        require(token.isNotBlank() && token.length <= MAX_TOKEN_CHARS) {
            "Invalid Hacker News page token size"
        }
        val json = token.decodeBase64()?.utf8()
            ?: throw IllegalArgumentException("Invalid Hacker News page token encoding")
        require(json.length <= MAX_TOKEN_JSON_CHARS) { "Invalid Hacker News page token payload size" }
        val payload = try {
            gson.fromJson(json, CursorPayload::class.java)
        } catch (error: JsonParseException) {
            throw IllegalArgumentException("Invalid Hacker News page token JSON", error)
        } ?: throw IllegalArgumentException("Invalid Hacker News page token JSON")
        require(payload.version == VERSION) { "Unsupported Hacker News page token version" }
        require(payload.rootId == expectedRootId) { "Hacker News page token belongs to another thread" }

        val cursor = CursorState(
            rootId = payload.rootId,
            frontier = ArrayDeque(payload.frontier ?: emptyList()),
            visited = LinkedHashSet(payload.visited ?: emptyList()),
            examined = payload.examined,
        )
        validate(cursor)
        require(cursor.frontier.isNotEmpty()) { "Hacker News page token has no remaining items" }
        return cursor
    }

    private fun validate(cursor: CursorState) {
        require(cursor.rootId > 0) { "Invalid Hacker News cursor root id" }
        require(cursor.examined in 0..MAX_EXAMINED_ITEMS) { "Invalid Hacker News cursor examined count" }
        require(cursor.frontier.size in 1..MAX_FRONTIER_IDS) { "Invalid Hacker News cursor frontier size" }
        require(cursor.visited.size in 1..MAX_VISITED_IDS) { "Invalid Hacker News cursor visited size" }
        require(cursor.visited.contains(cursor.rootId)) { "Invalid Hacker News cursor root state" }
        require(cursor.frontier.none { it <= 0 } && cursor.visited.none { it <= 0 }) {
            "Invalid Hacker News cursor item id"
        }
        require(cursor.frontier.size == cursor.frontier.toSet().size) {
            "Invalid Hacker News cursor duplicate frontier ids"
        }
        require(cursor.visited.size == cursor.visited.toSet().size) {
            "Invalid Hacker News cursor duplicate visited ids"
        }
        require(cursor.frontier.all(cursor.visited::contains) && !cursor.frontier.contains(cursor.rootId)) {
            "Invalid Hacker News cursor frontier state"
        }
        require(cursor.visited.size == 1 + cursor.frontier.size + cursor.examined) {
            "Invalid Hacker News cursor traversal state"
        }
    }

    private data class CursorPayload(
        val version: Int = 0,
        val rootId: Long = 0,
        val frontier: List<Long>? = null,
        val visited: List<Long>? = null,
        val examined: Int = -1,
    )

    private companion object {
        const val VERSION = 1
        const val MAX_FRONTIER_IDS = 10_000
        const val MAX_VISITED_IDS = 20_000
        const val MAX_EXAMINED_ITEMS = 1_000_000
        const val MAX_TOKEN_CHARS = 196_608
        const val MAX_TOKEN_JSON_CHARS = 147_456
    }
}

internal data class CursorState(
    val rootId: Long,
    val frontier: ArrayDeque<Long>,
    val visited: LinkedHashSet<Long>,
    var examined: Int,
)
