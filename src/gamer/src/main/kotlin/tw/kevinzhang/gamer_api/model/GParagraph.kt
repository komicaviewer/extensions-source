package tw.kevinzhang.gamer_api.model

interface GParagraph {
    val content: String
}

data class GImageInfo(
    val thumb: String? = null,
    val raw: String,
): GParagraph {
    override val content = thumb ?: raw
}

data class GText(
    override val content: String,
): GParagraph

data class GQuote(
    override val content: String,
): GParagraph

data class GReplyTo(
    override val content: String,
): GParagraph

data class GLink(
    override val content: String,
): GParagraph

/**
 * Text that must retain its inline HTML semantics.  The extension API exposes a
 * semantic palette instead of raw CSS values, so the parser keeps the same
 * compact representation until [GamerSource] maps it to the public model.
 */
data class GRichText(
    val runs: List<GRichTextRun>,
): GParagraph {
    override val content: String = runs.joinToString(separator = "") { it.text }
}

data class GRichTextRun(
    val text: String,
    val color: GTextColor = GTextColor.DEFAULT,
    val emphasis: GTextEmphasis = GTextEmphasis.NORMAL,
    val linkUrl: String? = null,
)

enum class GTextColor {
    DEFAULT,
    BLACK,
    RED,
    GREEN,
    YELLOW,
    BLUE,
    MAGENTA,
    CYAN,
    WHITE,
}

enum class GTextEmphasis { NORMAL, BRIGHT }

enum class GVideoSite { YOUTUBE, OTHER }

data class GVideoInfo(
    val url: String,
    val site: GVideoSite,
): GParagraph {
    override val content = url
}

fun List<GParagraph>.trim(): List<GParagraph> {
    val first = this.indexOfFirst { it.content.trim().isNotBlank() }
    val last = this.indexOfLast { it.content.trim().isNotBlank() }
    return if (first == -1 || last == -1) {
        emptyList()
    } else {
        subList(first, last + 1).mapIndexedNotNull { index, paragraph ->
            if (paragraph !is GRichText) return@mapIndexedNotNull paragraph

            val runs = paragraph.runs.toMutableList()
            if (index == 0) trimRunStart(runs)
            if (index == last - first) trimRunEnd(runs)
            GRichText(runs.filter { it.text.isNotEmpty() }).takeIf { it.runs.isNotEmpty() }
        }
    }
}

private fun trimRunStart(runs: MutableList<GRichTextRun>) {
    while (runs.isNotEmpty()) {
        val first = runs.first()
        val trimmed = first.text.trimStart()
        if (trimmed.isEmpty()) {
            runs.removeAt(0)
        } else {
            runs[0] = first.copy(text = trimmed)
            return
        }
    }
}

private fun trimRunEnd(runs: MutableList<GRichTextRun>) {
    while (runs.isNotEmpty()) {
        val index = runs.lastIndex
        val last = runs[index]
        val trimmed = last.text.trimEnd()
        if (trimmed.isEmpty()) {
            runs.removeAt(index)
        } else {
            runs[index] = last.copy(text = trimmed)
            return
        }
    }
}
