package tw.kevinzhang.newshub.extension.mobile01

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kevinzhang.extension_api.model.Paragraph

class Mobile01ParserTest {
    private val parser = Mobile01Parser()
    private val boardUrl = "https://www.mobile01.com/topiclist.php?f=350"

    @Test
    fun `listing parser keeps first page sticky posts and excludes sponsored content`() {
        val threads = parser.parseThreadSummaries(resource("topic-list-page-1.html"), boardUrl, "icon", listingPage = 1)

        assertEquals(listOf("100", "5356590"), threads.map { Mobile01UrlPolicy.thread(it.id)?.threadId })
        assertTrue(threads.all { Mobile01UrlPolicy.thread(it.id)?.page == 1 })
        assertEquals(28, threads.last().replyCount)
        assertEquals("https://attach2.mobile01.com/image.jpg", threads.last().rawImage)
    }

    @Test
    fun `listing parser removes sticky posts after first page`() {
        val threads = parser.parseThreadSummaries(resource("topic-list-page-2.html"), boardUrl, null, listingPage = 2)

        assertEquals(listOf("5356600"), threads.map { Mobile01UrlPolicy.thread(it.id)?.threadId })
    }

    @Test
    fun `thread parser maps every floor to posts with an opaque next token`() {
        val page = parser.parseThreadPage(
            resource("topic-detail-page-1.html"),
            "https://www.mobile01.com/topicdetail.php?f=350&t=5356590",
            "icon",
        )

        assertEquals(listOf("66984790", "66998109"), page.posts.map { it.id })
        assertTrue(page.posts.all { it.comments.isEmpty() })
        assertEquals("https://www.mobile01.com/topicdetail.php?f=350&t=5356590&p=2", page.nextPageToken)
        assertTrue(page.posts.first().content.any { it is Paragraph.Quote })
        assertTrue(page.posts.first().content.any { it is Paragraph.ImageInfo && it.raw.endsWith("original.jpg") })
        assertTrue(page.posts[1].content.any { it is Paragraph.ReplyTo && it.targetId == "66984790" })
        assertNull(page.posts.first().rawHtml)
        assertNull(page.posts.first().replyCount)
    }

    @Test
    fun `last page has no token and supports video links`() {
        val page = parser.parseThreadPage(
            resource("topic-detail-page-2.html"),
            "https://www.mobile01.com/topicdetail.php?f=350&t=5356590&p=2",
            null,
        )

        assertEquals("67000123", page.posts.single().id)
        assertTrue(page.posts.single().content.any { it is Paragraph.VideoInfo && it.site == Paragraph.VideoInfo.Site.YOUTUBE })
        assertNull(page.nextPageToken)
    }

    @Test
    fun `thread parser fails closed when post structure is unknown`() {
        assertThrows(Mobile01PageStructureException::class.java) {
            parser.parseThreadPage(
                "<html><body><p>unexpected response</p></body></html>",
                "https://www.mobile01.com/topicdetail.php?f=350&t=5356590",
                null,
            )
        }
    }

    @Test
    fun `listing parser fails closed when listing structure is unknown`() {
        assertThrows(Mobile01PageStructureException::class.java) {
            parser.parseThreadSummaries(
                "<html><body><p>unexpected response</p></body></html>",
                boardUrl,
                null,
                listingPage = 1,
            )
        }
    }

    @Test
    fun `thread parser rejects posts without a stable article identifier`() {
        assertThrows(Mobile01PageStructureException::class.java) {
            parser.parseThreadPage(
                "<article><div class=\"article-content\">content without an article number</div></article>",
                "https://www.mobile01.com/topicdetail.php?f=350&t=5356590",
                null,
            )
        }
    }

    private fun resource(name: String): String = requireNotNull(javaClass.classLoader.getResource("mobile01/$name"))
        .readText()
}
