package com.orbit.agent.youtube

import java.util.Locale

object SeoMetadataBuilder {
    fun draft(fileName: String, topic: String = "", keywords: List<String> = emptyList()): VideoMetadata {
        val base = fileName.substringBeforeLast('.', fileName)
            .replace(Regex("[_\\-]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val cleanTopic = topic.trim()
        val titleBase = cleanTopic.ifBlank { base }.ifBlank { "New video" }
        val title = titleBase.take(88).let {
            if (it.contains("youtube", true)) it else "$it | Video"
        }.take(100)
        val cleanTags = (keywords + titleBase.split(Regex("[^\\p{L}\\p{N}]+")))
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.length in 2..30 }
            .distinct()
            .take(20)
        val description = buildString {
            append(titleBase)
            append("\\n\\n")
            append("इस वीडियो में: ")
            append(titleBase)
            append("\\n\\n")
            if (cleanTags.isNotEmpty()) append("Topics: ").append(cleanTags.joinToString(", "))
            append("\\n\\n")
            append("#").append(titleBase.split(Regex("\\s+")).take(3).joinToString("") {
                it.filter(Char::isLetterOrDigit).replaceFirstChar { ch -> ch.uppercase() }
            })
        }.take(5000)
        return VideoMetadata(title, description, cleanTags)
    }
}
