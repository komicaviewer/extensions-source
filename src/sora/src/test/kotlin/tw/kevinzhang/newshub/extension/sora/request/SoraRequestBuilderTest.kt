package tw.kevinzhang.newshub.extension.sora.request

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class SoraRequestBuilderTest {

    @Test
    fun `summary builder adds page_num only after the first page`() {
        val secondPage = SoraThreadSummariesRequestBuilder()
            .setUrl("https://gita.komica1.org/00b/pixmicat.php?mode=module".toHttpUrl())
            .setPage(2)
            .build()

        val firstPage = SoraThreadSummariesRequestBuilder()
            .setUrl(secondPage.url)
            .setPage(1)
            .build()

        assertEquals(
            "https://gita.komica1.org/00b/pixmicat.php?mode=module&page_num=2",
            secondPage.url.toString(),
        )
        assertEquals(
            "https://gita.komica1.org/00b/pixmicat.php?mode=module",
            firstPage.url.toString(),
        )
    }

    @Test
    fun `thread builder creates pixmicat endpoint from an explicit board URL`() {
        val request = SoraThreadRequestBuilder()
            .setUrl("https://example.org/board/".toHttpUrl())
            .setRes("12345")
            .setFragment("r67890")
            .build()

        assertEquals(
            "https://example.org/board/pixmicat.php?res=12345#r67890",
            request.url.toString(),
        )
    }
}
