package tw.kevinzhang.newshub.extension.wtako

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kevinzhang.extension_api.model.Paragraph

class WtakoParserTest {
    private val parser = WtakoParser()

    @Test
    fun `summary parser handles a grid card with protocol relative media and omitted replies`() {
        val posts = parser.parseSummaries(RTHOST_LIST, "https://rthost.win/sd")

        assertEquals(1, posts.size)
        assertEquals("391476", posts.single().id)
        assertEquals("有趣截圖祭", posts.single().title)
        assertEquals("無名氏", posts.single().author)
        assertEquals(12, posts.single().replies)
        assertEquals(
            "https://rthost.win/sd/pixmicat.php?res=391476",
            posts.single().url,
        )
        val image = posts.single().content.filterIsInstance<Paragraph.ImageInfo>().single()
        assertEquals("https://rthost.win/sd/src/1528104841802.png", image.raw)
        assertEquals("https://rthost.win/sd/thumb/1528104841802s.jpg", image.thumb)
    }

    @Test
    fun `thread parser maps reply references links images videos and previews`() {
        val posts = parser.parseThread(KARLSLAND_THREAD, "https://www.karlsland.net/sw/pixmicat.php?res=43871")

        assertEquals(listOf("43871", "44259"), posts.map { it.id })
        val reply = posts[1]
        assertTrue(reply.content.filterIsInstance<Paragraph.ReplyTo>().any { it.targetId == "43871" })
        assertEquals("首文內容", reply.content.filterIsInstance<Paragraph.ReplyTo>().single().preview)
        assertTrue(reply.content.filterIsInstance<Paragraph.Link>().any { it.content == "https://example.org/info" })
        assertTrue(reply.content.filterIsInstance<Paragraph.VideoInfo>().any { it.url == "https://www.karlsland.net/sw/src/movie.webm" })
        val image = reply.content.filterIsInstance<Paragraph.ImageInfo>().single()
        assertEquals("https://www.karlsland.net/sw/src/1770076508178.jpg", image.raw)
        assertEquals("https://www.karlsland.net/sw/thumb/1770076508178s.jpg", image.thumb)
    }

    @Test
    fun `parser resolves relative WTako attachment URLs`() {
        val post = parser.parseSummaries(WTAKO_LIST, "https://kemono.wtako.net/kemono").single()
        val image = post.content.filterIsInstance<Paragraph.ImageInfo>().single()

        assertEquals("https://kemono.wtako.net/kemono/src/1596588476076.gif", image.raw)
        assertEquals("https://kemono.wtako.net/kemono/thumb/1596588476076s.jpg", image.thumb)
        assertTrue(post.content.filterIsInstance<Paragraph.Text>().any { it.content.contains("獸版") })
    }

    private companion object {
        val RTHOST_LIST = """
            <div id="threads"><div class="grid">
              <div class="threadpost" id="r391476"><span class="title">有趣截圖祭</span>
              名稱: <span class="name">無名氏</span> [18/06/04(一)17:34 ID:WMkgEzfA]
              <a href="pixmicat.php?res=391476#q391476" class="qlink">No.391476</a>
              <a href="//rthost.win/sd/src/1528104841802.png"><img class="img" src="//rthost.win/sd/thumb/1528104841802s.jpg"></a>
              <div class="quote">文字內容<br>第二行</div></div>
              <span class="warn_txt2">有回應 12 篇被省略。</span>
            </div></div>
        """.trimIndent()

        val KARLSLAND_THREAD = """
            <div id="threads">
              <div class="threadpost" id="r43871"><span class="title">首文</span>
              名稱: <span class="name">EMT</span> [26/01/01(四)08:13 ID:op]
              <div class="quote">首文內容</div></div>
              <div class="reply" id="r44259"><span class="title">回覆</span>
              名稱: <span class="name">EMT</span> [26/02/03(二)07:55 ID:reply]
              <a href="pixmicat.php?res=43871#q43871" class="qlink">No.43871</a>
              <div class="quote"><span class="resquote"><a class="qlink">&gt;&gt;No.43871</a></span>
              <a href="https://example.org/info">外部連結</a></div>
              <a href="/sw/src/1770076508178.jpg"><img class="img" src="/sw/thumb/1770076508178s.jpg"></a>
              <a href="/sw/src/movie.webm">影片</a></div>
            </div>
        """.trimIndent()

        val WTAKO_LIST = """
            <div id="threads"><div class="threadpost" id="r26490">
              <span class="title">獸圖</span> 名稱: <span class="name">無名獸</span> [16/08/22(一)12:18 ID:UBVtd4RQ]
              <a href="./src/1596588476076.gif"><img class="img" src="./thumb/1596588476076s.jpg"></a>
              <div class="quote">獸版文字</div>
            </div></div>
        """.trimIndent()
    }
}
