package tw.kevinzhang.newshub.extension.hackernews

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tw.kevinzhang.extension_api.model.BoardPageRequest
import tw.kevinzhang.extension_api.model.BoardQuery

class HackerNewsBoardsTest {
    private val source = HackerNewsSource()

    @Test
    fun `board catalog exposes the six official feeds`() = runTest {
        val page = source.getBoardPage(BoardPageRequest())

        assertEquals(
            listOf("Top Stories", "New Stories", "Best Stories", "Ask HN", "Show HN", "Jobs"),
            page.boards.map { it.name },
        )
        assertNull(page.nextPageToken)
    }

    @Test
    fun `board catalog filters and paginates locally`() = runTest {
        val first = source.getBoardPage(
            BoardPageRequest(query = BoardQuery("stories"), pageSize = 2),
        )
        val second = source.getBoardPage(
            BoardPageRequest(
                query = BoardQuery("stories"),
                pageToken = first.nextPageToken,
                pageSize = 2,
            ),
        )

        assertEquals(listOf("Top Stories", "New Stories"), first.boards.map { it.name })
        assertEquals("2", first.nextPageToken)
        assertEquals(listOf("Best Stories"), second.boards.map { it.name })
        assertNull(second.nextPageToken)
    }
}
