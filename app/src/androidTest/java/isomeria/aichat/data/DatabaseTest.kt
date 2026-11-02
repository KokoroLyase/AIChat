package isomeria.aichat.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import isomeria.aichat.data.crypto.KeystoreSecretCipher
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.Conversation
import isomeria.aichat.data.db.DeepSeekDefaults
import isomeria.aichat.data.db.MIGRATION_1_2
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.Role
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 需要在设备或模拟器上运行：./gradlew connectedDebugAndroidTest
 *
 * 以后每次改表都在这里加一条迁移测试：helper.createDatabase(旧版本) → runMigrationsAndValidate(新版本)。
 */
@RunWith(AndroidJUnit4::class)
class DatabaseTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test
    fun migrate1To2_keepsDataAndMatchesExportedSchema() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO provider_config (id, kind, provider, baseUrl, apiKeyEncrypted, defaultModel, enabled) " +
                    "VALUES (1, 'LLM', 'deepseek', 'https://api.deepseek.com', 'encrypted', 'deepseek-flash', 1)",
            )
            db.execSQL(
                "INSERT INTO contact (id, name, avatarPath, avatarColor, providerId, model, thinking, temperature, " +
                    "systemPrompt, contextCount, splitReplies, createdAt, updatedAt) " +
                    "VALUES (7, '小雨', NULL, 1, 1, 'deepseek-flash', 'OFF', 1.3, '', 20, 1, 1, 1)",
            )
        }
        // 真机的系统 SQLite 上执行迁移，并按导出的 v2 schema 校验
        helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2).use { db ->
            db.query("SELECT name, contextTokens, autoMemory FROM contact WHERE id = 7").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("小雨", cursor.getString(0))
                assertEquals(4000, cursor.getInt(1))
                assertEquals(1, cursor.getInt(2))
            }
            db.query("SELECT name, apiKeyEncrypted FROM provider_config WHERE id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("DeepSeek", cursor.getString(0))
                assertEquals("encrypted", cursor.getString(1))
            }
        }
    }

    @Test
    fun seedAndCascadeDelete() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(AppDatabase.seedCallback)
            .build()
        try {
            val provider = db.providerDao().llmList().first()
            assertEquals(DeepSeekDefaults.DEFAULT_MODEL, provider.defaultModel)
            assertEquals(DeepSeekDefaults.knownModels.size, db.providerDao().models(provider.id).size)

            val id = db.contactDao().insert(
                Contact(name = "小雨", avatarColor = 0, providerId = provider.id, model = "deepseek-flash", createdAt = 1, updatedAt = 1),
            )
            db.conversationDao().upsert(Conversation(contactId = id, lastMessageTime = 5, lastMessagePreview = "hi"))
            db.messageDao().insert(
                Message(contactId = id, role = Role.USER, type = MessageType.TEXT, content = "hi", status = MessageStatus.SUCCESS, createdAt = 5),
            )
            assertEquals(1, db.conversationDao().observeList().first().size)

            db.contactDao().delete(id)
            assertNull(db.conversationDao().get(id))
            assertEquals(0, db.messageDao().listByContact(id).size)
        } finally {
            db.close()
        }
    }

    @Test
    fun keystoreCipher_roundTrip() {
        val cipher = KeystoreSecretCipher()
        val secret = "sk-test-1234567890"
        val encrypted = cipher.encrypt(secret)
        assertNotEquals(secret, encrypted)
        assertEquals(secret, cipher.decrypt(encrypted))
        assertNull(cipher.decrypt("not-valid-base64!!"))
    }

    private companion object {
        const val TEST_DB = "migration-test"
    }
}
