package tw.kevinzhang.newshub.extension.site2cat.parser

import okhttp3.HttpUrl
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import tw.kevinzhang.komica_api.parser.PostHeadParser
import tw.kevinzhang.komica_api.parser.UrlParser
import tw.kevinzhang.komica_api.toTimestamp
import java.util.logging.Logger

private val logger = Logger.getLogger("_2catPostHeadParser")

class Site2catPostHeadParser(
    private val urlParser: UrlParser,
): PostHeadParser {
    override fun parseTitle(source: Element, url: HttpUrl): String? {
        source.selectFirst("span.title")?.let {
            return it.text().trim()
        }
        return null
    }

    override fun parseCreatedAt(source: Element, url: HttpUrl): Long {
        return try {
            // 尋找包含日期時間和 ID 的文字節點
            val dateTimeText = findDateTimeText(source)
                ?: throw IllegalArgumentException("找不到包含日期和ID的文字節點")

            // 提取 [日期時間 ID:xxx] 格式
            val pattern = """\[(.+?)\s+ID:.+?\]""".toRegex()
            val matchResult = pattern.find(dateTimeText)
                ?: throw IllegalArgumentException("無法解析日期時間: $dateTimeText")

            val dateTimeStr = matchResult.groupValues[1].trim()
            // 可能是: "26/03/07(六)17:08" 或 "25/10/01(三)01:01"

            // 移除星期符號並解析
            parseDateTimeString(dateTimeStr)

        } catch (e: Exception) {
            logger.warning("parseCreatedAt 失敗: ${e.message}\n${e.stackTraceToString()}")
            0
        }
    }

    override fun parsePoster(source: Element, url: HttpUrl): String? {
        return try {
            // 尋找包含日期時間和 ID 的文字節點
            val dateTimeText = findDateTimeText(source)
                ?: throw IllegalArgumentException("找不到包含日期和ID的文字節點")

            // 提取 ID (可能包含特殊字符)
            val pattern = """ID:([^\]]+)""".toRegex()
            val matchResult = pattern.find(dateTimeText)
                ?: throw IllegalArgumentException("無法解析ID: $dateTimeText")

            matchResult.groupValues[1].trim()

        } catch (e: Exception) {
            logger.warning("parsePoster 失敗: ${e.message}\n${e.stackTraceToString()}")
            ""
        }
    }

    private fun findDateTimeText(element: Element): String? {
        fun searchNodes(node: Node): String? {
            if (node is TextNode) {
                val text = node.text()
                if (text.contains("ID:") && text.contains("[")) {
                    return text
                }
            }

            for (child in node.childNodes()) {
                searchNodes(child)?.let { return it }
            }

            return null
        }

        return searchNodes(element)
    }

    /**
     * 輔助方法: 解析 Komica2 的日期時間字串
     * @param dateTimeStr 例如: "26/03/07(六)17:08" 或 "25/10/01(三)01:01"
     * @return Unix 時間戳 (毫秒)
     */
    private fun parseDateTimeString(dateTimeStr: String): Long {
        // 移除星期: "26/03/07(六)17:08" -> "26/03/07 17:08"
        val cleanDateTime = dateTimeStr.replace(Regex("""\([^)]+\)"""), " ").trim()

        // 分割: ["26", "03", "07", "17", "08"]
        val parts = cleanDateTime.split(Regex("""[/\s:]+"""))

        if (parts.size < 5) {
            throw IllegalArgumentException("日期時間格式錯誤: $cleanDateTime, parts: $parts")
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
