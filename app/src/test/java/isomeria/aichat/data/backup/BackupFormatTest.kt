package isomeria.aichat.data.backup

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 密码加密 API Key、数据格式迁移、文件名校验 */
class BackupFormatTest {
    // 测试用较少的迭代次数，加快速度；格式和正式一致
    private val iterations = 1_000

    @Test
    fun passwordCipher_roundTrip() {
        val secret = PasswordCipher.encrypt("sk-abc123", "secret-pw".toCharArray(), iterations)
        assertEquals(SecretDto.SCHEME_PASSWORD, secret.scheme)
        assertEquals(iterations, secret.iterations)
        assertTrue("密文里不能出现明文", !secret.data.contains("sk-abc123"))
        assertEquals("sk-abc123", PasswordCipher.decrypt(secret, "secret-pw".toCharArray()))
    }

    @Test
    fun passwordCipher_freshSaltAndIvEachTime() {
        val a = PasswordCipher.encrypt("sk-abc123", "pw123456".toCharArray(), iterations)
        val b = PasswordCipher.encrypt("sk-abc123", "pw123456".toCharArray(), iterations)
        assertNotEquals(a.salt, b.salt)
        assertNotEquals(a.iv, b.iv)
        assertNotEquals(a.data, b.data)
    }

    @Test
    fun passwordCipher_wrongPasswordOrTampered_throwsWrongPassword() {
        val secret = PasswordCipher.encrypt("sk-abc123", "right-pw".toCharArray(), iterations)
        expectWrongPassword { PasswordCipher.decrypt(secret, "wrong-pw".toCharArray()) }
        val bytes = java.util.Base64.getDecoder().decode(secret.data)
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        val tampered = secret.copy(data = java.util.Base64.getEncoder().encodeToString(bytes))
        expectWrongPassword { PasswordCipher.decrypt(tampered, "right-pw".toCharArray()) }
    }

    @Test
    fun passwordCipher_defaultIterationsWrittenToFile() {
        val secret = PasswordCipher.encrypt("k", "pw123456".toCharArray())
        assertEquals(PasswordCipher.ITERATIONS, secret.iterations)
        assertEquals("k", PasswordCipher.decrypt(secret, "pw123456".toCharArray()))
    }

    private fun expectWrongPassword(block: () -> Unit) {
        try {
            block()
            fail("应该抛出 WrongPasswordException")
        } catch (_: WrongPasswordException) {
        }
    }

    // ---- 迁移 ----

    @Test
    fun migrations_v1ChainReachesCurrentVersion() {
        // 真实的步骤表：每个旧版本都有转换
        for (version in 1 until BackupFormat.VERSION) assertTrue(BackupMigrations.STEPS.containsKey(version))
    }

    @Test
    fun migrations_currentVersion_unchanged() {
        val data = buildJsonObject { put("contacts", JsonPrimitive(1)) }
        assertEquals(data, BackupMigrations().migrate(data, BackupFormat.VERSION))
    }

    @Test
    fun migrations_olderVersion_runsEachStepInOrder() {
        val steps = mapOf<Int, (JsonObject) -> JsonObject>(
            1 to { o -> JsonObject(o + ("trail" to JsonPrimitive(o["trail"]!!.jsonPrimitive.content + ">2"))) },
            2 to { o -> JsonObject(o + ("trail" to JsonPrimitive(o["trail"]!!.jsonPrimitive.content + ">3"))) },
        )
        val migrations = BackupMigrations(current = 3, steps = steps)
        val result = migrations.migrate(buildJsonObject { put("trail", "1") }, from = 1)
        assertEquals("1>2>3", result["trail"]!!.jsonPrimitive.content)
        assertEquals("2>3", migrations.migrate(buildJsonObject { put("trail", "2") }, from = 2)["trail"]!!.jsonPrimitive.content)
    }

