package isomeria.aichat.feature.me

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import isomeria.aichat.ui.theme.AIChatTheme
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.w3c.dom.Element

/**
 * 1.0.0：界面文字里写的「我 → X → …」（也有写成“我 → X”的）设置路径，第一层 X 必须是「我」页上真有的入口。
 * 0.9.2 有两条提示写成了「我 → 设置 → 模型服务 → 文件解析」，而「我」页 0.4.2 起就没有单独的“设置”入口。
 * 入口从实际画出来的「我」页取，不在这里另写一份清单。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h2000dp-xxhdpi", application = Application::class)
class MePathInStringsTest {
    @get:Rule val compose = createComposeRule()

    /** strings.xml 里所有 string 和 plurals item 的文字（已经按 XML 解码） */
    private fun allStrings(): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/values/strings.xml"))
        val out = linkedMapOf<String, String>()
        val strings = doc.getElementsByTagName("string")
        for (i in 0 until strings.length) {
            val e = strings.item(i) as Element
            out[e.getAttribute("name")] = e.textContent
        }
        val plurals = doc.getElementsByTagName("plurals")
        for (i in 0 until plurals.length) {
            val p = plurals.item(i) as Element
            val items = p.getElementsByTagName("item")
            for (j in 0 until items.length) {
                val e = items.item(j) as Element
                out["${p.getAttribute("name")}/${e.getAttribute("quantity")}"] = e.textContent
            }
        }
        return out
    }

    /** 「我」页上显示的每一行文字 */
    private fun mePageTexts(): Set<String> {
        compose.setContent { AIChatTheme(darkTheme = false) { MeContent("测试昵称", null, {}, {}) } }
        return compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { node -> node.config[SemanticsProperties.Text].map { it.text } }
            .toSet()
    }

    @Test
    fun everyMePathStartsWithARealMePageEntry() {
        val entries = mePageTexts()
        // 「我」页现在的入口：收藏，全局记忆一组四个，设置四个
        listOf("收藏", "全局记忆", "回复偏好", "主动聊天", "语音消息", "模型服务", "通用设置", "数据管理", "关于 APP").forEach {
            assertTrue("「我」页上没有「$it」，测试取到的是 $entries", it in entries)
        }
        assertTrue("「我」页不应该有单独的「设置」入口", "设置" !in entries)

        val paths = allStrings().flatMap { (name, text) -> mePaths(text).map { name to it } }
        // 现在有十几条带路径的文字（包括写成“我 → 语音消息”的 voice_picker_no_source），没扫到说明匹配写坏了
        assertTrue("只扫到 ${paths.size} 条路径", paths.size >= 15)
        assertTrue("没扫到用“”写的路径", paths.any { it.first == "voice_picker_no_source" })
        val wrong = paths.filter { (_, first) -> first !in entries }
        assertEquals("这些文字里「我 → X」的 X 不是「我」页的入口", emptyList<Pair<String, String>>(), wrong)
    }

    @Test
    fun pathMatcherHandlesBothQuoteStylesAndDeeperLevels() {
        assertEquals(listOf("设置"), mePaths("先在「我 → 设置 → 模型服务 → 文件解析」里配置"))
        assertEquals(listOf("模型服务"), mePaths("先在「我 → 模型服务 → 文件解析」里配置"))
        assertEquals(listOf("语音消息"), mePaths("先在“我 → 语音消息”里选一个。"))
        assertEquals(listOf("主动聊天", "通用设置"), mePaths("在「我 → 主动聊天」调整，在「我 → 通用设置」关闭"))
        assertEquals(emptyList<String>(), mePaths("见「关于 APP → 开源许可」"))
    }

    companion object {
        private val PATH = Regex("[「“]我 → ([^→」”]+?)\\s*(?:→|[」”])")

        /** 文字里每条「我 → X …」路径的第一层 X */
        fun mePaths(text: String): List<String> = PATH.findAll(text).map { it.groupValues[1].trim() }.toList()
    }
}
