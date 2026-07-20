package tw.kevinzhang.newshub.extension.ptt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kevinzhang.extension_api.model.Paragraph
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class PttParserTest {
    private val parser = PttParser(Clock.fixed(Instant.parse("2026-07-21T00:00:00Z"), ZoneId.of("Asia/Taipei")))

    @Test
    fun `board parser skips deleted and untrusted articles while preserving previous anchor`() {
        val listing = parser.parseBoardListing(fixture("board.html"), "Stock", "icon")

        assertEquals(1, listing.summaries.size)
        assertEquals("https://www.ptt.cc/bbs/Stock/M.1720000000.A.ABC.html", listing.summaries.single().id)
        assertEquals(100, listing.summaries.single().commentCount)
        assertEquals(1_720_000_000_000L, listing.summaries.single().createdAt)
        assertEquals(7123, listing.previousPageIndex)
    }

    @Test
    fun `thread parser maps article to Post and push variants to Comments`() {
        val page = parser.parseThreadPage(
            fixture("thread.html"),
            "https://www.ptt.cc/bbs/Stock/M.1720000000.A.ABC.html",
            "icon",
        )

        assertEquals(null, page.nextPageToken)
        assertEquals(1, page.posts.size)
        val post = page.posts.single()
        assertEquals("M.1720000000.A.ABC", post.id)
        assertEquals("alice (Alice)", post.author)
        assertEquals(3, post.comments.size)
        assertEquals("推 很好", (post.comments[0].content.single() as Paragraph.Text).content)
        assertEquals("噓 不同意", (post.comments[1].content.single() as Paragraph.Text).content)
        assertEquals("→ 路過", (post.comments[2].content.single() as Paragraph.Text).content)
        assertTrue(post.content.any { it is Paragraph.Quote && it.content == "> 引用內容" })
        assertTrue(post.content.any { it is Paragraph.ImageInfo && it.raw == "https://i.imgur.com/example.jpg" })
        assertTrue(post.content.any { it is Paragraph.VideoInfo && it.site == Paragraph.VideoInfo.Site.YOUTUBE })
        assertTrue(post.content.any { it is Paragraph.Link && it.content == "https://example.com/page" })
        assertFalse(post.content.filterIsInstance<Paragraph.Text>().any { "發信站" in it.content })
    }

    private fun fixture(name: String): String = requireNotNull(javaClass.getResource("/ptt/$name"))
        .readText()
}
