package tw.kevinzhang.newshub.extension.mobile01

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals("螢幕選購與校色心得", threads.last().title)
        assertEquals("小明", threads.last().author)
        assertEquals(1784597400000, threads.last().createdAt)
        assertEquals(28, threads.last().replyCount)
        assertNull(threads.last().rawImage)
        assertTrue(threads.last().previewContent.isEmpty())
    }

    @Test
    fun `listing parser removes sticky posts after first page`() {
        val threads = parser.parseThreadSummaries(resource("topic-list-page-2.html"), boardUrl, null, listingPage = 2)

        assertEquals(listOf("5356600"), threads.map { Mobile01UrlPolicy.thread(it.id)?.threadId })
    }

    @Test
    fun `listing parser does not reject ordinary titles that mention promotion or pinning`() {
        val html = """
            <div class="l-listTable__tr">
              <div class="c-listTableTd__title">
                <a href="topicdetail.php?f=350&amp;t=5356601">討論廣告贊助與 PIN 置頂功能</a>
              </div>
              <a class="u-username">一般會員</a>
              <div class="l-listTable__td--count">4</div>
            </div>
        """.trimIndent()

        val threads = parser.parseThreadSummaries(html, boardUrl, null, listingPage = 2)

        assertEquals(listOf("5356601"), threads.map { Mobile01UrlPolicy.thread(it.id)?.threadId })
    }

    @Test
    fun `thread parser maps every floor to posts with an opaque next token`() {
        val page = parser.parseThreadPage(
            resource("topic-detail-page-1.html"),
            "https://www.mobile01.com/topicdetail.php?f=350&t=5356590",
            "icon",
        )

        assertEquals(listOf("66984790", "66998109"), page.posts.map { it.id })
        assertEquals("螢幕選購與校色心得", page.title)
        assertEquals("原作者", page.posts.first().author)
        assertEquals("回覆者", page.posts[1].author)
        assertEquals(1784337600000, page.posts.first().createdAt)
        assertEquals(1784340000000, page.posts[1].createdAt)
        assertTrue(page.posts.all { it.comments.isEmpty() })
        assertEquals("https://www.mobile01.com/topicdetail.php?f=350&t=5356590&p=2", page.nextPageToken)
        assertTrue(page.posts.first().content.any { it is Paragraph.Quote })
        assertTrue(page.posts.first().content.filterIsInstance<Paragraph.Text>().any {
            it.content.contains("區塊內容\n第二段區塊內容")
        })
        assertTrue(page.posts.first().content.any { it is Paragraph.ImageInfo && it.raw.endsWith("original.jpg") })
        assertTrue(page.posts[1].content.any { it is Paragraph.Quote })
        assertFalse(page.posts.flatMap { it.content }.any { it is Paragraph.ReplyTo })
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
        assertEquals(1784343600000, page.posts.single().createdAt)
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
                "<div class=\"l-articlePage\"><div class=\"l-articlePage__publish\"><article>content</article></div></div>",
                "https://www.mobile01.com/topicdetail.php?f=350&t=5356590",
                null,
            )
        }
    }

    @Test
    fun `thread parser de-duplicates repeated article ids`() {
        val duplicated = resource("topic-detail-page-1.html").replace(
            "</body>",
            """
                <div class="l-articlePage"><a id="name_66998109" class="u-username">回覆者</a><div class="l-articlePage__publish">
                  <article id="article_66998109" class="u-gapBottom--max c-articleLimit"><p>重複 DOM</p></article>
                </div></div>
                </body>
            """.trimIndent(),
        )

        val page = parser.parseThreadPage(
            duplicated,
            "https://www.mobile01.com/topicdetail.php?f=350&t=5356590",
            null,
        )

        assertEquals(listOf("66984790", "66998109"), page.posts.map { it.id })
    }

    private fun resource(name: String): String = requireNotNull(javaClass.classLoader.getResource("mobile01/$name"))
        .readText()
}
