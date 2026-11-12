package isomeria.aichat.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v7 → v8（0.7.2）：记忆分两层，记忆加「是否核心记忆」。旧记忆都是近期记忆，第一次自动记忆时按预算整理出核心记忆。
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE memory ADD COLUMN core INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v6 → v7（0.7.0）：语音消息。消息加语音文件路径、时长、是否播放过、是否显示转写文字（旧消息都没有语音）；
 * 联系人加消息发送类型（NULL：用全局设置，默认跟随）、音色（NULL：服务商默认）和语气风格；服务商加默认音色。
 * 只加列，旧消息、旧联系人的行为不变；语音服务商（kind 为 TTS、ASR）由用户在设置里新建，迁移不写入。
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE message ADD COLUMN voicePath TEXT")
        db.execSQL("ALTER TABLE message ADD COLUMN voiceMillis INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE message ADD COLUMN voicePlayed INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE message ADD COLUMN voiceShowText INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE contact ADD COLUMN replyType TEXT")
        db.execSQL("ALTER TABLE contact ADD COLUMN ttsVoice TEXT")
        db.execSQL("ALTER TABLE contact ADD COLUMN ttsStyle TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE provider_config ADD COLUMN voice TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * v5 → v6（0.6.0）：联系人加主动聊天开关（新旧联系人都默认关闭，用户决定）和频率（默认适中）；
 * 消息加来源（旧消息都是普通消息）；会话加连续未回复次数和最近一次主动请求时间；
 * 新建主动聊天状态表（今日已用、最近运行时间）和约定提醒表。迁移后没有任何联系人开启，不会有后台模型调用。
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE contact ADD COLUMN proactiveEnabled INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE contact ADD COLUMN proactiveFrequency TEXT NOT NULL DEFAULT 'MEDIUM'")
        db.execSQL("ALTER TABLE message ADD COLUMN origin TEXT NOT NULL DEFAULT 'NORMAL'")
        db.execSQL("ALTER TABLE conversation ADD COLUMN proactiveUnanswered INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE conversation ADD COLUMN lastProactiveAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS proactive_state (
                id INTEGER NOT NULL,
                day INTEGER NOT NULL,
                used INTEGER NOT NULL,
                lastRunAt INTEGER NOT NULL,
                PRIMARY KEY (id)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS proactive_reminder (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                contactId INTEGER NOT NULL,
                dueAt INTEGER NOT NULL,
                note TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                FOREIGN KEY (contactId) REFERENCES contact (id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_proactive_reminder_contactId ON proactive_reminder (contactId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_proactive_reminder_dueAt ON proactive_reminder (dueAt)")
    }
}

/**
 * v4 → v5（0.5.0）：联系人加「使用全局记忆」「使用回复偏好」，旧联系人也默认开启（用户决定）；
 * 新建全局记忆的类别、主题、条目三张表。分条显示 splitReplies 原样保留。
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE contact ADD COLUMN useGlobalMemory INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE contact ADD COLUMN useReplyPreferences INTEGER NOT NULL DEFAULT 1")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS global_memory_category (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                sortOrder INTEGER NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS global_memory_topic (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                categoryId INTEGER NOT NULL,
                name TEXT NOT NULL,
                sortOrder INTEGER NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                FOREIGN KEY (categoryId) REFERENCES global_memory_category (id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_global_memory_topic_categoryId ON global_memory_topic (categoryId)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS global_memory_item (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                topicId INTEGER NOT NULL,
                content TEXT NOT NULL,
                enabled INTEGER NOT NULL,
                sortOrder INTEGER NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                FOREIGN KEY (topicId) REFERENCES global_memory_topic (id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_global_memory_item_topicId ON global_memory_item (topicId)")
    }
}

/** v3 → v4（0.4.2）：会话加免打扰和聊天背景，旧会话默认不静音、无背景。 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversation ADD COLUMN muted INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE conversation ADD COLUMN backgroundPath TEXT")
    }
}

/** v2 → v3：只加列，不按地址推断品牌或变更历史请求。 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE provider_config ADD COLUMN presetId TEXT")
        db.execSQL("ALTER TABLE provider_config ADD COLUMN compatibility TEXT NOT NULL DEFAULT 'LEGACY'")
        db.execSQL("ALTER TABLE provider_model ADD COLUMN legacy INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE provider_model ADD COLUMN reportedConfig TEXT")
        db.execSQL("ALTER TABLE provider_model ADD COLUMN userConfig TEXT")
        db.execSQL("ALTER TABLE provider_model ADD COLUMN compatibilityOverride TEXT")
        db.execSQL("ALTER TABLE provider_model ADD COLUMN missingFromList INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE contact ADD COLUMN thinkingOption TEXT")
        db.execSQL("ALTER TABLE contact ADD COLUMN memoryModel TEXT")
        db.execSQL("ALTER TABLE contact ADD COLUMN memoryThinkingOption TEXT NOT NULL DEFAULT 'off'")
    }
}

/**
 * v1 → v2（0.3.0）：多服务商、上下文预算、长期记忆、用量统计。
 *
 * - 已有的服务商记录（id、Base URL、加密的 Key、默认模型都不变）命名为 DeepSeek，模型标记为支持思考，
 *   联系人的 providerId 不用动
 * - 上下文条数换算成 token 预算：条数 × 200，最低 1000（默认的 20 条 → 4000）
 * - 消息表里已有的 token 数回填到用量统计，日期按手机当前时区算
 * - 已有聊天的记忆起点留空（memoryCursor 为 NULL），第一次检查时设为当时的上下文窗口开头，不自动回溯老聊天
 *
 * 只用加列和 DROP COLUMN，不重建表：minSdk 34 的系统 SQLite 都支持（3.35 起），外键关系也不受影响。
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 服务商
        db.execSQL("ALTER TABLE provider_config ADD COLUMN name TEXT NOT NULL DEFAULT ''")
        db.execSQL(
            "UPDATE provider_config SET name = ? WHERE provider = ?",
            arrayOf<Any>(DeepSeekDefaults.NAME, DeepSeekDefaults.PROVIDER),
        )
        db.execSQL("ALTER TABLE provider_model ADD COLUMN thinkingInput TEXT NOT NULL DEFAULT 'UNKNOWN'")
        db.execSQL("ALTER TABLE provider_model ADD COLUMN thinkingUserMark INTEGER")
        db.execSQL("ALTER TABLE provider_model ADD COLUMN manual INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            """
            UPDATE provider_model SET thinkingInput = 'YES'
            WHERE providerId IN (SELECT id FROM provider_config WHERE provider = ?)
            """.trimIndent(),
            arrayOf<Any>(DeepSeekDefaults.PROVIDER),
        )

        // 联系人：条数换算成预算
        db.execSQL("ALTER TABLE contact ADD COLUMN contextTokens INTEGER NOT NULL DEFAULT 4000")
        db.execSQL("ALTER TABLE contact ADD COLUMN autoMemory INTEGER NOT NULL DEFAULT 1")
        db.execSQL("UPDATE contact SET contextTokens = MAX(${Contact.MIN_CONTEXT_TOKENS}, contextCount * 200)")
        db.execSQL("ALTER TABLE contact DROP COLUMN contextCount")

        // 会话：记忆起点。NULL 表示下次检查时再定
        db.execSQL("ALTER TABLE conversation ADD COLUMN memoryCursor INTEGER")
        db.execSQL("ALTER TABLE conversation ADD COLUMN memoryStart INTEGER NOT NULL DEFAULT 0")

        // 记忆
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS memory (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                contactId INTEGER NOT NULL,
                content TEXT NOT NULL,
                fromTime INTEGER NOT NULL,
                toTime INTEGER NOT NULL,
                edited INTEGER NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                FOREIGN KEY (contactId) REFERENCES contact (id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_contactId_fromTime ON memory (contactId, fromTime)")

        // 用量：用消息表里已有的 token 数回填。分条回复只有第一条带用量，所以条数就是请求数
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS usage_daily (
                day INTEGER NOT NULL,
                contactId INTEGER NOT NULL,
                purpose TEXT NOT NULL,
                promptTokens INTEGER NOT NULL,
                completionTokens INTEGER NOT NULL,
                requests INTEGER NOT NULL,
                PRIMARY KEY (day, contactId, purpose)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO usage_daily (day, contactId, purpose, promptTokens, completionTokens, requests)
            SELECT CAST(strftime('%s', date(createdAt / 1000, 'unixepoch', 'localtime')) AS INTEGER) / 86400 AS d,
                   contactId, 'CHAT', SUM(promptTokens), SUM(completionTokens), COUNT(*)
            FROM message
            WHERE role = 'ASSISTANT' AND (promptTokens > 0 OR completionTokens > 0)
            GROUP BY d, contactId
            """.trimIndent(),
        )
    }
}
