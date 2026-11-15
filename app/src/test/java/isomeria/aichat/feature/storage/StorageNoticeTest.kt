package isomeria.aichat.feature.storage

import android.app.Application
import isomeria.aichat.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 存储空间页底部的说明：清理缓存不影响的内容要列全存储空间页上的各项，孤立文件和随消息删除的也要写上聊天文件。
 * 0.9.2 加“聊天文件”一行时漏改了这段（真机验证后重录截图时发现）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class StorageNoticeTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun notice_listsEveryKeptRow() {
        val notice = context.getString(R.string.storage_notice)
        val cacheSentence = notice.substringBefore("。")
        val kept = listOf(
            R.string.storage_images, R.string.storage_avatars, R.string.storage_backgrounds, R.string.storage_voice,
            R.string.storage_calls, R.string.storage_files, R.string.storage_favorites, R.string.storage_asr_models, R.string.storage_database,
        ).map(context::getString)
        val missing = kept.filter { it !in cacheSentence }
        assertTrue("清理缓存的说明漏了：$missing", missing.isEmpty())
        val files = context.getString(R.string.storage_files)
        assertTrue("孤立文件和随消息删除的说明里要有$files：$notice", notice.split("。").drop(1).count { files in it } >= 2)
    }
}
