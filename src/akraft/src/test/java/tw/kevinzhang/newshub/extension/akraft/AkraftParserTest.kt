package tw.kevinzhang.newshub.extension.akraft

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kevinzhang.extension_api.model.Paragraph

class AkraftParserTest {
    private val parser = AkraftParser()
    private val boardUrl = "https://www.akraft.net/service/board-id"

    @Test
    fun `summary parser reads title metadata rich preview and relative thread URL`() {
        val posts = parser.parseSummaries(LIST_FIXTURE, boardUrl)

        assertEquals(1, posts.size)
        val post = posts.single()
        assertEquals("thread-1", post.id)
        assertEquals("新番討論", post.title)
        assertEquals("匿名", post.author)
        assertEquals("https://www.akraft.net/service/board-id/thread-1", post.url)
        assertEquals(0, post.replies)
        assertTrue(post.createdAt > 0)
        assertTrue(post.content.contains(Paragraph.Text("第一行")))
        assertTrue(post.content.contains(Paragraph.Quote("引用文字")))
        assertTrue(post.content.contains(Paragraph.ReplyTo("old-post")))
        assertTrue(post.content.contains(Paragraph.Link("https://www.akraft.net/wiki")))
        assertTrue(post.content.contains(Paragraph.ImageInfo("https://www.akraft.net/thumb.jpg", "https://www.akraft.net/full.jpg")))
        assertTrue(post.content.contains(Paragraph.VideoInfo("https://www.youtube.com/embed/demo")))
    }

    @Test
    fun `thread parser returns original and replies with reply count and reply target`() {
        val posts = parser.parseThread(THREAD_FIXTURE, "$boardUrl/thread-1")

        assertEquals(listOf("thread-1", "reply-1"), posts.map { it.id })
        assertEquals(1, posts.first().replies)
        assertEquals(0, posts.last().replies)
        assertEquals("匿名回覆", posts.last().author)
        assertTrue(posts.last().content.contains(Paragraph.ReplyTo("thread-1")))
        assertTrue(posts.last().content.contains(Paragraph.Text("回覆內容")))
    }

    private companion object {
        val LIST_FIXTURE = """
            <div id="thread-1" class="rounded-lg border bg-card text-card-foreground shadow-sm mb-6 overflow-hidden scroll-mt-20">
              <div class="flex flex-col space-y-1.5 p-6 pb-3">
                <h3><a href="/service/board-id/thread-1">新番討論</a></h3>
                <div class="flex flex-wrap items-center gap-2 text-sm text-gray-500">
                  <span class="font-semibold text-gray-700">匿名</span><span>2026/07/19 10:20</span>
                </div>
              </div>
              <div class="p-6 pt-3">
                <div class="prose"><p>第一行</p><blockquote>引用文字</blockquote><p>&gt;&gt; old-post</p><p><a href="/wiki">站內連結</a></p></div>
                <a href="/full.jpg"><img src="/thumb.jpg" /></a>
                <iframe src="https://www.youtube.com/embed/demo"></iframe>
              </div>
            </div>
        """.trimIndent()

        val THREAD_FIXTURE = """
            <div id="thread-1" class="rounded-lg border bg-card text-card-foreground shadow-sm mb-6 overflow-hidden scroll-mt-20">
              <h3><span>新番討論</span></h3>
              <div class="flex flex-wrap items-center gap-2 text-sm text-gray-500"><span class="font-semibold">匿名</span><span>2026/07/19 10:20</span></div>
              <div class="p-6 pt-3"><div class="prose"><p>首篇內容</p></div></div>
            </div>
            <div id="reply-1" class="scroll-mt-20">
              <div class="flex flex-wrap items-center gap-2 text-sm text-gray-500"><span class="font-semibold">匿名回覆</span><span>2026/07/19 10:21</span></div>
              <div class="mt-2"><div class="prose"><p>&gt;&gt; thread-1</p><p>回覆內容</p></div></div>
            </div>
        """.trimIndent()
    }
}
