package tw.kevinzhang.newshub.extension.eyny

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kevinzhang.extension_api.AuthSpec
import tw.kevinzhang.extension_api.WebLoginUserAgentProvider
import tw.kevinzhang.extension_api.model.Paragraph

class EynyParserTest {
    private val parser = EynyParser()

    @Test fun `catalog retains visible boards including restricted categories`() {
        val catalog = parser.parseCatalog("""
            <a href='forum.php?gid=1' title='一般討論'>分類</a><table><td id='category_1'><a href='forum-27-1.html'>吹水聊天室</a></td></table>
            <a href='forum.php?gid=2' title='成人專區'>分類</a><table><td id='category_2'><a href='forum-999-1.html'>限制級討論</a></td></table>
        """.trimIndent())
        assertEquals(listOf("1", "2"), catalog.categories.map { it.id })
        assertEquals(listOf("一般討論", "成人專區"), catalog.categories.map { it.name })
        assertEquals("https://eyny.com/forum-999-1.html", catalog.boards.last().board.url)
    }

    @Test fun `catalog keeps masked categories and detail page expands their boards`() {
        val catalog = parser.parseCatalog("""
            <a href='forum.php?gid=333'><img src='adult.jpg'></a>
            <table><td id='category_333'><a href='forum.php?view=all'>成人貼圖</a></td></table>
        """.trimIndent())
        assertEquals(listOf("333"), catalog.categories.map { it.id })
        assertEquals(listOf("成人話題"), catalog.categories.map { it.name })
        assertTrue(catalog.boards.isEmpty())

        val boards = parser.parseCategoryBoards("""
            <a href='forum.php?mod=forumdisplay&amp;fid=43'>頁首廣告</a>
            <div id='ct'><table class='fl_tb'>
              <tr><td><a href='forum-577-1.html'><img></a><h2><a href='forum-577-1.html'>成人貼圖</a></h2>
                <p><a href='forum.php?mod=forumdisplay&amp;fid=32'>日韓美女</a></p></td></tr>
            </table></div>
        """.trimIndent(), catalog.categories.single())
        assertEquals(listOf("成人貼圖", "日韓美女"), boards.map { it.board.name })
        assertEquals(listOf("https://eyny.com/forum-577-1.html", "https://eyny.com/forum-32-1.html"), boards.map { it.board.url })
    }

    @Test fun `summary parses normal rows and excludes stickies`() {
        val result = parser.parseSummaries("""
            <table><tbody id='stickthread_1'><tr><td><a href='thread-1-1-1.html'>置頂</a></td></tr></tbody>
            <tbody id='normalthread_2'><tr><td class='icn'><a href='thread-144-1-DD276MYS.html'><img></a><a class='xst' href='thread-144-1-DD276MYS.html'>一般主題</a></td><td class='by'><cite>匿名</cite></td><td class='num'><a>12</a></td></tr></tbody></table>
        """.trimIndent(), board(), null)
        assertEquals(1, result.size)
        assertEquals("https://eyny.com/thread-144-1-DD276MYS.html", result.single().id)
        assertEquals(12, result.single().replyCount)
    }

    @Test fun `thread maps every floor to a post and next canonical token`() {
        val page = parser.parseThreadPage("""
            <table id='pid100'><tr><td class='authi'><a class='xw1'>甲</a><em id='authorposton100'><span title='2026-07-20 12:30'>發表</span></em></td><td id='postmessage_100'>第一樓 <a href='https://example.com'>連結</a></td></tr></table>
            <table id='pid101'><tr><td class='authi'><a class='xw1'>乙</a></td><td id='postmessage_101'><blockquote>引用內容</blockquote><img src='https://img.example/a.jpg' zoomfile='https://img.example/full.jpg'></td></tr></table>
            <a rel='next' href='thread-144-2-1.html'>下一頁</a>
        """.trimIndent(), "https://eyny.com/thread-144-1-1.html", null)
        assertEquals(listOf("100", "101"), page.posts.map { it.id })
        assertEquals("https://img.example/full.jpg", (page.posts[1].content.filterIsInstance<Paragraph.ImageInfo>().single()).raw)
        assertNotNull(page.posts[0].createdAt)
        assertEquals("https://eyny.com/thread-144-2-1.html", page.nextToken)
    }

