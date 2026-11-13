package isomeria.aichat.data.repo

import android.content.Context
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.CallTurn
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.Favorite
import isomeria.aichat.data.db.FavoriteTurn
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** 列表和详情页的一条收藏：[source] 是还在的来源联系人（id 和创建时间都对得上），不在时为 null，[turns] 是通话的每一句 */
/** 收藏了几条；[imagesSkipped] 是选中的里面有图片（图片不能收藏） */
data class FavoriteResult(val added: Int, val imagesSkipped: Boolean)

data class FavoriteEntry(val favorite: Favorite, val source: Contact?, val turns: List<FavoriteTurn>)

/**
 * 收藏（0.9.0）。每条收藏是收藏时的快照：文字、说话方、来源联系人的名字和头像颜色、原消息时间；
 * 语音和通话的音频复制一份到 files/favorites，删除原消息、清空聊天、删除联系人都不影响收藏，删除收藏时删它自己的文件。
 *
 * 来源联系人按 id 和创建时间一起认（id 可能被别的联系人用上）；对不上时用存下的名字和首字头像。
 */
@Singleton
class FavoriteRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val files: FileStorage,
) {
    private val dao get() = db.favoriteDao()

    /** 所有收藏（按收藏时间倒序），带上还在的来源联系人和通话的每一句；联系人改名、换头像、被删时跟着变 */
    fun observeEntries(): Flow<List<FavoriteEntry>> =
        combine(dao.observeAll(), dao.observeAllTurns(), db.contactDao().observeAll()) { favorites, turns, contacts ->
            val byTurn = turns.groupBy { it.favoriteId }
            val byContact = contacts.associateBy { it.id }
            favorites.map { favorite ->
                FavoriteEntry(favorite, byContact[favorite.contactId]?.takeIf { it.createdAt == favorite.contactCreatedAt }, byTurn[favorite.id].orEmpty())
            }
        }

    /**
     * 收藏这几条消息（长按一条或多选）：每条单独存一份。图片、等待回复中、待投递、正在进行的通话跳过；
     * 已经收藏过的（同一条原消息）不再存
     */
    suspend fun add(messageIds: Collection<Long>, now: Long = System.currentTimeMillis()): FavoriteResult =
        StorageOperations.gate.activity { withContext(Dispatchers.IO) { addProtected(messageIds, now) } }

    private suspend fun addProtected(messageIds: Collection<Long>, now: Long): FavoriteResult {
        val found = messageIds.distinct().chunked(500).flatMap { db.messageDao().getByIds(it) }
        val images = found.any { it.type == MessageType.IMAGE }
        val messages = found.filter { canFavorite(it) }.sortedWith(compareBy({ it.createdAt }, { it.id }))
        if (messages.isEmpty()) return FavoriteResult(0, images)
        val existing = dao.listAll().mapNotNullTo(HashSet()) { favorite -> favorite.messageId?.let { it to favorite.messageCreatedAt } }
        val contacts = HashMap<Long, Contact?>()
        var added = 0
        for ((index, message) in messages.withIndex()) {
            if (!existing.add(message.id to message.createdAt)) continue
            val contact = contacts.getOrPut(message.contactId) { db.contactDao().get(message.contactId) } ?: continue
            if (save(message, contact, now + index)) added++
        }
        return FavoriteResult(added, images)
    }

    /** 一条消息存成一条收藏；音频先复制（固定着），写库后解除，失败时删掉复制出来的 */
    private suspend fun save(message: Message, contact: Contact, at: Long): Boolean {
        val copied = ArrayList<String>()
        fun copy(path: String?): String? = files.copyToFavorites(path)?.also(copied::add)
        try {
            val turns = if (message.type == MessageType.CALL) db.callDao().turns(message.id) else emptyList()
            val audio = if (message.type == MessageType.VOICE) copy(message.voicePath) else null
            val turnAudio = turns.map { copy(it.audioPath) }
            db.withTransaction {
                val id = dao.insert(
                    Favorite(
                        type = message.type,
                        role = message.role,
                        contactId = contact.id,
                        contactCreatedAt = contact.createdAt,
                        contactName = contact.name,
                        contactAvatarColor = contact.avatarColor,
                        content = if (message.type == MessageType.CALL) "" else message.content,
                        audioPath = audio,
                        // 语音的文件不在了（例如恢复的备份里缺了）：只留转写文字
                        audioMillis = when (message.type) {
                            MessageType.VOICE -> if (audio == null) 0 else message.voiceMillis
                            MessageType.CALL -> message.voiceMillis
                            else -> 0
                        },
                        messageId = message.id,
                        messageCreatedAt = message.createdAt,
                        createdAt = at,
                    ),
                )
                dao.insertTurns(
                    turns.mapIndexed { index, turn ->
                        val path = turnAudio[index]
                        FavoriteTurn(
                            favoriteId = id, seq = turn.seq, role = turn.role, text = turn.text,
                            audioPath = path, audioMillis = if (path == null) 0 else turn.audioMillis,
                            status = turn.status, createdAt = turn.createdAt,
                        )
                    },
                )
            }
        } catch (e: Throwable) {
            copied.forEach(files::delete)
            throw e
        }
        copied.forEach(files::commitAvatar)
        return true
    }

    /** 删除收藏和它复制出来的音频 */
    suspend fun delete(id: Long) {
        val audio = dao.audioPathsOf(id)
        db.withTransaction {
            dao.deleteTurns(id)
            dao.delete(id)
        }
        audio.forEach(files::delete)
    }

    /** 复制（0.9.0）：和聊天里复制同样的写法；通话复制完整内容 */
    fun copyText(entry: FavoriteEntry): String {
        val favorite = entry.favorite
        val message = Message(
            id = favorite.id, contactId = favorite.contactId, role = favorite.role, type = favorite.type, content = favorite.content,
            status = isomeria.aichat.data.db.MessageStatus.SUCCESS, voiceMillis = favorite.audioMillis, createdAt = favorite.messageCreatedAt,
        )
        val turns = entry.turns.map {
            CallTurn(id = it.id, messageId = favorite.id, seq = it.seq, role = it.role, text = it.text, audioPath = null, audioMillis = it.audioMillis, status = it.status, createdAt = it.createdAt)
        }
        return isomeria.aichat.core.MessageCopy.text(listOf(message), mapOf(favorite.id to turns), copyLabels(context, entry.source?.name ?: favorite.contactName))
    }

    companion object {
        /** 能收藏的：文字、语音（含没有转写的）、打完的通话；图片、等待回复中、待投递的不能 */
        fun canFavorite(message: Message): Boolean = message.type != MessageType.IMAGE && isSelectable(message)
    }
}
