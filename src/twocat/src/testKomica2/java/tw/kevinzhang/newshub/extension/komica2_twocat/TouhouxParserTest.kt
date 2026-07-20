package tw.kevinzhang.newshub.extension.twocat.komica2

import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kevinzhang.extension_api.model.Paragraph
import tw.kevinzhang.newshub.extension.twocat.komica2.pixmicat.Komica2PixmicatEngine
import tw.kevinzhang.newshub.extension.twocat.komica2.model.Komica2TwocatBoards

class TouhouxParserTest {
    @Test
    fun `summary parser reads a local touhoux Pixmicat thread`() {
        val engine = Komica2PixmicatEngine()
        val request = engine.createThreadSummariesRequestBuilder(Komica2TwocatBoards.all.single())
            .setPage(1)
            .build()

        val posts = engine.createThreadSummariesParser(engine.createUrlParser())
            .parse(TOUHOUX_FIXTURE.toResponseBody(), request)

        assertEquals(listOf("42"), posts.map { it.id })
        val post = posts.single()
        assertEquals("Fixture", post.title)
        assertEquals("fixture", post.poster)
        assertTrue(post.createdAt > 0)
        val image = post.content.filterIsInstance<Paragraph.ImageInfo>().single()
        assertEquals("https://p.2cat.org/touhoux/thumb/42s.jpg", image.thumb)
        assertEquals("https://p.2cat.org/touhoux/src/42.jpg", image.raw)
    }

    private companion object {
        val TOUHOUX_FIXTURE = """
            <div id="threads">
              <div class="threadpost reply" id="r42">
                <a href="//p.2cat.org/touhoux/src/42.jpg"><img class="img" data-original="//p.2cat.org/touhoux/thumb/42s.jpg"></a>
                <span class="title">Fixture</span> [25/01/01(三)12:00 ID:fixture]
                <div class="quote">local parser fixture</div>
              </div>
            </div>
        """.trimIndent()
    }
}
