package com.orbit.agent.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoMetadataTest {
    @Test fun seoDraftIsEditableAndUnlisted() {
        val draft = SeoMetadataBuilder.draft("My_Travel_Video.mp4")
        assertEquals("unlisted", draft.privacyStatus)
        assertEquals(null, draft.validate())
    }

    @Test fun rejectsNonUnlistedPrivacyForThisWorkflow() {
        val draft = SeoMetadataBuilder.draft("video.mp4").copy(privacyStatus = "public")
        assertEquals("इस workflow में Upload privacy Unlisted ही होनी चाहिए।", draft.validate())
    }

    @Test fun rejectsTitleOverYoutubeLimit() {
        val draft = VideoMetadata("x".repeat(101), "", emptyList())
        assertEquals("YouTube title 100 characters से अधिक नहीं हो सकता।", draft.validate())
    }

    @Test fun acceptsMaximumTitleLength() {
        val draft = VideoMetadata("x".repeat(100), "", emptyList())
        assertNull(draft.validate())
    }
}
