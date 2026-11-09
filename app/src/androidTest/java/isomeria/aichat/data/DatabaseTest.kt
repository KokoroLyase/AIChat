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
import isomeria.aichat.data.db.MIGRATION_2_3
import isomeria.aichat.data.db.MIGRATION_3_4
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
        helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_1_2, MIGRATION_2_3).use { db ->
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
    fun migrate2To3_keepsLegacyAndIndependentMemoryDefaults() {
        helper.createDatabase("migration-v2-test", 2).use { db ->
            db.execSQL("INSERT INTO provider_config(id,kind,provider,name,baseUrl,apiKeyEncrypted,defaultModel,enabled) VALUES(5,'LLM','openai','原连接','https://example.com/A/v1','encrypted','m',1)")
            db.execSQL("INSERT INTO provider_model(providerId,modelId,displayName,imageInput,imageInputUserMark,sortOrder,thinkingInput,thinkingUserMark,manual) VALUES(5,'m','模型','UNKNOWN',1,0,'UNKNOWN',1,1)")
            db.execSQL("INSERT INTO contact(id,name,avatarColor,providerId,model,thinking,temperature,systemPrompt,contextTokens,splitReplies,autoMemory,createdAt,updatedAt) VALUES(7,'旧联系人',0,5,'m','MAX',0.7,'',4000,1,1,1,1)")
        }
        helper.runMigrationsAndValidate("migration-v2-test", 3, true, MIGRATION_2_3).use { db ->
            db.query("SELECT compatibility, presetId, baseUrl, apiKeyEncrypted FROM provider_config WHERE id=5").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("LEGACY", c.getString(0))
                assertTrue(c.isNull(1))
                assertEquals("https://example.com/A/v1", c.getString(2))
                assertEquals("encrypted", c.getString(3))
            }
            db.query("SELECT thinking, temperature, memoryModel, memoryThinkingOption FROM contact WHERE id=7").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("MAX", c.getString(0))
                assertEquals(0.7, c.getDouble(1), 0.001)
                assertTrue(c.isNull(2))
                assertEquals("off", c.getString(3))
            }
        }
    }

    @Test
    fun migrate3To4_conversationDefaultsNotMutedAndNoBackground() {
        helper.createDatabase("migration-v3-test", 3).use { db ->
            db.execSQL("INSERT INTO provider_config(id,kind,provider,name,baseUrl,apiKeyEncrypted,defaultModel,enabled,presetId,compatibility) VALUES(1,'LLM','openai','DeepSeek','https://api.deepseek.com',NULL,'',1,'deepseek','DEEPSEEK')")
            db.execSQL("INSERT INTO contact(id,name,avatarColor,providerId,model,thinking,temperature,systemPrompt,contextTokens,splitReplies,autoMemory,createdAt,updatedAt,memoryThinkingOption) VALUES(7,'旧联系人',0,1,'m','OFF',1.3,'',4000,1,1,1,1,'off')")
            db.execSQL("INSERT INTO conversation(contactId,pinned,unreadCount,markedUnread,draft,lastMessageTime,lastMessagePreview,memoryCursor,memoryStart) VALUES(7,1,3,0,'草稿',9,'预览',5,2)")
        }
        helper.runMigrationsAndValidate("migration-v3-test", 4, true, MIGRATION_3_4).use { db ->
            db.query("SELECT muted, backgroundPath, pinned, unreadCount, draft, lastMessagePreview, memoryCursor FROM conversation WHERE contactId=7").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0))
                assertTrue(c.isNull(1))
                assertEquals(1, c.getInt(2))
                assertEquals(3, c.getInt(3))
                assertEquals("草稿", c.getString(4))
                assertEquals("预览", c.getString(5))
                assertEquals(5, c.getLong(6))
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
            assertEquals("", provider.defaultModel)
            assertTrue(db.providerDao().models(provider.id).isEmpty())

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
