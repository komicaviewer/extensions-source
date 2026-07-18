package tw.kevinzhang.newshub.extension.komica2

import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kevinzhang.komica_api.model.KImageInfo
import tw.kevinzhang.newshub.extension.komica2.model.Komica2Boards

class HisoParserTest {
    @Test
    fun `summary parser groups bare thread posts without duplicates and uses lazy thumbnail`() {
        val request = Komica2Factory()
            .createThreadSummariesRequestBuilder(Komica2Boards.all.last())
            .setPage(1)
            .build()

        val posts = Komica2Factory()
            .createThreadSummariesParser(Komica2Factory().createThreadUrlParser())
            .parse(HISO_FIXTURE.toResponseBody(), request)

        assertEquals(listOf("101", "201"), posts.map { it.id })
        assertEquals(posts.size, posts.map { it.id }.toSet().size)
        assertEquals(listOf(3, 0), posts.map { it.replies })

        val firstImage = posts.first().content.filterIsInstance<KImageInfo>().single()
        assertEquals("https://p.2cat.org/hiso/thumb/101s.jpg", firstImage.thumb)
        assertEquals("https://p.2cat.org/hiso/src/101.jpg", firstImage.raw)
        assertTrue(posts.first().content.filterIsInstance<KImageInfo>().none { it.thumb == "/share/theme/white.png" })
    }

    private companion object {
        val HISO_FIXTURE = """
            <div id="threads">
              <div class="threadpost" id="r101">
                <a href="//p.2cat.org/hiso/src/101.jpg"><img class="img" src="/share/theme/white.png" data-original="//p.2cat.org/hiso/thumb/101s.jpg"></a>
                <span class="title">First</span> 名稱: <span class="name">無名氏</span> [25/01/01(三)12:00 ID:first]
                <div class="quote">first content</div>
              </div>
              <div class="reply" id="r102"><div class="replywrap"><div class="quote">reply</div></div></div>
              <span class="warn_txt2">有回應 2 篇被省略。要閱讀所有回應請按下回應連結。</span>
              <hr>
              <div class="threadpost" id="r201">
                <a href="//p.2cat.org/hiso/src/201.jpg"><img class="img" src="/share/theme/white.png" data-original="//p.2cat.org/hiso/thumb/201s.jpg"></a>
                <span class="title">Second</span> 名稱: <span class="name">無名氏</span> [25/01/02(四)12:00 ID:second]
                <div class="quote">second content</div>
              </div>
              <hr>
            </div>
        """.trimIndent()
    }
}
