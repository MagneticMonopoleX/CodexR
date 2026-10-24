package com.example.codexmobile.api

/** Make provider URL annotations visible in the existing selectable Markdown renderer. */
internal data class InlineSearchCitation(val url: String, val anchor: String)

internal fun renderSearchCitations(text: String, citations: Map<String, String>,
    inline: List<InlineSearchCitation> = emptyList()): String {
    if (citations.isEmpty()) return text
    val linked = StringBuilder(text)
    inline.mapNotNull { citation ->
        val at = text.indexOf(citation.anchor).takeIf { citation.anchor.isNotBlank() && it >= 0 } ?: return@mapNotNull null
        val number = citations.keys.indexOf(citation.url) + 1
        if (number == 0) return@mapNotNull null
        Triple(at + citation.anchor.length, number, citation.url)
    }.distinct().sortedByDescending { it.first }.forEach { (at, number, url) ->
        linked.insert(at, " [$number](<${safeCitationUrl(url)}>)")
    }
    val clean = linked.toString().replace(Regex("\uE200cite\uE202[^\uE201]*\uE201"), "")
    return clean.trimEnd() + "\n\n### Sources\n\n" + citations.entries.joinToString("\n") { (url, title) ->
        val label = title.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]")
            .replace(Regex("[\\r\\n]+"), " ")
        "- [$label](<${safeCitationUrl(url)}>)"
    }
}

private fun safeCitationUrl(url: String) = url.replace("<", "%3C").replace(">", "%3E")
    .replace("\n", "%0A").replace("\r", "%0D")
