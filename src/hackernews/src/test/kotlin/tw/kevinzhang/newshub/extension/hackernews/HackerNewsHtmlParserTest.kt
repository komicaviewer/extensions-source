package tw.kevinzhang.newshub.extension.hackernews

import org.junit.Assert.assertEquals
import org.junit.Test
import tw.kevinzhang.extension_api.model.Paragraph

class HackerNewsHtmlParserTest {
    private val parser = HackerNewsHtmlParser()

    @Test
    fun `parses entities links quotes and code without duplicate links`() {
        val paragraphs = parser.parse(
            """
            <p>Hello &amp; welcome to <a href="item?id=42">the thread</a>.</p>
            <blockquote>A quoted thought</blockquote>
            <pre>val answer = 42</pre>
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                Paragraph.Text("Hello & welcome to"),
                Paragraph.Text("the thread"),
                Paragraph.Link("https://news.ycombinator.com/item?id=42"),
                Paragraph.Text("."),
                Paragraph.Quote("A quoted thought"),
                Paragraph.Text("val answer = 42"),
            ),
            paragraphs,
        )
    }

    @Test
    fun `blank html produces no paragraphs`() {
        assertEquals(emptyList<Paragraph>(), parser.parse("  "))
        assertEquals(emptyList<Paragraph>(), parser.parse(null))
    }
}
