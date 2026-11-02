package isomeria.aichat.data.backup

import kotlinx.serialization.json.JsonObject

/** 备份来自更新版本的智信：数据格式版本比当前新，需要先升级 App。 */
class BackupTooNewException(val formatVersion: Int) : Exception("备份数据格式 v$formatVersion 比当前 v${BackupFormat.VERSION} 新")

/** 不是智信的备份文件，或文件已损坏。 */
class InvalidBackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 旧格式的 data.json 逐版升级到当前格式：v1 → v2 → … → [BackupFormat.VERSION]。
 *
 * 以后改格式时，在 [steps] 里加一项「从 N 升到 N+1」的转换，直接改 JSON 树（加字段、改名、拆分），
 * 不依赖旧的数据类。v1 是第一个格式，目前还没有转换步骤。
 */
class BackupMigrations(
    private val current: Int = BackupFormat.VERSION,
    private val steps: Map<Int, (JsonObject) -> JsonObject> = STEPS,
) {
    fun migrate(data: JsonObject, from: Int): JsonObject {
        if (from > current) throw BackupTooNewException(from)
        if (from < 1) throw InvalidBackupException("数据格式版本无效：$from")
        var result = data
        for (version in from until current) {
            val step = steps[version] ?: throw InvalidBackupException("缺少 v$version 到 v${version + 1} 的转换")
            result = step(result)
        }
        return result
    }

    companion object {
        /** 键是旧版本号，值把这个版本的 data.json 转成下一个版本 */
        val STEPS: Map<Int, (JsonObject) -> JsonObject> = emptyMap()
    }
}
