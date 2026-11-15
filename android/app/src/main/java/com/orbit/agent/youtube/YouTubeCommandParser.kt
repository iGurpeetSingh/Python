package com.orbit.agent.youtube

import java.util.Locale

object YouTubeCommandParser {
    private val numberWords = mapOf(
        "zero" to 0, "शून्य" to 0, "एक" to 1, "one" to 1, "दो" to 2, "two" to 2,
        "तीन" to 3, "three" to 3, "चार" to 4, "four" to 4, "पांच" to 5, "पाँच" to 5,
        "five" to 5, "छह" to 6, "छः" to 6, "six" to 6, "सात" to 7, "seven" to 7,
        "आठ" to 8, "eight" to 8, "नौ" to 9, "nine" to 9, "दस" to 10, "ten" to 10,
        "ग्यारह" to 11, "बारह" to 12, "तेरह" to 13, "चौदह" to 14, "पंद्रह" to 15
    )

    data class ParsedCommand(val durationSeconds: Long?, val privacyStatus: String = "unlisted")

    fun parse(text: String): ParsedCommand? {
        val normalized = text.lowercase(Locale.ROOT)
        val asksUpload = listOf("youtube", "यूट्यूब", "upload", "अपलोड").any(normalized::contains)
        if (!asksUpload) return null

        val colon = Regex("""(?<!\d)(\d{1,2}):(\d{2})(?!\d)""").find(normalized)
        if (colon != null) {
            val minutes = colon.groupValues[1].toLong()
            val seconds = colon.groupValues[2].toLong()
            if (seconds < 60) return ParsedCommand(minutes * 60 + seconds)
        }

        val spoken = Regex("""(\d+)\s*(?:minute|min|मिनट)\s*(?:(\d+)\s*(?:second|sec|सेकंड))?""")
            .find(normalized)
        if (spoken != null) {
            val mins = spoken.groupValues[1].toLong()
            val secs = spoken.groupValues[2].toLongOrNull() ?: 0L
            if (secs < 60) return ParsedCommand(mins * 60 + secs)
        }

        val hindiMinutes = Regex("""([^\s]+)\s*मिनट""").find(normalized)
        if (hindiMinutes != null) {
            val minutes = numberWords[hindiMinutes.groupValues[1]]?.toLong()
            val hindiSeconds = Regex("""([^\s]+)\s*सेकंड""").find(normalized)
            val seconds = hindiSeconds?.groupValues?.get(1)?.let { numberWords[it]?.toLong() } ?: 0L
            if (minutes != null && seconds < 60L) return ParsedCommand(minutes * 60L + seconds)
        }

        val hindi = Regex("""([\p{L}]+)\s*(?:मिनट|minute|min)\s*(?:([\p{L}]+)\s*(?:सेकंड|second|sec))?""")
            .find(normalized)
        if (hindi != null) {
            val mins = numberWords[hindi.groupValues[1]]?.toLong() ?: return ParsedCommand(null)
            val secs = numberWords[hindi.groupValues[2]]?.toLong() ?: 0L
            if (secs < 60) return ParsedCommand(mins * 60 + secs)
        }
        return ParsedCommand(null)
    }
}
