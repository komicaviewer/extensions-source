package tw.kevinzhang.newshub.extension.ptt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kevinzhang.extension_api.model.Paragraph
import tw.kevinzhang.extension_api.model.RichTextColor
import tw.kevinzhang.extension_api.model.RichTextEmphasis
import tw.kevinzhang.extension_api.model.RichTextLayout
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
        assertTrue(post.content.any { it is Paragraph.ImageInfo && it.raw == "https://i.imgur.com/example.jpg" })
        assertTrue(post.content.any { it is Paragraph.VideoInfo && it.site == Paragraph.VideoInfo.Site.YOUTUBE })
        assertTrue(post.content.filterIsInstance<Paragraph.RichText>().any { rich ->
            rich.runs.any { it.text == "link" && it.linkUrl == "https://example.com/page" }
        })
        assertFalse(post.content.filterIsInstance<Paragraph.RichText>().any { rich ->
            rich.runs.any { "發信站" in it.text }
        })
    }

    @Test
    fun `thread parser preserves PTT preformatted rich text while leaving media and comments unchanged`() {
        val page = parser.parseThreadPage(
            fixture("thread-rich.html"),
            "https://www.ptt.cc/bbs/Stock/M.1720000000.A.ABC.html",
            "icon",
        )
        val post = page.posts.single()
        val richParagraphs = post.content.filterIsInstance<Paragraph.RichText>()
        val runs = richParagraphs.flatMap { it.runs }

        assertTrue(richParagraphs.all { it.layout == RichTextLayout.PREFORMATTED_WRAP })
        assertEquals(
            "前綴 紅亮綠黃青\n  保留  連續空白\n\n\n連結文字\n",
            richParagraphs.first().runs.joinToString("") { it.text },
        )
        assertTrue(runs.any { it.text == "紅" && it.color == RichTextColor.RED })
        assertTrue(runs.any {
            it.text == "亮綠" &&
                it.color == RichTextColor.GREEN &&
                it.emphasis == RichTextEmphasis.BRIGHT
        })
        assertTrue(runs.any { it.text == "黃" && it.color == RichTextColor.YELLOW })
        assertTrue(runs.any { it.text == "青" && it.color == RichTextColor.CYAN })
        assertTrue(runs.any {
            it.text == "連結文字" &&
                it.color == RichTextColor.MAGENTA &&
                it.linkUrl == "https://example.com/page"
        })
        assertTrue(runs.any { "※ 發信站: 正文示例不可刪除" in it.text })
        assertFalse(runs.any { "批踢踢實業坊(ptt.cc)" in it.text || "※ 文章網址:" in it.text })
        assertEquals(1, post.content.filterIsInstance<Paragraph.ImageInfo>().size)
        assertEquals(1, post.content.filterIsInstance<Paragraph.VideoInfo>().size)
        assertEquals("推 很好", (post.comments.single().content.single() as Paragraph.Text).content)
    }

    private fun fixture(name: String): String = requireNotNull(javaClass.getResource("/ptt/$name"))
        .readText()
}
