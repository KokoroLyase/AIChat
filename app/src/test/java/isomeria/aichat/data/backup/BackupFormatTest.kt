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
