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
import isomeria.aichat.data.db.MIGRATION_4_5
import isomeria.aichat.data.db.MIGRATION_5_6
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
    fun migrate4To5_contactsDefaultToNewSwitches_splitRepliesKept_globalTablesCascade() {
        helper.createDatabase("migration-v4-test", 4).use { db ->
            db.execSQL("INSERT INTO provider_config(id,kind,provider,name,baseUrl,apiKeyEncrypted,defaultModel,enabled,presetId,compatibility) VALUES(1,'LLM','openai','DeepSeek','https://api.deepseek.com',NULL,'',1,'deepseek','DEEPSEEK')")
            db.execSQL("INSERT INTO contact(id,name,avatarColor,providerId,model,thinking,temperature,systemPrompt,contextTokens,splitReplies,autoMemory,createdAt,updatedAt,memoryThinkingOption) VALUES(7,'分条',0,1,'m','OFF',1.3,'人设',4000,1,1,1,1,'off'),(8,'不分条',0,1,'m','OFF',0.5,'',6000,0,0,2,2,'off')")
            db.execSQL("INSERT INTO conversation(contactId,pinned,unreadCount,markedUnread,draft,lastMessageTime,lastMessagePreview,memoryCursor,memoryStart,muted,backgroundPath) VALUES(7,1,3,0,'草稿',9,'预览',5,2,1,'/bg/7.jpg')")
        }
        helper.runMigrationsAndValidate("migration-v4-test", 5, true, MIGRATION_4_5).use { db ->
            db.query("SELECT id, useGlobalMemory, useReplyPreferences, splitReplies, systemPrompt, temperature FROM contact ORDER BY id").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(listOf(7L, 1L, 1L, 1L), listOf(c.getLong(0), c.getLong(1), c.getLong(2), c.getLong(3)))
                assertEquals("人设", c.getString(4))
                assertTrue(c.moveToNext())
                assertEquals(listOf(8L, 1L, 1L, 0L), listOf(c.getLong(0), c.getLong(1), c.getLong(2), c.getLong(3)))
                assertEquals(0.5, c.getDouble(5), 0.0001)
            }
            db.query("SELECT muted, backgroundPath, draft FROM conversation WHERE contactId=7").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1, c.getInt(0))
                assertEquals("/bg/7.jpg", c.getString(1))
                assertEquals("草稿", c.getString(2))
            }
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL("INSERT INTO global_memory_category(id,name,sortOrder,createdAt,updatedAt) VALUES(1,'个人信息',0,1,1)")
            db.execSQL("INSERT INTO global_memory_topic(id,categoryId,name,sortOrder,createdAt,updatedAt) VALUES(1,1,'基本情况',0,1,1)")
            db.execSQL("INSERT INTO global_memory_item(id,topicId,content,enabled,sortOrder,createdAt,updatedAt) VALUES(1,1,'住在杭州',1,0,1,1)")
            db.execSQL("DELETE FROM global_memory_category WHERE id=1")
            db.query("SELECT (SELECT COUNT(*) FROM global_memory_topic) + (SELECT COUNT(*) FROM global_memory_item)").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0))
            }
        }
    }

    @Test
    fun migrate5To6_proactiveOffForExistingContacts_messagesNormal_newTablesWork() {
        helper.createDatabase("migration-v5-test", 5).use { db ->
            db.execSQL("INSERT INTO provider_config(id,kind,provider,name,baseUrl,apiKeyEncrypted,defaultModel,enabled,presetId,compatibility) VALUES(1,'LLM','openai','DeepSeek','https://api.deepseek.com',NULL,'',1,'deepseek','DEEPSEEK')")
            db.execSQL("INSERT INTO contact(id,name,avatarColor,providerId,model,thinking,temperature,systemPrompt,contextTokens,splitReplies,autoMemory,createdAt,updatedAt,memoryThinkingOption,useGlobalMemory,useReplyPreferences) VALUES(7,'甲',0,1,'m','OFF',1.3,'人设',4000,1,1,1,1,'off',0,1),(8,'乙',0,1,'m','OFF',0.5,'',6000,0,0,2,2,'off',1,0)")
            db.execSQL("INSERT INTO conversation(contactId,pinned,unreadCount,markedUnread,draft,lastMessageTime,lastMessagePreview,memoryCursor,memoryStart,muted,backgroundPath) VALUES(7,1,3,0,'草稿',9,'预览',5,2,1,'/bg/7.jpg')")
            db.execSQL("INSERT INTO message(id,contactId,role,type,content,imageWidth,imageHeight,reasoningMillis,status,errorCode,promptTokens,completionTokens,replyGroup,createdAt) VALUES(1,7,'USER','TEXT','在吗',0,0,0,'SUCCESS',0,0,0,NULL,8),(2,7,'ASSISTANT','TEXT','在',0,0,0,'PENDING',0,7,3,'g',9)")
        }
        helper.runMigrationsAndValidate("migration-v5-test", 6, true, MIGRATION_5_6).use { db ->
            db.query("SELECT id, proactiveEnabled, proactiveFrequency, useGlobalMemory, useReplyPreferences, systemPrompt FROM contact ORDER BY id").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(listOf(7L, 0L, 0L, 1L), listOf(c.getLong(0), c.getLong(1), c.getLong(3), c.getLong(4)))
                assertEquals("MEDIUM", c.getString(2))
                assertEquals("人设", c.getString(5))
                assertTrue(c.moveToNext())
                assertEquals(listOf(8L, 0L, 1L, 0L), listOf(c.getLong(0), c.getLong(1), c.getLong(3), c.getLong(4)))
                assertEquals("MEDIUM", c.getString(2))
            }
            db.query("SELECT origin, status FROM message ORDER BY id").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("NORMAL", c.getString(0))
                assertTrue(c.moveToNext())
                assertEquals("NORMAL", c.getString(0))
                assertEquals("PENDING", c.getString(1))
            }
            db.query("SELECT proactiveUnanswered, lastProactiveAt, muted, backgroundPath, draft FROM conversation WHERE contactId=7").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0))
                assertEquals(0L, c.getLong(1))
                assertEquals(1, c.getInt(2))
                assertEquals("/bg/7.jpg", c.getString(3))
                assertEquals("草稿", c.getString(4))
            }
            db.query("SELECT (SELECT COUNT(*) FROM proactive_state) + (SELECT COUNT(*) FROM proactive_reminder)").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0))
            }
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL("INSERT INTO proactive_state(id,day,used,lastRunAt) VALUES(1,20000,3,5)")
            db.execSQL("INSERT INTO proactive_reminder(contactId,dueAt,note,createdAt) VALUES(8,500,'叫他起床',1)")
            db.execSQL("DELETE FROM contact WHERE id=8")
            db.query("SELECT (SELECT COUNT(*) FROM proactive_reminder), (SELECT used FROM proactive_state WHERE id=1)").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0))
                assertEquals(3, c.getInt(1))
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
