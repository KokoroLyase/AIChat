package isomeria.aichat.update

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseNotesTest {
    @Test
    fun stripsMarkdown() {
        val markdown = "智信 0.2.0：数据与更新。\r\n\r\n## 新功能\r\n\r\n- **导出数据**：生成 zip\r\n* 查看 [Release](https://github.com/x) 页面\r\n" +
            "![截图](a.png)\r\n<!-- 内部备注 -->\r\n\r\n\r\n\r\n需要 `Android 14` 及以上。"
        assertEquals(
            "智信 0.2.0：数据与更新。\n\n新功能\n\n• 导出数据：生成 zip\n• 查看 Release 页面\n\n需要 Android 14 及以上。",
            ReleaseNotes.toPlainText(markdown),
        )
    }

    @Test
    fun keepsNestedBulletIndent() {
        assertEquals("• 一\n  • 二", ReleaseNotes.toPlainText("- 一\n  - 二"))
    }

    @Test
    fun emptyBody() {
        assertEquals("", ReleaseNotes.toPlainText(""))
    }
}
