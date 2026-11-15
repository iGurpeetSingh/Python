package com.orbit.agent.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class YouTubeCommandParserTest {
    @Test fun parsesColonDuration() {
        assertEquals(543L, YouTubeCommandParser.parse("YouTube पर 09:03 वाली video upload करो")?.durationSeconds)
    }

    @Test fun parsesEnglishSpokenDuration() {
        assertEquals(543L, YouTubeCommandParser.parse("Upload the 9 minute 3 second video to YouTube")?.durationSeconds)
    }

    @Test fun parsesHindiSpokenDuration() {
        assertEquals(543L, YouTubeCommandParser.parse("यूट्यूब पर नौ मिनट तीन सेकंड वाली वीडियो अपलोड करो")?.durationSeconds)
    }

    @Test fun ignoresCommandsWithoutYoutubeIntent() {
        assertEquals(null, YouTubeCommandParser.parse("Instagram खोलो"))
    }

    @Test fun keepsDurationUnknownWhenNotProvided() {
        assertNotNull(YouTubeCommandParser.parse("YouTube upload करो"))
        assertEquals(null, YouTubeCommandParser.parse("YouTube upload करो")?.durationSeconds)
    }
}
