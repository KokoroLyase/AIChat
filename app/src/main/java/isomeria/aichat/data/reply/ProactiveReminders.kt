package isomeria.aichat.data.reply

import isomeria.aichat.core.ParsedReminder
import isomeria.aichat.core.ProactivePolicy
import isomeria.aichat.core.ReminderMarker
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.ProactiveReminder
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * 主动聊天的后台任务开关；[ProactiveScheduler] 用 WorkManager 实现，单元测试里换成记录调用的假实现。
 */
interface ProactiveControl {
    /**
     * 让后台任务和数据对上：至少一个联系人开启主动聊天时注册周期检查，全部关闭时取消；
     * 有约定提醒时按最近的一个排一次性任务，没有时取消。改了开关、记下或用掉约定、恢复数据、启动时调用。
     */
    suspend fun sync()

    companion object {
        val NONE = object : ProactiveControl {
            override suspend fun sync() = Unit
        }
    }
}

/**
 * 约定提醒（0.6.0）：开启主动聊天的联系人在回复里用隐藏标记（[ReminderMarker]）记下用户约好的时间。
 * 标记不显示给用户；没开主动聊天的联系人，回复原样保留（提示词里也不会教它写标记）。
 */
@Singleton
class ProactiveReminders @Inject constructor(
    private val db: AppDatabase,
    private val control: ProactiveControl,
    private val zone: Provider<ZoneId>,
    labels: ProactiveLabels,
) {
    private val marker = ReminderMarker(labels.reminderKeyword)

    /** 去掉回复里的约定标记，返回正文和解析出的约定 */
    fun extract(contact: Contact, text: String, now: Long): Pair<String, List<ParsedReminder>> =
        if (contact.proactiveEnabled) marker.extract(text, now, zone.get()) else text to emptyList()

    /** 记下约定：同一时间的不重复记，每个联系人最多留 [ProactivePolicy.MAX_REMINDERS_PER_CONTACT] 个。和写回复在同一个事务里调用 */
    suspend fun save(contactId: Long, reminders: List<ParsedReminder>, now: Long) {
        if (reminders.isEmpty()) return
        val dao = db.proactiveDao()
        val existing = dao.remindersOf(contactId)
        val known = existing.mapTo(HashSet()) { it.dueAt }
        var room = ProactivePolicy.MAX_REMINDERS_PER_CONTACT - existing.size
        for (reminder in reminders) {
            if (room <= 0) break
            if (!known.add(reminder.dueAt)) continue
            dao.insertReminder(
                ProactiveReminder(contactId = contactId, dueAt = reminder.dueAt, note = reminder.note.take(ProactiveReminder.NOTE_MAX_LENGTH), createdAt = now),
            )
            room--
        }
    }

    /** 约定有变化后，重新排到点的一次性任务 */
    suspend fun schedule() = control.sync()
}
