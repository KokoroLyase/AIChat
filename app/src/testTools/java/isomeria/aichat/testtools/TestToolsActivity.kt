package isomeria.aichat.testtools

import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.graphics.createBitmap
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import dagger.hilt.android.AndroidEntryPoint
import isomeria.aichat.R
import isomeria.aichat.data.db.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject

/** Local fixtures only. This class and manifest entry are absent from release. Never enqueue requests. */
@AndroidEntryPoint
class TestToolsActivity : ComponentActivity() {
    @Inject lateinit var db: AppDatabase
    @Inject lateinit var chats: isomeria.aichat.data.repo.ChatRepository
    @Inject lateinit var files: isomeria.aichat.data.repo.FileStorage
    @Inject lateinit var settings: isomeria.aichat.data.settings.SettingsStore
    @Inject lateinit var providers: isomeria.aichat.data.repo.ProviderRepository
    @Inject lateinit var proactive: isomeria.aichat.data.reply.ProactiveEngine
    @Inject lateinit var proactiveScheduler: isomeria.aichat.data.reply.ProactiveScheduler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply { setText(R.string.tools_running) }
        setContentView(text)
        when (intent.getStringExtra("action")) {
            "crash" -> throw IllegalStateException("LocalCrashFixture")
            "own_avatar" -> lifecycleScope.launch {
                try {
                    val bitmap = createBitmap(80, 80).apply { eraseColor(0xFFB85A36.toInt()) }
                    val old = settings.avatarPath.value
                    val path = try { files.saveAvatar(bitmap) } finally { bitmap.recycle() }
                    settings.setAvatarPath(path)
                    files.commitAvatar(path)
                    files.delete(old)
                    text.setText(R.string.tools_avatar_ready)
                } catch (_: Exception) { text.setText(R.string.tools_failed) }
            }
            "appearance" -> {
                settings.setThemeMode(if (intent.getBooleanExtra("dark", false)) isomeria.aichat.data.settings.ThemeMode.DARK else isomeria.aichat.data.settings.ThemeMode.LIGHT)
                settings.setFontSize(if (intent.getBooleanExtra("large", false)) isomeria.aichat.data.settings.FontSize.EXTRA_LARGE else isomeria.aichat.data.settings.FontSize.STANDARD)
                text.setText(R.string.tools_appearance_ready)
            }
            "verify_fixture" -> lifecycleScope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        val configured = providers.list().filter { it.apiKeyEncrypted != null }
                        val keysMatch = configured.isNotEmpty() && configured.all { providers.apiKey(it) == "123456" }
                        val avatar = settings.avatarPath.value?.let { File(it).isFile } ?: false
                        "fixtureKeys=$keysMatch; configured=${configured.size}; ownAvatar=$avatar"
                    }
                    text.text = getString(R.string.tools_fixture_result, result)
                } catch (_: Exception) { text.setText(R.string.tools_failed) }
            }
            // Only local test packages contain these actions. No private data is included.
            "native_crash" -> Handler(Looper.getMainLooper()).postDelayed({
                Os.kill(Process.myPid(), OsConstants.SIGABRT)
            }, 500)
            "anr" -> {
                text.setText(R.string.tools_anr)
                Handler(Looper.getMainLooper()).postDelayed({ Thread.sleep(30_000) }, 1_000)
            }
            "deny_snapshot_delete" -> {
                val directory = File(filesDir, "backups").apply { mkdirs() }
                val snapshot = File(directory, "before-restore.zip")
                require(!snapshot.exists())
                // After a real restore writes its snapshot, deny directory unlink permission.
                // No restore implementation hooks or simulated File.delete results are involved.
                Thread {
                    val deadline = SystemClock.elapsedRealtime() + 120_000
                    while (SystemClock.elapsedRealtime() < deadline) {
                        if (snapshot.isFile) {
                            Os.chmod(directory.path, 0x140) // 0500; restore with run-as chmod 700.
                            return@Thread
                        }
                        Thread.sleep(2)
                    }
                }.apply { isDaemon = true; start() }
                text.setText(R.string.tools_snapshot_watch)
            }
            "search" -> lifecycleScope.launch {
                try {
                    val query = intent.getStringExtra("query") ?: "123"
                    val contact = intent.getLongExtra("contact", 1L)
                    val measurements = withContext(Dispatchers.IO) {
                        List(3) {
                            val start = SystemClock.elapsedRealtimeNanos()
                            val contacts = chats.searchContacts(query).size
                            val groups = chats.searchMessageGroups(query).size
                            val messages = chats.searchInChat(contact, query).size
                            val nanos = SystemClock.elapsedRealtimeNanos() - start
                            "${nanos / 1_000_000.0} ms; contacts=$contacts groups=$groups messages=$messages"
                        }.joinToString("\n")
                    }
                    text.text = getString(R.string.tools_search_result, measurements)
                } catch (_: Exception) { text.setText(R.string.tools_failed) }
            }
            // 主动聊天「立即运行一次」：不掷概率、不看最短间隔，其余跳过条件和每日次数照常。只在 debug / perf 包里有。
            // 会按联系人的配置调用模型：测试时服务商必须指向本机模拟接口
            "proactive_run" -> lifecycleScope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        val run = proactive.run(force = !intent.getBooleanExtra("roll", false), remindersOnly = intent.getBooleanExtra("reminders", false))
                        val state = db.proactiveDao().state()
                        "ran=${run.ran}; " + run.attempts.joinToString { (id, a) -> "$id:${a.outcome}${a.block?.let { b -> "/$b" } ?: ""}" } +
                            "; used=${state?.used ?: 0}; scheduled=${proactiveScheduler.isCheckScheduled()}; reminder=${proactiveScheduler.isReminderScheduled()}"
                    }
                    text.text = getString(R.string.tools_proactive_result, result)
                    android.util.Log.i("TestTools", "proactive_run $result")
                } catch (_: Exception) { text.setText(R.string.tools_failed) }
            }
            // 0.9.0：预置音频代替麦克风。先 adb push 到 /sdcard/Android/data/<包名>/files/ 下，再用这个操作复制进 files/
            // （perf 包不能 run-as）。target=call（默认，通话用 test-call-input.wav）或 voice（按住说话用 test-voice-input.wav）；
            // remove=true 删掉，回到用麦克风
            "import_call_audio" -> lifecycleScope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        val name = if (intent.getStringExtra("target") == "voice") isomeria.aichat.data.voice.AndroidVoiceRecorder.TEST_INPUT
                        else isomeria.aichat.data.voice.AndroidCallMicrophone.TEST_INPUT
                        val external = getExternalFilesDir(null) ?: return@withContext "noExternalDir"
                        CallAudioFixture.import(File(external, name), File(filesDir, name), intent.getBooleanExtra("remove", false))
                    }
                    text.text = getString(R.string.tools_call_audio_result, result)
                    android.util.Log.i("TestTools", "import_call_audio $result")
                } catch (_: Exception) { text.setText(R.string.tools_failed) }
            }
            // 只看状态，不运行
            "proactive_state" -> lifecycleScope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        val state = db.proactiveDao().state()
                        "enabled=${db.contactDao().proactiveCount()}; used=${state?.used ?: 0}; lastRunAt=${state?.lastRunAt ?: 0}; " +
                            "reminders=${db.proactiveDao().reminderCount()}; scheduled=${proactiveScheduler.isCheckScheduled()}; reminder=${proactiveScheduler.isReminderScheduled()}"
                    }
                    text.text = getString(R.string.tools_proactive_result, result)
                    android.util.Log.i("TestTools", "proactive_state $result")
                } catch (_: Exception) { text.setText(R.string.tools_failed) }
            }
            "seed" -> lifecycleScope.launch {
                try {
                    val count = intent.getIntExtra("count", 10_000)
                    require(count in 1..50_000)
                    val id = withContext(Dispatchers.IO) { seed(count) }
                    text.text = getString(R.string.tools_seeded, id, count)
                } catch (_: Exception) { text.setText(R.string.tools_failed) }
            }
            else -> text.setText(R.string.tools_usage)
        }
    }

    private suspend fun seed(count: Int): Long = isomeria.aichat.data.repo.StorageOperations.gate.activity { db.withTransaction {
        val provider = db.providerDao().insert(ProviderConfig(
            kind = ProviderKind.LLM, provider = ProviderProtocol.OPENAI, name = "Local fixture", baseUrl = "https://example.invalid/v1", defaultModel = "fixture",
        ))
        val now = System.currentTimeMillis()
        val contact = db.contactDao().insert(Contact(
            name = getString(R.string.tools_contact), avatarColor = 0xFF607D8B.toInt(), providerId = provider,
            model = "fixture", autoMemory = false, createdAt = now, updatedAt = now,
        ))
        val images = File(filesDir, "images").apply { mkdirs() }
        val bitmap = createBitmap(16, 16)
        val imageBytes = java.io.ByteArrayOutputStream()
        try { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, imageBytes) }
        finally { bitmap.recycle() }
        for (start in 0 until count step 500) {
            db.messageDao().insertAll((start until minOf(start + 500, count)).map { index ->
                val user = index % 4 == 0
                val picture = user && index % 200 == 0
                val image = if (picture) File(images, "${UUID.randomUUID()}.jpg").apply { writeBytes(imageBytes.toByteArray()) } else null
                Message(
                    contactId = contact, role = if (user) Role.USER else Role.ASSISTANT,
                    type = if (picture) MessageType.IMAGE else MessageType.TEXT,
                    content = getString(R.string.tools_message, index),
                    imagePath = image?.absolutePath,
                    imageWidth = if (picture) 16 else 0, imageHeight = if (picture) 16 else 0,
                    replyGroup = if (user) null else "fixture-$contact-${index / 4}",
                    status = MessageStatus.SUCCESS, createdAt = now - (count - index) * 1000L,
                )
            })
        }
        db.conversationDao().upsert(Conversation(contactId = contact, unreadCount = 3, lastMessageTime = now - 1000,
            lastMessagePreview = getString(R.string.tools_message, count - 1)))
        contact
    } }
}
