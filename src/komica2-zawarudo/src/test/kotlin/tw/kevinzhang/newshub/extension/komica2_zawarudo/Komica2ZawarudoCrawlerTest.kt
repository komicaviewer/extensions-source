package tw.kevinzhang.newshub.extension.komica2_zawarudo

import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kevinzhang.komica_api.model.KImageInfo
import tw.kevinzhang.komica_api.model.KLink
import tw.kevinzhang.komica_api.model.KQuote
import tw.kevinzhang.komica_api.model.KReplyTo
import tw.kevinzhang.komica_api.model.KText

class Komica2ZawarudoCrawlerTest {
    private val crawler = Komica2ZawarudoCrawler()

    @Test
    fun `board request uses vichan page paths`() {
        assertEquals(
            "https://majeur.zawarudo.org/demande/",
            crawler.boardRequest("https://majeur.zawarudo.org/demande", 1).url.toString(),
        )
        assertEquals(
            "https://majeur.zawarudo.org/demande/3.html",
            crawler.boardRequest("https://majeur.zawarudo.org/demande", 3).url.toString(),
        )
    }

    @Test
    fun `board parser maps posts paragraphs attachments and omitted replies`() {
        val request = Request.Builder()
            .url("https://majeur.zawarudo.org/demande/")
            .build()
        val posts = crawler.parseBoard(BOARD_HTML.toResponseBody(), request)

        assertEquals(1, posts.size)
        val post = posts.single()
        assertEquals("33740", post.id)
        assertEquals("https://majeur.zawarudo.org/demande/res/33740.html#33740", post.url)
        assertEquals("測試標題", post.title)
        assertEquals("無名氏", post.poster)
        assertEquals(5, post.replies)
        assertEquals(1_784_304_000_000L, post.createdAt)
        assertTrue(post.content.any { it is KText && it.content.contains("本文") })
        assertTrue(post.content.any { it == KReplyTo("123") })
        assertTrue(post.content.any { it == KQuote("一般引用") })
        assertTrue(post.content.any { it == KLink("https://example.com/page") })
        assertTrue(
            post.content.any {
                it == KImageInfo(
                    "https://majeur.zawarudo.org/demande/thumb/1.jpg",
                    "https://majeur.zawarudo.org/demande/src/1.png",
                )
            },
        )
    }

    @Test
    fun `thread parser returns op and replies with reply attachments`() {
        val request = Request.Builder()
            .url("https://majeur.zawarudo.org/demande/res/33740.html")
            .build()
        val posts = crawler.parseThread(THREAD_HTML.toResponseBody(), request)

        assertEquals(listOf("33740", "33761"), posts.map { it.id })
        val reply = posts[1].content.filterIsInstance<KReplyTo>().single()
        assertEquals("33740", reply.targetId)
        assertEquals("OP body", reply.preview)
        assertTrue(
            posts[1].content.any {
                it == KImageInfo(
                    "https://majeur.zawarudo.org/demande/thumb/2.jpg",
                    "https://majeur.zawarudo.org/demande/src/2.jpeg",
                )
            },
        )
    }

    private companion object {
        val BOARD_HTML = """
            <html><body>
              <div class="thread" id="thread_33740" data-board="demande">
                <div class="files"><div class="file">
                  <a href="/demande/src/1.png"><img class="post-image" src="/demande/thumb/1.jpg"></a>
                </div></div>
                <div class="post op" id="op_33740">
                  <p class="intro"><label><span class="subject">測試標題</span><span class="name">無名氏</span>
                    <time datetime="2026-07-17T16:00:00Z">date</time></label>
                    <a href="/demande/res/33740.html">[Reply]</a></p>
                  <div class="body">本文<br><a href="/demande/res/33740.html#123">&gt;&gt;123</a>
                    <span class="quote">&gt;一般引用</span><a href="https://example.com/page">example</a></div>
                  <span class="omitted">3 posts and 1 image reply omitted.</span>
                </div>
                <div class="post reply" id="reply_33741"><div class="body">reply</div></div>
              </div>
            </body></html>
        """.trimIndent()

        val THREAD_HTML = """
            <html><body><div class="thread" id="thread_33740">
              <div class="post op" id="op_33740">
                <p class="intro"><span class="name">OP</span><time datetime="2026-07-17T16:00:00Z"></time>
                  <a href="/demande/res/33740.html">[Reply]</a></p>
                <div class="body">OP body</div>
              </div>
              <div class="post reply" id="reply_33761">
                <p class="intro"><span class="name">Reply</span><time datetime="2026-07-17T17:00:00Z"></time></p>
                <div class="files"><div class="file"><a href="/demande/src/2.jpeg">
                  <img class="post-image" src="/demande/thumb/2.jpg"></a></div></div>
                <div class="body"><a href="#33740">&gt;&gt;33740</a><br>reply</div>
              </div>
            </div></body></html>
        """.trimIndent()
    }
}
