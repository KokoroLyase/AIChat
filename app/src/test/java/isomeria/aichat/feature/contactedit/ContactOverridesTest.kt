package isomeria.aichat.feature.contactedit

import isomeria.aichat.core.ReplyPreferences
import isomeria.aichat.core.ReplyPreferencesSource
import isomeria.aichat.core.forContact
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.DbConverters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 0.7.2 联系人单独设置：回复偏好的取值、三种方式之间切换、换语音合成来源时清空音色、回复偏好存成 JSON。 */
class ContactOverridesTest {
    private val global = ReplyPreferences(waitSeconds = 5, targetLength = 40)
    private val source = ReplyPreferencesSource { global }
    private val contact = Contact(name = "甲", avatarColor = 0, providerId = 1, model = "m", createdAt = 1, updatedAt = 1)

    @Test
    fun forContact_offFollowCustom() {
        assertEquals("跟随全局", global, source.forContact(contact))
        val own = ReplyPreferences(waitSeconds = 0, maxCount = 5)
        assertEquals("单独设置", own, source.forContact(contact.copy(replyPreferences = own)))
        assertNull("不使用", source.forContact(contact.copy(useReplyPreferences = false, replyPreferences = own)))
        // 存的值越界时按范围收回
        val broken = ReplyPreferences(waitSeconds = 999, minCount = 4, maxCount = 2)
        assertEquals(broken.normalized(), source.forContact(contact.copy(replyPreferences = broken)))
    }

    @Test
    fun replyMode_switching_copiesGlobalOnce_keepsCustomWhenOff() {
        val form = ContactForm()
        assertEquals(ReplyPreferencesMode.FOLLOW, form.replyMode)
        val custom = form.withReplyMode(ReplyPreferencesMode.CUSTOM, global)
        assertEquals(ReplyPreferencesMode.CUSTOM, custom.replyMode)
        assertEquals("第一次单独设置时复制全局的", global, custom.replyPreferences)

        val edited = custom.copy(replyPreferences = custom.replyPreferences!!.copy(waitSeconds = 9))
        val off = edited.withReplyMode(ReplyPreferencesMode.OFF, global)
        assertEquals(ReplyPreferencesMode.OFF, off.replyMode)
        assertEquals("不使用时保留单独设置的值", 9, off.replyPreferences!!.waitSeconds)
        assertEquals("再选单独设置还在", 9, off.withReplyMode(ReplyPreferencesMode.CUSTOM, global).replyPreferences!!.waitSeconds)

        val follow = edited.withReplyMode(ReplyPreferencesMode.FOLLOW, global)
        assertEquals(ReplyPreferencesMode.FOLLOW, follow.replyMode)
        assertNull("跟随全局时丢掉单独设置的值", follow.replyPreferences)
    }

    @Test
    fun ttsSource_clearsVoiceOnlyWhenTheEffectiveSourceChanges() {
        val form = ContactForm(ttsVoice = "苏打")
        val global = "provider:3"
        assertEquals("单独选了和全局一样的，音色还能用", "苏打", form.withTtsSource("provider:3", global).ttsVoice)
        val other = form.withTtsSource("provider:4", global)
        assertEquals("provider:4", other.ttsSource)
        assertEquals("换了服务商，音色清空", "", other.ttsVoice)
        assertEquals("", form.withTtsSource("local:system-tts", global).ttsVoice)
        val back = other.copy(ttsVoice = "4 的音色").withTtsSource(null, global)
        assertNull(back.ttsSource)
        assertEquals("从单独选的换回跟随全局，实际来源也变了", "", back.ttsVoice)
    }

    @Test
    fun save_dropsSourcesWhoseProviderWasDeleted_keepsLocalAndExisting() {
        fun provider(id: Long, kind: isomeria.aichat.data.db.ProviderKind) =
            isomeria.aichat.data.db.ProviderConfig(id = id, kind = kind, provider = "p", baseUrl = "https://a.test", defaultModel = "m")
        val choices = ContactVoiceChoices(tts = listOf(provider(3, isomeria.aichat.data.db.ProviderKind.TTS)), asr = listOf(provider(4, isomeria.aichat.data.db.ProviderKind.ASR)))
        val kept = ContactForm(ttsSource = "provider:3", asrSource = "local:offline-asr").withoutMissingSources(choices)
        assertEquals("provider:3", kept.ttsSource)
        assertEquals("local:offline-asr", kept.asrSource)
        val gone = ContactForm(ttsSource = "provider:9", asrSource = "provider:3").withoutMissingSources(choices)
        assertNull("服务商不在了：跟随全局", gone.ttsSource)
        assertNull("id 是合成服务商的，不能当识别来源", gone.asrSource)
        assertEquals(ContactForm(), ContactForm().withoutMissingSources(choices))
    }

    @Test
    fun replyPreferencesColumn_roundTrips_badJsonFollowsGlobal() {
        val converters = DbConverters()
        val own = ReplyPreferences(waitSeconds = 3, stripPunctuation = false, maxIntervalSeconds = 9)
        assertEquals(own, converters.replyPreferencesFromJson(converters.replyPreferencesToJson(own)))
        assertNull(converters.replyPreferencesToJson(null))
        assertNull(converters.replyPreferencesFromJson(null))
        assertNull("读不出时当作跟随全局", converters.replyPreferencesFromJson("{not json"))
        assertEquals("缺的字段用默认值", ReplyPreferences(waitSeconds = 2), converters.replyPreferencesFromJson("""{"waitSeconds":2}"""))
    }
}
