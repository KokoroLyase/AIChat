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
import isomeria.aichat.data.db.MessageDao
import isomeria.aichat.data.reply.ReplyScheduler
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
    private val files: FileStorage,
    private val pinyin: PinyinConverter,
    private val replyScheduler: ReplyScheduler,
) {
    fun observeContacts(): Flow<List<Contact>> = contactDao.observeAll()

    /** 通讯录：按拼音首字母分组 */
    fun observeSections(): Flow<List<ContactSection<Contact>>> = contactDao.observeAll()
        .map { list -> ContactGrouper.group(list, { it.name }, pinyin::toPinyin) }
        .flowOn(Dispatchers.Default)

    fun observeContact(id: Long): Flow<Contact?> = contactDao.observe(id)

    suspend fun get(id: Long): Contact? = contactDao.get(id)

    /** 新建联系人，同时建好它的会话 */
    suspend fun create(contact: Contact): Long = db.withTransaction {
        val now = System.currentTimeMillis()
        val id = contactDao.insert(contact.copy(id = 0, createdAt = now, updatedAt = now))
        conversationDao.upsert(Conversation(contactId = id))
        id
    }

    suspend fun update(contact: Contact) {
        val old = contactDao.get(contact.id)
        contactDao.update(contact.copy(updatedAt = System.currentTimeMillis()))
        if (old?.avatarPath != null && old.avatarPath != contact.avatarPath) files.delete(old.avatarPath)
    }

    /** 删除联系人，同时删除聊天记录、头像和聊天图片 */
    suspend fun delete(id: Long) {
        replyScheduler.cancel(id)
        val contact = contactDao.get(id) ?: return
        val images = messageDao.imagePaths(id)
        db.withTransaction {
            // 外键也会级联删除，这里显式删一遍，不依赖 PRAGMA foreign_keys
            messageDao.deleteByContact(id)
            conversationDao.reset(id)
            contactDao.delete(id)
        }
        files.delete(contact.avatarPath)
        images.forEach(files::delete)
    }
}
