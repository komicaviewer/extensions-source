package tw.kevinzhang.newshub.extension.hackernews

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import tw.kevinzhang.extension_api.model.Paragraph

internal class HackerNewsHtmlParser {
    fun plainText(html: String?): String? = html
        ?.takeIf(String::isNotBlank)
        ?.let { Jsoup.parseBodyFragment(it).text().trim() }
        ?.takeIf(String::isNotBlank)

    fun parse(html: String?): List<Paragraph> {
        if (html.isNullOrBlank()) return emptyList()
        val body = Jsoup.parseBodyFragment(html, HN_WEB_BASE).body()
        val result = mutableListOf<Paragraph>()
        body.childNodes().forEach { node -> appendNode(node, result) }
        return result.filterNot { paragraph ->
            paragraph is Paragraph.Text && paragraph.content.isBlank()
        }
    }

    private fun appendNode(node: Node, result: MutableList<Paragraph>) {
        when (node) {
            is TextNode -> addText(node.text(), result)
            is Element -> when (node.tagName()) {
                "blockquote" -> node.text().trim().takeIf(String::isNotBlank)
                    ?.let { result += Paragraph.Quote(it) }
                "br" -> Unit
                "pre", "code" -> addText(node.wholeText(), result)
                "a" -> {
                    addText(node.text(), result)
                    node.absUrl("href").takeIf(String::isNotBlank)
                        ?.let { result += Paragraph.Link(it) }
                }
                else -> {
                    node.childNodes().forEach { child -> appendNode(child, result) }
                    node.select("a[href]").map { it.absUrl("href") }
                        .filter(String::isNotBlank)
                        .forEach { url ->
                            if (result.none { it is Paragraph.Link && it.content == url }) {
                                result += Paragraph.Link(url)
                            }
                        }
                }
            }
        }
    }

    private fun addText(value: String, result: MutableList<Paragraph>) {
        value.trim().takeIf(String::isNotBlank)?.let { result += Paragraph.Text(it) }
    }

    private companion object {
        const val HN_WEB_BASE = "https://news.ycombinator.com/"
    }
}
