package isomeria.aichat.data.repo

import isomeria.aichat.core.UsageStats
import isomeria.aichat.data.db.UsageDaily
import isomeria.aichat.data.db.UsageDao
import isomeria.aichat.data.db.UsagePurpose
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** token 用量：每次请求成功后累加，设置里按联系人、按天查看。 */
@Singleton
class UsageRepository @Inject constructor(private val dao: UsageDao) {
    fun observeAll(): Flow<List<UsageDaily>> = dao.observeAll()

    suspend fun record(contactId: Long, purpose: UsagePurpose, promptTokens: Int, completionTokens: Int) {
        val day = UsageStats.epochDay(System.currentTimeMillis())
        dao.add(day, contactId, purpose, promptTokens.toLong(), completionTokens.toLong())
    }
}
