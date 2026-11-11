package isomeria.aichat.core

import isomeria.aichat.data.db.UsageDaily
import isomeria.aichat.data.db.UsagePurpose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用量统计里的语音（0.7.0）：合成的字数、秒数和识别的秒数单独合计，不混进 token 和请求次数。 */
class UsageStatsVoiceTest {
    private val rows = listOf(
        UsageDaily(day = 10, contactId = 1, purpose = UsagePurpose.CHAT, promptTokens = 1_000, completionTokens = 200, requests = 3),
        UsageDaily(day = 10, contactId = 1, purpose = UsagePurpose.TTS, promptTokens = 120, completionTokens = 30, requests = 4),
        UsageDaily(day = 10, contactId = 2, purpose = UsagePurpose.ASR, promptTokens = 45, completionTokens = 0, requests = 5),
        UsageDaily(day = 9, contactId = 2, purpose = UsagePurpose.MEMORY, promptTokens = 300, completionTokens = 50, requests = 1),
    )

    @Test
    fun voiceIsCountedSeparately_notInTokensOrRequests() {
        val total = UsageStats.total(rows)
        assertEquals(1_550L, total.totalTokens)
        assertEquals(4, total.requests)
        assertEquals(120L, total.ttsChars)
        assertEquals(30L, total.ttsSeconds)
        assertEquals(45L, total.asrSeconds)
        assertTrue(total.hasVoice)
    }

    @Test
    fun perDayAndPerContact() {
        val today = UsageStats.byDay(rows).first()
        assertEquals(10L, today.key)
        assertEquals(1_200L, today.totalTokens)
        assertEquals(45L, today.asrSeconds)
        val yesterday = UsageStats.byDay(rows).last()
        assertFalse(yesterday.hasVoice)

        val contacts = UsageStats.byContact(rows)
        assertEquals(listOf(1L, 2L), contacts.map { it.key })
        assertEquals(120L, contacts.first().ttsChars)
        assertEquals(350L, contacts.last().totalTokens)
    }

    @Test
    fun voiceOnlyContacts_sortAfterTokenUsers_byVoiceUsage() {
        val voiceOnly = listOf(
            UsageDaily(day = 1, contactId = 5, purpose = UsagePurpose.ASR, promptTokens = 10, completionTokens = 0, requests = 1),
            UsageDaily(day = 1, contactId = 6, purpose = UsagePurpose.TTS, promptTokens = 90, completionTokens = 20, requests = 1),
            UsageDaily(day = 1, contactId = 7, purpose = UsagePurpose.CHAT, promptTokens = 1, completionTokens = 1, requests = 1),
        )
        assertEquals(listOf(7L, 6L, 5L), UsageStats.byContact(voiceOnly).map { it.key })
    }
}
