package isomeria.aichat.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import isomeria.aichat.R
import isomeria.aichat.ui.components.FootnoteText
import isomeria.aichat.ui.components.WeDivider
import isomeria.aichat.ui.components.WeTopBar
import isomeria.aichat.ui.theme.WeTheme

private data class Library(val name: String, val owner: String, val license: String)

/** 项目名、作者和许可证名称是专有名词，不需要翻译，直接写在这里 */
private val libraries = listOf(
    Library("Kotlin / kotlinx.coroutines / kotlinx.serialization", "JetBrains", "Apache License 2.0"),
    Library("AndroidX (Core, Activity, Lifecycle, Navigation, Room, WorkManager)", "Google", "Apache License 2.0"),
    Library("Jetpack Compose / Material 3 / Material Icons", "Google", "Apache License 2.0"),
    Library("Dagger Hilt", "Google", "Apache License 2.0"),
    Library("OkHttp / Okio", "Square", "Apache License 2.0"),
    Library("Coil", "Coil Contributors", "Apache License 2.0"),
    Library("MMKV", "Tencent", "BSD 3-Clause License"),
    Library("Unicode Emoji (emoji-test.txt)", "Unicode, Inc.", "Unicode License v3"),
    // 0.7.1 离线识别：引擎随安装包；模型由用户在 App 内从魔搭社区下载，App 不内置、不转发
    Library("sherpa-onnx", "k2-fsa (Next-gen Kaldi)", "Apache License 2.0"),
    Library("ONNX Runtime", "Microsoft Corporation", "MIT License"),
    Library("SenseVoice Small (offline ASR model, downloaded on demand)", "Alibaba Group, Tongyi Lab (FunASR); sherpa-onnx conversion by k2-fsa", "FunASR Model Open Source License v1.1"),
)

@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val colors = WeTheme.colors
    Column(Modifier.fillMaxSize().background(colors.pageBackground)) {
        WeTopBar(title = stringResource(R.string.licenses_title), onBack = onBack)
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
            item { FootnoteText(stringResource(R.string.licenses_intro)) }
            items(libraries) { lib ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(colors.listBackground)
                        .padding(horizontal = WeTheme.dimens.horizontalPadding, vertical = 12.dp),
                ) {
                    Text(lib.name, style = WeTheme.typography.listTitle, color = colors.textPrimary)
                    Text("${lib.owner} · ${lib.license}", style = WeTheme.typography.small, color = colors.textTertiary)
                }
                WeDivider(startIndent = WeTheme.dimens.horizontalPadding)
            }
        }
    }
}
