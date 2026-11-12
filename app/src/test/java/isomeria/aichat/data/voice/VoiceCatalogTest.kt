package isomeria.aichat.data.voice

import isomeria.aichat.data.db.ProviderKind
import isomeria.aichat.data.llm.LlmError
import isomeria.aichat.data.llm.LlmException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * 0.7.2 的预置服务商和音色列表：顺序、默认值、内置列表；MiniMax、硅基流动、Fish Audio 的音色接口（只用本机模拟接口）。
 */
class VoiceCatalogTest {
    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val tts = FakeSystemTts()
    private val catalog = VoiceCatalog(OkHttpClient(), json, tts)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun base() = server.url("/v1").toString().trimEnd('/')

    @Test
    fun presetOrder_isWhatTheUserAskedFor() {
        assertEquals(listOf("volcengine", "qwen", "mimo", "minimax", "fish", "siliconflow"), VoicePresets.forKind(ProviderKind.TTS).map { it.id })
        assertEquals(listOf("volcengine", "qwen", "mimo", "siliconflow"), VoicePresets.forKind(ProviderKind.ASR).map { it.id })
        assertTrue(VoicePresets.forKind(ProviderKind.ASR).all { it.supports(ProviderKind.ASR) })
    }

    @Test
    fun presetDefaults_areInTheirOwnBuiltInLists_andProtocolsAreKnown() {
        for (preset in VoicePresets.all) {
            for (kind in listOf(ProviderKind.TTS, ProviderKind.ASR)) {
                val endpoint = preset.endpoint(kind) ?: continue
                assertTrue(preset.id, endpoint.protocol in VoiceProtocol.all)
                assertTrue(preset.id, endpoint.model.isNotBlank())
                assertTrue(preset.id, endpoint.docs.startsWith("https://"))
            }
            val tts = preset.tts ?: continue
            val list = BuiltInVoices.forTarget(VoiceTarget(preset.id, tts.protocol, tts.baseUrl, null, tts.model)) ?: continue
            if (tts.defaultVoice.isNotEmpty()) assertTrue("${preset.id} 的默认音色要在列表里", list.any { it.id == tts.defaultVoice })
        }
        // 百炼的地址要用户换成自己的业务空间
        assertTrue(VoicePresets.hasPlaceholder(VoicePresets.QWEN.tts!!.baseUrl))
        assertFalse(VoicePresets.hasPlaceholder(VoicePresets.VOLCENGINE.tts!!.baseUrl))
    }

    @Test
    fun builtInLists_haveUniqueIds_andCoverEachPreset() {
        for (list in listOf(BuiltInVoices.MIMO, BuiltInVoices.VOLCENGINE, BuiltInVoices.QWEN, BuiltInVoices.siliconFlow("m"))) {
            assertEquals(list.size, list.map { it.id }.toSet().size)
            assertTrue(list.all { it.name.isNotBlank() && it.group.isNotBlank() })
        }
        assertTrue(BuiltInVoices.VOLCENGINE.all { it.id.endsWith("_uranus_bigtts") })
        assertTrue(BuiltInVoices.QWEN.all { it.id.endsWith("_v3.1") })
        assertEquals("FunAudioLLM/CosyVoice2-0.5B:anna", BuiltInVoices.siliconFlow("FunAudioLLM/CosyVoice2-0.5B").first().id)
        assertNull("自定义服务商没有内置列表", BuiltInVoices.forTarget(VoiceTarget(null, VoiceProtocol.CHAT_AUDIO, "https://x", null, "m")))
        assertFalse(VoiceCatalog.supports(null, VoiceProtocol.OPENAI_AUDIO))
        assertTrue(VoiceCatalog.supports(null, "", systemTts = true))
    }

