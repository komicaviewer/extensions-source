package tw.kevinzhang.newshub.extension.sora.komica2.pixmicat.parser

import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import tw.kevinzhang.extension_api.model.Paragraph

class PixmicatPostParserTest {
    @Test
    fun `parses realura mp4 and preserves other media types`() {
        val request = Request.Builder()
            .url("https://2cat.uk/~realura/pixmicat.php?res=86832")
            .build()

        val post = PixmicatPostParser(
            PixmicatUrlParser(),
            Komica2PixmicatPostHeadParser(),
        ).parse(REALURA_MEDIA_FIXTURE.toResponseBody(), request)

        assertEquals(
            listOf(
                "https://p.2nyan.org//~realura/src/1777180360590.MP4?download=1#video",
                "https://p.2nyan.org//~realura/src/1777180360591.webm#video",
            ),
            post.content.filterIsInstance<Paragraph.VideoInfo>().map { it.url },
        )
        assertEquals(
            listOf(
                Paragraph.ImageInfo(
                    "https://p.2nyan.org//~realura/thumb/1777951371178s.jpg",
                    "https://p.2nyan.org//~realura/src/1777951371178.JPG?filename=preview.mp4",
                ),
            ),
            post.content.filterIsInstance<Paragraph.ImageInfo>(),
        )
    }

    private companion object {
        val REALURA_MEDIA_FIXTURE = """
            <div class="threadpost" id="r86832">
              <span class="title">要怎麼找 AI 換臉影片</span>
              名稱: <span class="name">無名氏</span> [26/04/26(日)13:12 ID:VTIZWI56]
              <a href="//p.2nyan.org//~realura/src/1777180360590.MP4?download=1#video">
                <img class="img" src="//p.2nyan.org//~realura/thumb/1777180360590s.jpg">
              </a>
              <a href="//p.2nyan.org//~realura/src/1777180360591.webm#video">
                <img class="img" src="//p.2nyan.org//~realura/thumb/1777180360591s.jpg">
              </a>
              <a href="//p.2nyan.org//~realura/src/1777951371178.JPG?filename=preview.mp4">
                <img class="img" src="//p.2nyan.org//~realura/thumb/1777951371178s.jpg">
              </a>
              <div class="quote">我知道影片是換過臉的</div>
            </div>
        """.trimIndent()
    }
}
