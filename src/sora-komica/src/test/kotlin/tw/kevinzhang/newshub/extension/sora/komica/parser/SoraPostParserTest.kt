package tw.kevinzhang.newshub.extension.sora.komica.parser

import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import tw.kevinzhang.extension_api.model.Paragraph
import tw.kevinzhang.newshub.extension.sora.komica.toResponseBody
import tw.kevinzhang.newshub.extension.sora.komica.toTimestamp

class SoraPostParserTest {
    private lateinit var fixture: Document

    @Before
    fun setUp() {
        val html = checkNotNull(javaClass.getResource("/iris-res-2649665-minimal.html"))
            .readText()
        fixture = Jsoup.parse(html)
    }

    @Test
    fun `iris two digit year and multiline body are parsed without empty paragraphs`() {
        val post = parseFixturePost("2649670")

        assertEquals("DXvnlIW6", post.poster)
        assertEquals(
            "2026/07/24 16:10".toTimestamp("yyyy/MM/dd HH:mm"),
            post.createdAt,
        )
        assertEquals(
            listOf(
                Paragraph.Text(
                    "留蚊子吧\n" +
                        "只要常常清理積水跟噴藥，數量就好控制\n" +
                        "臭蟲跟蟑螂處理起來太難了\n" +
                        "老鼠破壞力太大，也很會跑",
                ),
            ),
            post.content,
        )
    }

    @Test
    fun `iris body after reply target does not create surrounding empty text`() {
        val post = parseFixturePost("2649677")

        assertEquals(
            listOf(
                Paragraph.ReplyTo("2649665"),
                Paragraph.Text("這裡面最好殺的是蚊子"),
            ),
            post.content,
        )
    }

    @Test
    fun `four digit year remains compatible`() {
        val post = parseFixturePost("4000000")

        assertEquals(
            "2026/04/09 09:14".toTimestamp("yyyy/MM/dd HH:mm"),
            post.createdAt,
        )
    }

    private fun parseFixturePost(postId: String): ParsedPost {
        val element = checkNotNull(fixture.selectFirst("#r$postId"))
        return createParser().parse(
            element.toResponseBody(),
            Request.Builder()
                .url("https://iris.komica1.org/12/pixmicat.php?res=2649665#r$postId")
                .build(),
        )
    }

    private fun createParser(): SoraPostParser =
        SoraPostParser(SoraUrlParser(), SoraPostHeadParser())
}
