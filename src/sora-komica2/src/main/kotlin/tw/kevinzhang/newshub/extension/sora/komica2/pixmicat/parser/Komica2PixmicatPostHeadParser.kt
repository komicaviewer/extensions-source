package tw.kevinzhang.newshub.extension.sora.komica2.pixmicat.parser

import tw.kevinzhang.newshub.extension.sora.komica2.pixmicat.parser.Parser
import tw.kevinzhang.newshub.extension.sora.komica2.pixmicat.parser.ParsedPost
import tw.kevinzhang.newshub.extension.sora.komica2.pixmicat.parser.ParsedPostBuilder
import tw.kevinzhang.newshub.extension.sora.komica2.pixmicat.parser.PostHeadParser
import tw.kevinzhang.newshub.extension.sora.komica2.pixmicat.parser.UrlParser

import okhttp3.HttpUrl
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import tw.kevinzhang.newshub.extension.sora.komica2.pixmicat.toTimestamp
import java.util.logging.Logger

private val logger = Logger.getLogger("Komica2PostHeadParser")

internal class Komica2PixmicatPostHeadParser : PostHeadParser {
    override fun parseTitle(source: Element, url: HttpUrl): String? {
        val titleE = source.selectFirst("span.title")
        return titleE?.text()
    }

    override fun parseCreatedAt(source: Element, url: HttpUrl): Long? {
        return try {
            val dateTimeText = findMetadataText(source)
                ?: throw IllegalArgumentException("找不到包含日期和ID的文字節點")

            // 提取 [日期時間 ID:xxx] 格式
            val pattern = """\[(.+?)\s+ID:.+?\]""".toRegex()
            val matchResult = pattern.find(dateTimeText)
                ?: throw IllegalArgumentException("無法解析日期時間: $dateTimeText")

            val dateTimeStr = matchResult.groupValues[1].trim() // 例如: 25/10/01(三)01:01

            // 移除星期符號並解析
            parseDateTimeString(dateTimeStr)

        } catch (e: Exception) {
            logger.warning("parseCreatedAt 失敗: ${e.message}\n${e.stackTraceToString()}")
            0
        }
    }


    override fun parsePoster(source: Element, url: HttpUrl): String? {
        return try {
            val dateTimeText = findMetadataText(source)
                ?: throw IllegalArgumentException("找不到包含日期和ID的文字節點")

            // 提取 ID
            val pattern = """ID:([^\]]+)""".toRegex()
            val matchResult = pattern.find(dateTimeText)
                ?: throw IllegalArgumentException("無法解析ID: $dateTimeText")

            matchResult.groupValues[1].trim()

        } catch (e: Exception) {
            logger.warning("parsePoster 失敗: ${e.message}\n${e.stackTraceToString()}")
            ""
        }
    }


    /**
     * Prefer the metadata text following span.name, used by newer Komica2 pages. Older
     * Pixmicat pages such as touhoux omit span.name and place the same bracketed metadata
     * directly in the post, so fall back to the post's complete text.
     */
    private fun findMetadataText(source: Element): String? {
        source.selectFirst("span.name")?.let { nameSpan ->
            var currentNode = nameSpan.nextSibling()
            while (currentNode != null) {
                if (currentNode is TextNode) {
                    val text = currentNode.text()
                    if (text.contains("ID:")) return text
                }
                currentNode = currentNode.nextSibling()
            }
        }
        return source.text().takeIf { it.contains("ID:") }
    }

    /**
     * 輔助方法: 解析 Komica2 的日期時間字串
     * @param dateTimeStr 例如: "25/10/01(三)01:01"
     * @return Unix 時間戳 (毫秒)
     */
    private fun parseDateTimeString(dateTimeStr: String): Long {
        // 移除星期: "25/10/01(三)01:01" -> "25/10/01 01:01"
        val cleanDateTime = dateTimeStr.replace(Regex("""\([^)]+\)"""), " ").trim()

        // 分割: ["25", "10", "01", "01", "01"]
        val parts = cleanDateTime.split(Regex("""[/\s:]+"""))

        if (parts.size < 5) {
            throw IllegalArgumentException("日期時間格式錯誤: $cleanDateTime")
        }

        // 補上世紀
        val year = parts[0].toInt()
        val fullYear = if (year in 0..99) 2000 + year else year
        val month = parts[1].padStart(2, '0')
        val day = parts[2].padStart(2, '0')
        val hour = parts[3].padStart(2, '0')
        val minute = parts[4].padStart(2, '0')

        // 組合完整日期時間
        val fullDateTime = "$fullYear/$month/$day $hour:$minute"

        // 使用 java.time 轉換
        return fullDateTime.toTimestamp("yyyy/MM/dd HH:mm")
    }

}
