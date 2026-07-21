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
import tw.kevinzhang.gamer_api.model.GRichText
import tw.kevinzhang.gamer_api.model.GTextColor
import tw.kevinzhang.gamer_api.model.GTextEmphasis

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

    @Test
    fun `parse keeps font wrapped article text links and every image in DOM order`() {
        val post = parseArticle(
            """
            <div><font color="#616161"><font size="3">不同於電視機有的會植入推銷內容，多數電腦螢幕內部系統都是走純淨設計。</font></font></div>
            <div>傻眼 @@</div>
            <a href="https://www.4gamers.com.tw/news/detail/807777"><font><span>外部文章連結</span></font></a>
            <bahamut-swiper-image>
              <img data-src="/images/first.jpg">
              <img data-src="/images/second.jpg">
            </bahamut-swiper-image>
            <iframe src="https://www.youtube.com/embed/LG_test-1"></iframe>
            <div><font color="#0089ac">各界仍等待 LG 的回應</font></div>
            """.trimIndent(),
        )

        val text = post.content.filterIsInstance<GRichText>().joinToString(separator = "") { it.content }
        assertTrue(text.contains("不同於電視機有的會植入推銷內容"))
        assertTrue(text.contains("傻眼 @@"))
        assertTrue(text.contains("外部文章連結"))
        assertTrue(text.contains("各界仍等待 LG 的回應"))

        val linkedRun = post.content.filterIsInstance<GRichText>()
            .flatMap { it.runs }
            .first { it.text.contains("外部文章連結") }
        assertEquals("https://www.4gamers.com.tw/news/detail/807777", linkedRun.linkUrl)

        val images = post.content.filterIsInstance<GImageInfo>()
        assertEquals(
            listOf(
                "https://forum.gamer.com.tw/images/first.jpg",
                "https://forum.gamer.com.tw/images/second.jpg",
            ),
            images.map { it.raw },
        )
        assertEquals("https://www.youtube.com/watch?v=LG_test-1", post.content[3].content)
    }

    @Test
    fun `parse preserves ordinary nested div text regression`() {
        val post = parseArticle(
            """
            <div>玩 PUBG 的朋友可以先看這一篇。<br>這是未套 font 的一般文字。</div>
            <div><img data-src="/images/normal-one.jpg"></div>
            <div>第二段仍然要保留。</div>
            """.trimIndent(),
        )

        val text = post.content.filterIsInstance<GRichText>().joinToString(separator = "") { it.content }
        assertTrue(text.contains("玩 PUBG 的朋友可以先看這一篇。\n這是未套 font 的一般文字。"))
        assertTrue(text.contains("第二段仍然要保留。"))
        assertEquals(
            listOf("https://forum.gamer.com.tw/images/normal-one.jpg"),
            post.content.filterIsInstance<GImageInfo>().map { it.raw },
        )
    }

    @Test
    fun `parse preserves formatted table content instead of collapsing it to first image`() {
        val post = parseArticle(
            """
            <b><font color="#00a0a0" size="6"><a href="/info"><span>星河電腦螢幕資訊分享站</span></a></font></b>
            <div>這是網站中<b><font color="#ff0000">最重要的頁面</font></b>，個人對當下較出色產品進行排序的總評表。</div>
            <span style="color: #0000ff; font-weight: 700">CSS 藍色粗體</span>
            <table><tbody>
              <tr><td>討論串切換門</td><td><a href="/144hz"><font color="#0089ac">144Hz 螢幕討論串</font></a></td></tr>
              <tr><td>4K UHD 螢幕討論串</td><td>FHD 60Hz</td></tr>
            </tbody></table>
            <div><img data-src="/images/144hz.jpg"><img data-src="/images/4k.jpg"></div>
            """.trimIndent(),
        )

        val runs = post.content.filterIsInstance<GRichText>().flatMap { it.runs }
        val text = runs.joinToString(separator = "") { it.text }
        assertTrue(text.contains("星河電腦螢幕資訊分享站"))
        assertTrue(text.contains("最重要的頁面"))
        assertTrue(text.contains("討論串切換門 | 144Hz 螢幕討論串"))
        assertTrue(text.contains("4K UHD 螢幕討論串 | FHD 60Hz"))
        assertEquals(
            "https://forum.gamer.com.tw/info",
            runs.first { it.text.contains("星河電腦螢幕資訊分享站") }.linkUrl,
        )
        assertEquals(GTextEmphasis.BRIGHT, runs.first { it.text.contains("星河電腦螢幕資訊分享站") }.emphasis)
        assertEquals(GTextColor.CYAN, runs.first { it.text.contains("144Hz 螢幕討論串") }.color)
        val cssStyledRun = runs.first { it.text.contains("CSS 藍色粗體") }
        assertEquals(GTextColor.BLUE, cssStyledRun.color)
        assertEquals(GTextEmphasis.BRIGHT, cssStyledRun.emphasis)
        assertEquals(
            listOf(
                "https://forum.gamer.com.tw/images/144hz.jpg",
                "https://forum.gamer.com.tw/images/4k.jpg",
            ),
            post.content.filterIsInstance<GImageInfo>().map { it.raw },
        )
    }

    private fun parseArticle(content: String) = parser.parse(
        document(content).toResponseBody("text/html".toMediaType()),
        request,
    )

    private fun document(content: String): String =
        """
        <section class="c-section" id="post_123456">
          <div class="c-post__header">
            <h1 class="c-post__header__title">內容測試</h1>
            <div class="c-post__header__info"><a class="edittime tippy-post-info" data-mtime="2026-07-18 12:00:00"></a></div>
          </div>
          <a class="username">測試者</a><a class="userid">tester</a>
          <div class="gp"><a class="count tippy-gpbp-list">1</a></div>
          <div class="bp"><a class="count tippy-gpbp-list">0</a></div>
          <a id="showoldCommend_123456">留言 0</a>
          <div class="c-article__content">$content</div>
        </section>
        """.trimIndent()

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
