package tw.kevinzhang.newshub.extension.twocat.komica2

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import ru.gildor.coroutines.okhttp.await
import tw.kevinzhang.newshub.extension.runtime.SourceSiteUnavailableException
import tw.kevinzhang.newshub.extension.twocat.komica2.model.TwocatGatedBoard
import tw.kevinzhang.newshub.extension.twocat.komica2.session.TwocatBoardSession
import tw.kevinzhang.newshub.extension.twocat.komica2.session.parseDecoyPath
import tw.kevinzhang.newshub.extension.twocat.komica2.session.summariesUrl
import tw.kevinzhang.newshub.extension.twocat.komica2.session.threadUrl

class TwocatBoardSessionTest {
    private val hanime = TwocatGatedBoard("hanime", "動畫裏")
    private val toy = TwocatGatedBoard("toy", "玩具裏")

    @Test
    fun `parseDecoyPath accepts board path and rejects foreign src`() {
        fun shell(src: String) = "<iframe id='main' src='$src'></iframe>"
        assertEquals("/granblue/", parseDecoyPath(shell("/granblue/")))
        assertNull(parseDecoyPath(shell("//evil.example/")))
        assertNull(parseDecoyPath(shell("/../x/")))
        assertNull(parseDecoyPath(shell("https://2cat.uk/a/")))
        assertNull(parseDecoyPath(shell("/a/b/")))
        assertNull(parseDecoyPath("<html><iframe id='other' src='/granblue/'></iframe></html>"))
    }

    @Test
    fun `summariesUrl and threadUrl rewrite to decoy`() {
        assertEquals("https://2cat.uk/granblue/", hanime.summariesUrl("/granblue/", 1).toString())
        assertEquals("https://2cat.uk/granblue/?page=2", hanime.summariesUrl("/granblue/", 3).toString())
        assertEquals(
            "https://2cat.uk/granblue/?res=18426",
            hanime.threadUrl("/granblue/", "https://2cat.uk/hanime/?res=18426").toString(),
        )
        try {
            hanime.threadUrl("/granblue/", "https://2cat.uk/hanime/")
            fail("missing res must be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `same board twice activates once`() = runBlocking {
        val fake = FakeTwocat()
        val session = TwocatBoardSession(fake.client)
        repeat(2) { assertTitle("動畫裏", session.fetchIndex(fake, hanime)) }
        assertEquals(1, fake.entryCount)
    }

    @Test
    fun `interleaved boards never mix content`() = runBlocking {
        val fake = FakeTwocat()
        val session = TwocatBoardSession(fake.client)
        coroutineScope {
            (0 until 20).map { index ->
                val board = if (index % 2 == 0) hanime else toy
                async { board.expectedTitle to session.fetchIndex(fake, board) }
            }.awaitAll()
        }.forEach { (expected, body) -> assertTitle(expected, body) }
    }

    @Test
    fun `expired session reactivates once`() = runBlocking {
        val fake = FakeTwocat()
        val session = TwocatBoardSession(fake.client)
        assertTitle("動畫裏", session.fetchIndex(fake, hanime))
        fake.expire()
        assertTitle("動畫裏", session.fetchIndex(fake, hanime))
        assertEquals(2, fake.entryCount)
    }

    @Test
    fun `persistent mismatch fails without leaking url`() = runBlocking {
        val fake = FakeTwocat().apply { alwaysMismatch = true }
        val session = TwocatBoardSession(fake.client)
        val failure = expectSiteUnavailable { session.fetchIndex(fake, hanime) }
        assertTrue(!failure.message.orEmpty().contains("2cat"))
        assertEquals(2, fake.entryCount)
    }

    @Test
    fun `activation talks only to 2cat uk`() = runBlocking {
        val fake = FakeTwocat()
        TwocatBoardSession(fake.client).fetchIndex(fake, hanime)
        assertEquals("https://2cat.uk/~hanime/", fake.requests.first())
        assertTrue(fake.requests.all { it.startsWith("https://2cat.uk/") })
    }

    @Test
    fun `shell error fails activation without fetching`() = runBlocking {
        val fake = FakeTwocat().apply { shellStatus = 503 }
        expectSiteUnavailable { TwocatBoardSession(fake.client).fetchIndex(fake, hanime) }
        assertEquals(listOf("https://2cat.uk/~hanime/"), fake.requests)
    }

    @Test
    fun `foreign iframe src fails activation`() = runBlocking {
        val fake = FakeTwocat().apply { shellIframeSrc = "//evil.example/" }
        expectSiteUnavailable { TwocatBoardSession(fake.client).fetchIndex(fake, hanime) }
        assertTrue(fake.requests.none { it.contains("evil") })
    }

    @Test
    fun `entry request carries komica1 referer`() = runBlocking {
        val fake = FakeTwocat()
        TwocatBoardSession(fake.client).fetchIndex(fake, hanime)
        assertEquals(listOf<String?>("https://komica1.org/"), fake.entryReferers)
    }

    private suspend fun TwocatBoardSession.fetchIndex(fake: FakeTwocat, board: TwocatGatedBoard): String =
        withBoard(board) { decoy ->
            fake.client.newCall(Request.Builder().url(board.summariesUrl(decoy, 1)).build()).await()
        }.body!!.string()

    private fun assertTitle(expected: String, body: String) =
        assertTrue("expected $expected in $body", body.contains("<title>$expected"))

    private suspend fun expectSiteUnavailable(block: suspend () -> Unit): SourceSiteUnavailableException {
        try {
            block()
        } catch (failure: SourceSiteUnavailableException) {
            return failure
        }
        fail("expected SourceSiteUnavailableException")
        throw AssertionError()
    }
}
