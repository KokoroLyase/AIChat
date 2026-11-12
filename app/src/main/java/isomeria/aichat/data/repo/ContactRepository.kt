package isomeria.aichat.data.repo

import androidx.room.withTransaction
import isomeria.aichat.core.ContactGrouper
import isomeria.aichat.core.ContactSection
import isomeria.aichat.core.PinyinConverter
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.ContactDao
import isomeria.aichat.data.db.Conversation
import isomeria.aichat.data.db.ConversationDao
import isomeria.aichat.data.db.MemoryDao
import isomeria.aichat.data.db.MessageDao
import isomeria.aichat.data.db.UsageDao
import isomeria.aichat.data.reply.ProactiveControl
import isomeria.aichat.data.reply.ReplyControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContactRepository @Inject constructor(
    private val db: AppDatabase,
    private val contactDao: ContactDao,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val memoryDao: MemoryDao,
    private val usageDao: UsageDao,
    private val files: FileStorage,
    private val pinyin: PinyinConverter,
    private val replyScheduler: ReplyControl,
    private val proactive: ProactiveControl = ProactiveControl.NONE,
    /** 0.8.0：删掉正在通话的联系人前先挂断 */
    private val calls: isomeria.aichat.data.call.CallControl = isomeria.aichat.data.call.CallControl.NONE,
) {
    fun observeContacts(): Flow<List<Contact>> = contactDao.observeAll()

    /** 通讯录：按拼音首字母分组 */
    fun observeSections(): Flow<List<ContactSection<Contact>>> = contactDao.observeAll()
        .map { list -> ContactGrouper.group(list, { it.name }, pinyin::toPinyin) }
        .flowOn(Dispatchers.Default)

    fun observeContact(id: Long): Flow<Contact?> = contactDao.observe(id)

    suspend fun get(id: Long): Contact? = contactDao.get(id)

    /** 新建联系人，同时建好它的会话 */
    suspend fun create(contact: Contact): Long {
        val id = db.withTransaction {
            val now = System.currentTimeMillis()
            val id = contactDao.insert(contact.copy(id = 0, createdAt = now, updatedAt = now))
            conversationDao.upsert(Conversation(contactId = id))
            id
        }
        // 主动聊天的开关可能变了：有人开启就注册周期检查，全部关闭就取消
        proactive.sync()
        return id
    }

    suspend fun update(contact: Contact) {
        val old = contactDao.get(contact.id)
        db.withTransaction {
            contactDao.update(contact.copy(updatedAt = System.currentTimeMillis()))
            // 重新打开自动记忆：关闭期间的聊天不回溯，起点改到下次检查时的上下文窗口开头
            if (old != null && !old.autoMemory && contact.autoMemory) {
                val start = conversationDao.get(contact.id)?.memoryStart ?: 0L
                conversationDao.setMemoryRange(contact.id, null, start)
            }
            // 关掉主动聊天：约好的提醒一起去掉，之后不会再为它调用模型
            if (!contact.proactiveEnabled) db.proactiveDao().deleteRemindersOf(contact.id)
        }
        if (old?.avatarPath != null && old.avatarPath != contact.avatarPath) files.delete(old.avatarPath)
        proactive.sync()
    }

    /** 删除联系人，同时删除聊天记录、记忆、头像、聊天图片、语音和聊天背景；用量并到「已删除的联系人」，总量不变 */
    suspend fun delete(id: Long) {
        calls.hangUpFor(id)
        replyScheduler.cancel(id)
        replyScheduler.cancelDelivery(id)
        val contact = contactDao.get(id) ?: return
        val images = messageDao.imagePaths(id) + messageDao.voicePaths(id)
        val background = conversationDao.get(id)?.backgroundPath
        db.withTransaction {
            // 外键也会级联删除，这里显式删一遍，不依赖 PRAGMA foreign_keys
            messageDao.deleteByContact(id)
            db.callDao().deleteOrphans()
            memoryDao.deleteByContact(id)
            db.proactiveDao().deleteRemindersOf(id)
            conversationDao.reset(id)
            usageDao.mergeIntoDeleted(id)
            usageDao.deleteByContact(id)
            contactDao.delete(id)
        }
        files.delete(contact.avatarPath)
        files.delete(background)
        images.forEach(files::delete)
        proactive.sync()
    }
}
