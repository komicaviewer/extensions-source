package tw.kevinzhang.newshub.extension.sora.komica.parser

import okhttp3.HttpUrl
import org.jsoup.nodes.Element
import tw.kevinzhang.newshub.extension.sora.komica.parser.PostHeadParser
import tw.kevinzhang.newshub.extension.sora.komica.toTimestamp
import java.util.logging.Logger

private val logger = Logger.getLogger("SoraPostHeadParser")


internal class SoraPostHeadParser: PostHeadParser {
    override fun parseTitle(source: Element, url: HttpUrl): String? {
        val titleE = source.selectFirst("span.title")
        return titleE?.text()
    }

    override fun parseCreatedAt(source: Element, url: HttpUrl): Long {
        try {
            val postHead = source.selectFirst("div.post-head")
                ?: throw IllegalArgumentException("找不到 div.post-head")

            val nowSpan = postHead.selectFirst("span.now")
            val idSpan = postHead.selectFirst("span.id")

            val fullText = when {
                nowSpan != null -> nowSpan.text().trim()
                idSpan != null -> idSpan.text().trim()
                else -> throw IllegalArgumentException("找不到日期時間資訊")
            }

            // 支援四位年份與站上使用的兩位年份；兩位年份固定視為 2000-2099。
            val datePattern = """(\d{4}|\d{2})/(\d{2})/(\d{2})\([^)]+\)""".toRegex()
            val dateMatch = datePattern.find(fullText)

            val dateStr = dateMatch?.let { match ->
                val year = match.groupValues[1].let { value ->
                    if (value.length == 2) "20$value" else value
                }
                "$year/${match.groupValues[2]}/${match.groupValues[3]}"
            } ?: throw IllegalArgumentException("找不到日期: $fullText")

            // 解析時間: "09:14:34.294" -> "09:14"
            val timePattern = """(\d{2}:\d{2}):\d{2}""".toRegex()
            val timeMatch = timePattern.find(fullText)

            val timeMinute = timeMatch?.groupValues?.get(1)
                ?: run {
                    // 嘗試從下一個 sibling 找時間
                    var nextElement = nowSpan?.nextElementSibling()
                    while (nextElement != null) {
                        val text = nextElement.text()
                        val match = """(\d{2}:\d{2})""".toRegex().find(text)
                        if (match != null) {
                            return@run match.groupValues[1]
                        }
                        nextElement = nextElement.nextElementSibling()
                    }
                    throw IllegalArgumentException("找不到時間: $fullText")
                }

            // 組合並轉換為時間戳
            val dateTimeStr = "$dateStr $timeMinute"
            return dateTimeStr.toTimestamp("yyyy/MM/dd HH:mm")
        } catch (e: Exception) {
            logger.warning("parseCreatedAt 失敗: ${e.message}\n${e.stackTraceToString()}")
            return 0
        }
    }

    override fun parsePoster(source: Element, url: HttpUrl): String? {
        try {
            val postHead = source.selectFirst("div.post-head")
                ?: throw IllegalArgumentException("找不到 div.post-head")

            val nowSpan = postHead.selectFirst("span.now")
            val idSpan = postHead.selectFirst("span.id")

            val fullText = when {
                nowSpan != null -> nowSpan.text().trim()
                idSpan != null -> idSpan.text().trim()
                else -> throw IllegalArgumentException("找不到 ID 資訊")
            }

            // 從文字中提取 ID: "2026/04/09(四) 09:14:34.294 ID:xQMpQaFM" -> "xQMpQaFM"
            val idPattern = """ID:([A-Za-z0-9/]+)""".toRegex()
            val idMatch = idPattern.find(fullText)

            return idMatch?.groupValues?.get(1)
                ?: idSpan?.attr("data-id")?.trim()?.takeIf { it.isNotEmpty() }
                ?: idSpan?.text()?.replace("ID:", "")?.trim()
                ?: throw IllegalArgumentException("找不到 ID: $fullText")
        } catch (e: Exception) {
            logger.warning("parsePoster 失敗: ${e.message}\n${e.stackTraceToString()}")
            return null
        }
    }
}
