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
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.Role
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
    fun version1_schemaMatchesExportedFile() {
        helper.createDatabase(TEST_DB, 1).close()
        // 用导出的 v1 schema 校验当前实体定义
        helper.runMigrationsAndValidate(TEST_DB, 1, true).close()
    }

    @Test
    fun seedAndCascadeDelete() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(AppDatabase.seedCallback)
            .build()
        try {
            val provider = db.providerDao().getLlm()!!
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