    @Test fun `locked content is distinct from footer reply login prompt`() {
        parser.parseThreadPage("<table id='pid1'><td id='postmessage_1'>公開內容</td></table><div id='f_pst'>您需要登錄後才可以回帖</div>", "https://eyny.com/thread-1-1-1.html", null)
        try {
            parser.parseThreadPage("<table id='pid1'><td id='postmessage_1'><div class='locked'>登入後才能瀏覽完整內容</div></td></table>", "https://eyny.com/thread-1-1-1.html", null)
            throw AssertionError("expected lock")
        } catch (_: EynyLockedException) { }
    }

    @Test fun `blank protected pages fail instead of looking like empty content`() {
        try {
            parser.parseSummaries("<body class='pg_forumdisplay'><header>已登入</header></body>", board(), null)
            throw AssertionError("expected unavailable board")
        } catch (_: EynyUnavailableException) { }
        try {
            parser.parseThreadPage("<title>密碼安全</title><body class='pg_spacecp'>密碼安全</body>", "https://eyny.com/thread-1-1-1.html", null)
            throw AssertionError("expected account action")
        } catch (error: EynyLockedException) {
            assertTrue(error.message.orEmpty().contains("security setup"))
        }
    }

    @Test fun `url policy rejects foreign hosts and cross thread parsing`() {
        assertNull(EynyUrlPolicy.thread("https://evil.example/thread-1-1-1.html"))
        assertNull(EynyUrlPolicy.resolve("https://eyny.com/thread-1-1-1.html", "https://evil.example/thread-1-2-1.html"))
        assertEquals("144", EynyUrlPolicy.thread("https://www51.eyny.com/thread-144-2-1.html")?.tid)
        assertEquals("DD276MYS", EynyUrlPolicy.thread("https://www53.eyny.com/thread-14437342-1-DD276MYS.html")?.extra)
        assertEquals(32, EynyUrlPolicy.board("https://eyny.com/forum.php?mod=forumdisplay&fid=32")?.fid)
        assertEquals("144", EynyUrlPolicy.thread("https://eyny.com/forum.php?mod=viewthread&tid=144&page=2&extra=ABC_123")?.tid)
        assertNull(EynyUrlPolicy.thread("https://eyny.com/thread-144-1-%2Fetc.html"))
    }

    @Test fun `challenge parser and solver honour bounded difficulty`() = runBlocking {
        val html = """<script>var challenge = "abcdeffedcba1234"; var ts = "1784585056"; var diff = 1; document.cookie = "9bd3f9c_n=" + nonce;</script>"""
        val challenge = requireNotNull(EynyChallengeSolver.parse(html))
        assertNotNull(EynyChallengeSolver.solve(challenge))
        try {
            EynyChallengeSolver.parse("<script>var challenge='abcdeffedcba1234';var ts='1784585056';var diff=99;document.cookie='x_n=';</script>")
            throw AssertionError("expected safety rejection")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun `difficulty four challenge is solved without hex string allocation`() = runBlocking {
        val challenge = EynyChallenge(
            challenge = "0979c1c29ad14faae09cf23af6e79666",
            timestamp = "1784648155",
            difficulty = 4,
            cookiePrefix = "83fd0e2",
        )

        assertEquals(119_310L, EynyChallengeSolver.solve(challenge))
    }

    @Test fun `signed in requires nonzero discuz uid`() {
        assertFalse(parser.signedIn("var discuz_uid = '0';"))
        assertTrue(parser.signedIn("var discuz_uid = '12345';"))
    }

    @Test fun `post clock accepts twelve hour marker`() {
        val page = parser.parseThreadPage("<table id='pid1'><td><em id='authorposton1'><span title='2026-7-20 01:30 PM'>發表</span></em></td><td id='postmessage_1'>內容</td></table>", "https://eyny.com/thread-1-1-1.html", null)
        assertNotNull(page.posts.single().createdAt)
    }

    @Test fun `auth spec uses bounded exact mirror hosts`() {
        val source = EynySource()
        val spec = source.authSpec as AuthSpec.WebCookie

        assertTrue("eyny.com" in spec.allowedHosts)
        assertTrue("www00.eyny.com" in spec.allowedHosts)
        assertTrue("www53.eyny.com" in spec.allowedHosts)
        assertTrue("www99.eyny.com" in spec.allowedHosts)
        assertFalse(spec.allowedHosts.any { '*' in it })
        assertEquals(spec.allowedHosts.mapTo(linkedSetOf()) { "https://$it" }, spec.cookieOrigins)
        assertEquals(EYNY_USER_AGENT, (source as WebLoginUserAgentProvider).webLoginUserAgent)
    }

    private fun board() = tw.kevinzhang.extension_api.model.Board(EynySource.SOURCE_ID, "https://eyny.com/forum-27-1.html", "吹水")
}