    @Test
    fun builtInList_needsNoKeyAndNoRequest() = runBlocking {
        val list = catalog.list(VoiceTarget("volcengine", VoiceProtocol.VOLC_TTS, base(), null, "seed-tts-2.0"))
        assertEquals(BuiltInVoices.VOLCENGINE, list)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun systemTts_listsEngineVoices() = runBlocking {
        val list = catalog.list(VoiceTarget(null, "", "", null, "", systemTts = true))
        assertEquals(listOf("zh-voice-a", "zh-voice-b"), list.map { it.id })
    }

    @Test
    fun miniMax_getVoice_mineFirstThenSystem() = runBlocking {
        server.enqueue(
            MockResponse.Builder().body(
                """{"system_voice":[{"voice_id":"male-qn-qingse","voice_name":"青涩青年音色","description":["清亮"],"created_time":"1970-01-01"}],
                   "voice_cloning":[{"voice_id":"my-clone","description":["我复刻的"],"created_time":"2026-11-01"}],
                   "voice_generation":[],"base_resp":{"status_code":0,"status_msg":"success"}}""",
            ).build(),
        )
        val list = catalog.list(VoiceTarget("minimax", VoiceProtocol.MINIMAX_TTS, base(), "sk-test", "speech-2.8-hd"))
        assertEquals(listOf("my-clone", "male-qn-qingse"), list.map { it.id })
        assertEquals("青涩青年音色", list[1].name)
        assertEquals(VoiceCatalog.GROUP_MINE, list[0].group)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/get_voice", recorded.url.encodedPath)
        assertEquals("Bearer sk-test", recorded.headers["Authorization"])
        assertTrue(recorded.body!!.utf8().contains("\"voice_type\":\"all\""))
    }

    @Test
    fun miniMax_withoutKey_needsKey_andAuthErrorIsMapped() = runBlocking {
        try {
            catalog.list(VoiceTarget("minimax", VoiceProtocol.MINIMAX_TTS, base(), " ", "m"))
            fail("没有 Key 不能取")
        } catch (_: VoiceCatalogNeedsKeyException) {
        }
        assertEquals(0, server.requestCount)
        server.enqueue(MockResponse.Builder().body("""{"base_resp":{"status_code":1004,"status_msg":"auth"}}""").build())
        try {
            catalog.list(VoiceTarget("minimax", VoiceProtocol.MINIMAX_TTS, base(), "sk", "m"))
            fail("Key 不对")
        } catch (e: LlmException) {
            assertEquals(LlmError.AUTH, e.error)
        }
    }

    @Test
    fun siliconFlow_customVoicesBeforePresets_andWithoutKeyOnlyPresets() = runBlocking {
        server.enqueue(MockResponse.Builder().body("""{"results":[{"model":"FunAudioLLM/CosyVoice2-0.5B","customName":"我的声音","text":"…","uri":"speech:mine:abc:def"}]}""").build())
        val target = VoiceTarget("siliconflow", VoiceProtocol.OPENAI_AUDIO, base(), "sk-test", "FunAudioLLM/CosyVoice2-0.5B")
        val list = catalog.list(target)
        assertEquals("speech:mine:abc:def", list.first().id)
        assertEquals("我的声音", list.first().name)
        assertEquals(9, list.size)
        assertEquals("/v1/audio/voice/list", server.takeRequest().url.encodedPath)
        assertEquals(8, catalog.list(target.copy(apiKey = null)).size)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun fish_listIsMineAndPopular_searchSendsTitle() = runBlocking {
        server.enqueue(MockResponse.Builder().body("""{"total":1,"items":[{"_id":"mine1","title":"我的音色一","languages":["zh"],"author":{"nickname":"我"}}]}""").build())
        server.enqueue(MockResponse.Builder().body("""{"total":1,"items":[{"_id":"pub1","title":"热门","languages":["zh","en"],"author":{"nickname":"某人"}}]}""").build())
        val target = VoiceTarget("fish", VoiceProtocol.FISH_TTS, base(), "sk-test", "s2.1-pro")
        assertTrue(catalog.searchesRemotely(target))
        val list = catalog.list(target)
        assertEquals(listOf("mine1", "pub1"), list.map { it.id })
        assertEquals(listOf(VoiceCatalog.GROUP_MINE, VoiceCatalog.GROUP_PUBLIC), list.map { it.group })
        assertEquals("zh、en · 某人", list[1].detail)
        val mine = server.takeRequest()
        assertEquals("/v1/model", mine.url.encodedPath)
        assertEquals("true", mine.url.queryParameter("self"))
        assertNull(server.takeRequest().url.queryParameter("self"))

        server.enqueue(MockResponse.Builder().body("""{"total":0,"items":[]}""").build())
        assertTrue(catalog.search(target, " 温柔 ").isEmpty())
        val search = server.takeRequest()
        assertEquals("温柔", search.url.queryParameter("title"))
        assertEquals("Bearer sk-test", search.headers["Authorization"])
    }

    @Test
    fun redirect_isNotFollowed() = runBlocking {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", server.url("/elsewhere").toString()).build())
        try {
            catalog.list(VoiceTarget("minimax", VoiceProtocol.MINIMAX_TTS, base(), "sk", "m"))
            fail("重定向不跟")
        } catch (_: LlmException) {
        }
        assertEquals(1, server.requestCount)
    }
}
