package tw.kevinzhang.newshub.extension.akraft

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AkraftBoardsAndRequestTest {
    @Test
    fun `catalog contains the requested Akraft boards`() {
        assertEquals(
            listOf(
                Triple("影視", "https://www.akraft.net/service/66a6eca2bfccee3f04a52bc4", "tw.kevinzhang.akraft"),
                Triple("Dota2", "https://www.akraft.net/service/61bc09b0e27a80b99d12c095", "tw.kevinzhang.akraft"),
            ),
            AkraftBoards.all.map { Triple(it.name, it.url, it.sourceId) },
        )
    }

    @Test
    fun `board request writes one based pagination and a browser safe accept header`() {
        val request = AkraftRequestBuilder.board(AkraftBoards.all.first().url, 2)

        assertEquals(
            "https://www.akraft.net/service/66a6eca2bfccee3f04a52bc4?page=2",
            request.url.toString(),
        )
        assertEquals("NewsHub Akraft extension/1.0", request.header("User-Agent"))
        assertEquals("text/html,application/xhtml+xml", request.header("Accept"))
    }

    @Test
    fun `thread request preserves its canonical URL without page query`() {
        val request = AkraftRequestBuilder.thread("https://www.akraft.net/service/id/thread-id")

        assertEquals("https://www.akraft.net/service/id/thread-id", request.url.toString())
        assertNull(request.url.queryParameter("page"))
    }
}
