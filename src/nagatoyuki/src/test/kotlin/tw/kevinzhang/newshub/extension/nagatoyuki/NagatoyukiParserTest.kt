package tw.kevinzhang.newshub.extension.nagatoyuki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kevinzhang.extension_api.model.Paragraph

class NagatoyukiParserTest {
    private val parser = NagatoyukiParser()

    @Test
    fun `summary parser resolves relative media and counts hidden replies`() {
        val post = parser.parseSummaries(FIXTURE, "https://selene.zawarudo.org/costumade").single()

        assertEquals("3212", post.id)
        assertEquals("測試標題", post.title)
        assertEquals("作者", post.author)
        assertEquals(4, post.replies)
        assertTrue(post.content.any { it is Paragraph.ImageInfo && it.raw == "https://selene.zawarudo.org/costumade/src/image.png" })
        assertTrue(post.content.any { it is Paragraph.Quote && it.content == "引用文字" })
        assertTrue(post.content.any { it is Paragraph.Link && it.content == "https://example.com/a" })
    }

    @Test
    fun `thread parser maps quote links and supplies previews`() {
        val posts = parser.parseThread(FIXTURE, "https://selene.zawarudo.org/costumade/res/3212.html")

        assertEquals(listOf("3212", "3220", "3221"), posts.map { it.id })
        assertEquals(2, posts.first().replies)
        val reply = posts[1].content.filterIsInstance<Paragraph.ReplyTo>().single()
        assertEquals("3212", reply.targetId)
        assertEquals("首篇內容", reply.preview)
        assertTrue(posts[1].content.any { it is Paragraph.VideoInfo && it.url == "https://selene.zawarudo.org/costumade/src/movie.mp4" })
        assertTrue(posts[2].content.filterIsInstance<Paragraph.Text>().any { it.content == "純文字" })
    }

    private companion object {
        val FIXTURE = """
            <div class="thread" id="thread_3212">
              <div class="files"><div class="file">
                <p class="fileinfo"><a href="/costumade/src/image.png">image.png</a></p>
                <a href="/costumade/src/image.png"><img class="post-image" src="/costumade/thumb/image.png"></a>
              </div></div>
              <div class="post op" id="op_3212"><p class="intro"><span class="subject">測試標題</span><span class="name">作者</span><time datetime="2026-06-20T04:40:52Z">2026-06-20</time></p><div class="body">首篇內容<br><span class="quote">&gt;引用文字</span><br><a href="https://example.com/a">連結</a></div></div>
              <div class="post reply" id="reply_3220"><div class="files"><div class="file"><p class="fileinfo"><a href="/costumade/src/movie.mp4">movie.mp4</a></p><a href="/player.php"><img class="post-image" src="/costumade/thumb/movie.jpg"></a></div></div><p class="intro"><span class="name">回覆者</span><time datetime="2026-06-20T05:40:52Z">2026</time></p><div class="body"><a href="/costumade/res/3212.html#3212">&gt;&gt;3212</a></div></div>
              <div class="post reply" id="reply_3221"><p class="intro"><span class="name">另一位</span><time datetime="2026-06-20T06:40:52Z">2026</time></p><div class="body">純文字</div></div>
              <span class="omitted">2 則貼文 已省略。</span>
            </div>
        """.trimIndent()
    }
}
