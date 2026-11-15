package isomeria.aichat.data.backup

import android.app.Application
import isomeria.aichat.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 导出页写着导出的文件要用哪个版本以上的智信恢复。旧版本遇到更新的备份格式会拒绝（[BackupFormat.VERSION] 更大时），
 * 所以格式升级时这句话也要跟着改。0.8.0（v9）起这句一直写着 0.7.2，0.9.2 真机验证时发现。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ExportNoteTest {
    /** 每个备份格式第一次出现的版本；升格式时在这里加一行，并改导出页的说明 */
    private val firstVersion = mapOf(11 to "0.9.2")

    @Test
    fun exportNote_namesTheFirstVersionThatReadsThisFormat() {
        val version = firstVersion[BackupFormat.VERSION]
        assertTrue("备份格式升到了 v${BackupFormat.VERSION}：在 firstVersion 里加上它，并改 export_modules_note", version != null)
        val note = RuntimeEnvironment.getApplication().getString(R.string.export_modules_note)
        assertTrue("导出页应写“$version 及以上版本”：$note", "$version 及以上版本" in note)
    }
}
