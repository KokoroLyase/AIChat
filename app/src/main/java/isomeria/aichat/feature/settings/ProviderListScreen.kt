package isomeria.aichat.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import isomeria.aichat.R
import isomeria.aichat.data.db.ProviderConfig
import isomeria.aichat.data.repo.ProviderRepository
import isomeria.aichat.ui.components.FootnoteText
import isomeria.aichat.ui.components.GroupSpacer
import isomeria.aichat.ui.components.WeDivider
import isomeria.aichat.ui.components.WeIcons
import isomeria.aichat.ui.components.WeSmallButton
import isomeria.aichat.ui.components.WeTopBar
import isomeria.aichat.ui.theme.WeTheme
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class ProviderListViewModel @Inject constructor(providers: ProviderRepository) : ViewModel() {
    val providers: StateFlow<List<ProviderConfig>> = providers.llmConfigs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val usableModels: StateFlow<Map<Long, Int>> = providers.usableModelCounts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
}

/** 设置 → 模型服务 → 大语言模型：服务商列表，可添加任意兼容 OpenAI 格式的服务商。 */
@Composable
fun ProviderListScreen(
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    onAdd: () -> Unit,
    viewModel: ProviderListViewModel = hiltViewModel(),
) {
    val providers by viewModel.providers.collectAsStateWithLifecycle()
    val usableModels by viewModel.usableModels.collectAsStateWithLifecycle()
    ProviderListContent(providers, onBack, onOpen, onAdd, usableModels)
}

@Composable
fun ProviderListContent(
    providers: List<ProviderConfig>,
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    onAdd: () -> Unit,
    usableModels: Map<Long, Int> = emptyMap(),
) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    Column(Modifier.fillMaxSize().background(colors.pageBackground)) {
        WeTopBar(title = stringResource(R.string.settings_llm), onBack = onBack) {
            WeSmallButton(stringResource(R.string.action_add), onAdd)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            GroupSpacer()
            providers.forEachIndexed { index, provider ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(colors.listBackground)
                        .clickable { onOpen(provider.id) }
                        .heightIn(min = dimens.listRowHeight)
                        .padding(horizontal = dimens.horizontalPadding, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            provider.name,
                            style = WeTheme.typography.listTitle,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            provider.baseUrl,
                            style = WeTheme.typography.listTime,
                            color = colors.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val usable = usableModels[provider.id] ?: 0
                        Text(
                            if (usable > 0) stringResource(R.string.llm_models_usable, usable) else stringResource(R.string.llm_models_none_usable),
                            style = WeTheme.typography.listTime,
                            color = colors.textTertiary,
                        )
                    }
                    Text(
                        stringResource(
                            if (provider.apiKeyEncrypted.isNullOrEmpty()) R.string.llm_api_key_missing else R.string.llm_api_key_set,
                        ),
                        style = WeTheme.typography.small,
                        color = colors.textTertiary,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                    Icon(
                        WeIcons.ChevronRight,
                        contentDescription = null,
                        tint = colors.arrow,
                        modifier = Modifier.padding(start = 4.dp).size(18.dp),
                    )
                }
                if (index != providers.lastIndex) WeDivider(startIndent = dimens.horizontalPadding)
            }
            FootnoteText(stringResource(R.string.llm_providers_hint))
        }
    }
}
