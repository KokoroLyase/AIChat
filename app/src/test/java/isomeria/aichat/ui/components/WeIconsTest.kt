package isomeria.aichat.ui.components

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.9.1：界面里的图标都是自绘的，不再引用 Material Icons。 */
class WeIconsTest {
    @Test fun sourceDoesNotUseMaterialIcons() {
        val root = File("src/main/java")
        val offenders = root.walkTopDown().filter { it.extension == "kt" }
            .filter { "androidx.compose.material.icons" in it.readText() || Regex("""(?<![\w.])Icons\.(Outlined|Filled|Default|Rounded|Sharp|TwoTone|AutoMirrored)\b""").containsMatchIn(it.readText()) }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .toList()
        assertTrue("Material Icons still used in: $offenders", offenders.isEmpty())
    }

    @Test fun buildFileHasNoMaterialIconsDependency() {
        val build = File("build.gradle.kts").readText()
        val catalog = File("../gradle/libs.versions.toml").readText()
        assertTrue("material-icons" !in build && "icons.extended" !in build)
        assertTrue("material-icons" !in catalog)
    }

    @Test fun everyIconIs24dpOnA24Grid() {
        val icons = allWeIcons()
        // 0.9.0 时 23 个，0.9.1 新画 23 个（Tab 的实心版也算）
        assertTrue("only ${icons.size} icons", icons.size >= 46)
        icons.forEach { (name, icon) ->
            assertEquals(name, 24.dp, icon.defaultWidth)
            assertEquals(name, 24.dp, icon.defaultHeight)
            assertEquals(name, 24f, icon.viewportWidth)
            assertEquals(name, 24f, icon.viewportHeight)
        }
    }
}

/** 按名字排好的全部自绘图标（反射 WeIcons 的 getter），截图里的图标总览也用它 */
fun allWeIcons(): List<Pair<String, ImageVector>> =
    WeIcons::class.java.methods
        .filter { it.parameterCount == 0 && it.returnType == ImageVector::class.java && it.name.startsWith("get") }
        .map { it.name.removePrefix("get") to it.invoke(WeIcons) as ImageVector }
        .sortedBy { it.first }
