package isomeria.aichat.testtools

import android.graphics.Bitmap
import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply { setText(R.string.tools_running) }
        setContentView(text)
        when (intent.getStringExtra("action")) {
            "crash" -> throw IllegalStateException("LocalCrashFixture")
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

    private suspend fun seed(count: Int): Long = db.withTransaction {
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
    }
}