    @Test
    fun v1ToV2_convertsContactsProvidersAndUsage() {
        val v1 = kotlinx.serialization.json.Json.parseToJsonElement(
            """
            {
              "contacts": [
                {"id": 1, "name": "小雨", "avatarColor": 0, "providerId": 1, "model": "m", "temperature": 1.0,
                 "contextCount": 30, "createdAt": 1, "updatedAt": 2},
                {"id": 2, "name": "阿哲", "avatarColor": 0, "providerId": 1, "model": "m", "temperature": 1.0,
                 "contextCount": 2, "createdAt": 1, "updatedAt": 2}
              ],
              "conversations": [{"contactId": 1, "pinned": true}],
              "messages": [
                {"id": 1, "contactId": 1, "role": "ASSISTANT", "type": "TEXT", "status": "SUCCESS",
                 "promptTokens": 10, "completionTokens": 2, "createdAt": 1793448000000},
                {"id": 2, "contactId": 1, "role": "ASSISTANT", "type": "TEXT", "status": "SUCCESS",
                 "promptTokens": 5, "completionTokens": 1, "createdAt": 1793448060000},
                {"id": 3, "contactId": 1, "role": "ASSISTANT", "type": "TEXT", "status": "SUCCESS", "createdAt": 1793448070000},
                {"id": 4, "contactId": 2, "role": "USER", "type": "TEXT", "status": "SUCCESS",
                 "promptTokens": 99, "createdAt": 1793448080000}
              ],
              "providers": [
                {"id": 1, "kind": "LLM", "provider": "deepseek", "baseUrl": "https://api.deepseek.com", "defaultModel": "m",
                 "models": [{"modelId": "m", "displayName": "M", "imageInput": "YES"}]}
              ]
            }
            """,
        ) as JsonObject
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        val migrated = BackupMigrations.v1ToV2(v1, zone)
        val data = BackupFormat.json.decodeFromJsonElement(BackupData.serializer(), migrated)

        assertEquals(listOf(6000, 1000), data.contacts.map { it.contextTokens })
        assertTrue(data.contacts.all { it.autoMemory })
        assertTrue(!migrated["contacts"].toString().contains("contextCount"))

        val provider = data.providers.single()
        assertEquals("DeepSeek", provider.name)
        assertEquals(isomeria.aichat.data.db.Capability.YES, provider.models.single().thinkingInput)

        val usage = data.usage.single()
        assertEquals(1L, usage.contactId)
        assertEquals(15L, usage.promptTokens)
        assertEquals(3L, usage.completionTokens)
        assertEquals(2, usage.requests)
        assertEquals(isomeria.aichat.core.UsageStats.epochDay(1793448000000, zone), usage.day)

        // 记忆起点留空，恢复后再定
        assertEquals(null, data.conversations.single().memoryCursor)
        assertTrue(data.memories.isEmpty())
    }

    @Test
    fun v1ToV2_otherProviderKeepsItsIdentifierAsName_andEmptyDataIsFine() {
        val v1 = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"providers": [{"id": 1, "kind": "LLM", "provider": "other", "baseUrl": "https://x.com", "defaultModel": "m",
                "models": [{"modelId": "m", "displayName": "M"}]}]}""",
        ) as JsonObject
        val data = BackupFormat.json.decodeFromJsonElement(BackupData.serializer(), BackupMigrations.v1ToV2(v1))
        assertEquals("other", data.providers.single().name)
        assertEquals(isomeria.aichat.data.db.Capability.UNKNOWN, data.providers.single().models.single().thinkingInput)

        val empty = BackupFormat.json.decodeFromJsonElement(BackupData.serializer(), BackupMigrations.v1ToV2(JsonObject(emptyMap())))
        assertTrue(empty.contacts.isEmpty() && empty.usage.isEmpty())
    }

    @Test
    fun migrations_newerVersion_rejected() {
        try {
            BackupMigrations().migrate(JsonObject(emptyMap()), BackupFormat.VERSION + 1)
            fail("应该拒绝更新版本的格式")
        } catch (e: BackupTooNewException) {
            assertEquals(BackupFormat.VERSION + 1, e.formatVersion)
        }
    }

    @Test
    fun migrations_missingStepOrBadVersion_invalid() {
        for ((from, migrations) in listOf(1 to BackupMigrations(current = 2, steps = emptyMap()), 0 to BackupMigrations())) {
            try {
                migrations.migrate(JsonObject(emptyMap()), from)
                fail("应该报格式无效")
            } catch (_: InvalidBackupException) {
            }
        }
    }

    @Test fun v2ToV3KeepsLegacyDefaultsAndV1Chains() {
        val raw = kotlinx.serialization.json.Json.parseToJsonElement("""{"contacts":[{"id":9,"name":"旧联系人","avatarColor":0,"providerId":8,"model":"m","thinking":"MAX","temperature":0.7,"createdAt":1,"updatedAt":2}],"providers":[{"id":8,"kind":"LLM","provider":"openai","baseUrl":"https://example.com/A/v1","defaultModel":"m","models":[{"modelId":"m","displayName":"m","manual":true}]}]}""") as JsonObject
        val data = BackupFormat.json.decodeFromJsonElement(BackupData.serializer(), BackupMigrations().migrate(raw, 2))
        assertEquals("LEGACY", data.providers.single().compatibility)
        assertEquals(null, data.providers.single().presetId)
        assertTrue(data.providers.single().models.single().legacy)
        assertTrue(data.providers.single().models.single().manual)
        val contact = data.contacts.single()
        assertEquals(isomeria.aichat.data.db.ThinkingLevel.MAX, contact.thinking)
        assertEquals(0.7f, contact.temperature)
        assertEquals(null, contact.memoryModel)
        assertEquals("off", contact.memoryThinkingOption)
        val chained = BackupMigrations().migrate(raw, 1)
        assertEquals("LEGACY", BackupFormat.json.decodeFromJsonElement(BackupData.serializer(), chained).providers.single().compatibility)
    }

    @Test
    fun mediaNames_onlySafeNamesAllowed() {
        val pattern = BackupFormat.mediaNamePattern
        assertTrue(pattern.matches("images/2f1c-4a.jpg"))
        assertTrue(pattern.matches("avatars/a_b.jpg"))
        for (bad in listOf("../images/x.jpg", "images/../x.jpg", "images/a/b.jpg", "/images/x.jpg", "other/x.jpg", "images/", "images/..", "images/.hidden")) {
            assertTrue(bad, !pattern.matches(bad))
        }
    }
}
