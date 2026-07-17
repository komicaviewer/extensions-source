package tw.kevinzhang.gamer_api.parser

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kevinzhang.gamer_api.model.GImageInfo

class PostParserTest {

    private val parser = PostParser(UrlParserImpl())
    private val request = Request.Builder()
        .url("https://forum.gamer.com.tw/C.php?bsn=60076&snA=9084331&sn=123456".toHttpUrl())
        .build()

    @Test
    fun `parse extracts photoswipe and standalone lazy loaded images with absolute URLs`() {
        val post = parser.parse(fixture().toResponseBody("text/html".toMediaType()), request)

        val images = post.content.filterIsInstance<GImageInfo>()
        assertEquals(2, images.size)
        assertEquals("https://truth.bahamut.com.tw/s01/thumb.jpg", images[0].thumb)
        assertEquals("https://forum.gamer.com.tw/images/full.jpg", images[0].raw)
        assertEquals("https://forum.gamer.com.tw/images/standalone-large.jpg", images[1].thumb)
        assertEquals("https://forum.gamer.com.tw/images/standalone-large.jpg", images[1].raw)
    }

    @Test
    fun `parse normalizes lazy image attributes for JavaScript disabled raw HTML`() {
        val post = parser.parse(fixture().toResponseBody("text/html".toMediaType()), request)
        val content = Jsoup.parseBodyFragment(post.rawHtml)

        val photoswipe = content.selectFirst("a.photoswipe-image")!!
        val photoswipeImage = photoswipe.selectFirst("img")!!
        assertEquals("https://forum.gamer.com.tw/images/full.jpg", photoswipe.attr("href"))
        assertEquals("https://truth.bahamut.com.tw/s01/thumb.jpg", photoswipeImage.attr("src"))
        assertEquals(
            "https://forum.gamer.com.tw/images/small.jpg 1x, https://truth.bahamut.com.tw/s01/large.jpg 2x",
            photoswipeImage.attr("srcset"),
        )

        val standalone = content.selectFirst("img.standalone")!!
        assertEquals("https://forum.gamer.com.tw/images/standalone-large.jpg", standalone.attr("src"))
        assertTrue(standalone.attr("srcset").contains("https://forum.gamer.com.tw/images/standalone-small.jpg 320w"))

        val source = content.selectFirst("picture source")!!
        assertEquals(
            "https://forum.gamer.com.tw/images/picture-small.webp 1x, https://forum.gamer.com.tw/images/picture-large.webp 2x",
            source.attr("srcset"),
        )
    }

    private fun fixture(): String =
        """
        <section class="c-section" id="post_123456">
          <div class="c-post__header">
            <h1 class="c-post__header__title">圖片測試</h1>
            <div class="c-post__header__info">
              <a class="edittime tippy-post-info" data-mtime="2026-07-18 12:00:00"></a>
            </div>
          </div>
          <a class="username">測試者</a>
          <a class="userid">tester</a>
          <div class="gp"><a class="count tippy-gpbp-list">1</a></div>
          <div class="bp"><a class="count tippy-gpbp-list">0</a></div>
          <a id="showoldCommend_123456">留言 0</a>
          <div class="c-article__content">
            <a class="photoswipe-image" href="/images/full.jpg">
              <img src="" data-src="//truth.bahamut.com.tw/s01/thumb.jpg"
                   data-srcset="/images/small.jpg 1x, //truth.bahamut.com.tw/s01/large.jpg 2x">
            </a>
            <div>
              <picture>
                <source data-srcset="/images/picture-small.webp 1x, /images/picture-large.webp 2x">
                <img class="standalone" src="data:image/gif;base64,placeholder"
                     data-srcset="/images/standalone-small.jpg 320w, /images/standalone-large.jpg 1280w">
              </picture>
            </div>
          </div>
        </section>
        """.trimIndent()
}
