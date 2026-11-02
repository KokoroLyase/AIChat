package isomeria.aichat.feature.me

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import isomeria.aichat.BuildConfig
import isomeria.aichat.R
import isomeria.aichat.data.repo.FileStorage
import isomeria.aichat.data.settings.SettingsStore
import isomeria.aichat.ui.components.Avatar
import isomeria.aichat.ui.components.AvatarCropDialog
import isomeria.aichat.ui.components.FormRow
import isomeria.aichat.ui.components.GroupSpacer
import isomeria.aichat.ui.components.ImeBackHandler
import isomeria.aichat.ui.components.SheetOption
import isomeria.aichat.ui.components.WeActionSheet
import isomeria.aichat.ui.components.WeIcons
import isomeria.aichat.ui.components.WeListRow
import isomeria.aichat.ui.components.WeTopBar
import isomeria.aichat.ui.theme.AIChatTheme
import isomeria.aichat.ui.theme.SelfAvatarColor
import isomeria.aichat.ui.theme.WeTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MeViewModel @Inject constructor(
    private val settings: SettingsStore,
    private val files: FileStorage,
) : ViewModel() {
    val nickname: StateFlow<String> = settings.nickname
    val avatarPath: StateFlow<String?> = settings.avatarPath

    private val _cropSource = MutableStateFlow<Bitmap?>(null)
    val cropSource: StateFlow<Bitmap?> = _cropSource.asStateFlow()

    fun setNickname(name: String) = settings.setNickname(name.take(20))

    fun pickAvatar(uri: Uri) {
        viewModelScope.launch { _cropSource.value = runCatching { files.decodeForCrop(uri) }.getOrNull() }
    }

    fun cancelCrop() {
        _cropSource.value = null
    }

    fun applyCrop(bitmap: Bitmap) {
        viewModelScope.launch {
            val old = settings.avatarPath.value
            settings.setAvatarPath(files.saveAvatar(bitmap))
            files.commitAvatar(settings.avatarPath.value)
            files.delete(old)
            _cropSource.value = null
        }
    }

    fun resetAvatar() {
        files.delete(settings.avatarPath.value)
        settings.setAvatarPath(null)
    }
}

/** 我：顶部个人卡片（头像、昵称、版本号），下方「设置」。 */
@Composable
fun MeScreen(
    onOpenSettings: () -> Unit,
    onOpenProfile: () -> Unit,
    viewModel: MeViewModel = hiltViewModel(),
) {
    val nickname by viewModel.nickname.collectAsStateWithLifecycle()
    val avatar by viewModel.avatarPath.collectAsStateWithLifecycle()
    MeContent(nickname, avatar, onOpenSettings, onOpenProfile)
}

@Composable
fun MeContent(nickname: String, avatarPath: String?, onOpenSettings: () -> Unit, onOpenProfile: () -> Unit) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    val name = nickname.ifBlank { stringResource(R.string.default_nickname) }
    Column(Modifier.fillMaxSize().background(colors.pageBackground)) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(colors.listBackground)
                .clickable(onClick = onOpenProfile)
                .statusBarsPadding()
                .padding(start = 24.dp, end = dimens.horizontalPadding, top = 40.dp, bottom = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(avatarPath, name, SelfAvatarColor, dimens.profileAvatar, corner = dimens.profileAvatarCorner)
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    name,
                    style = WeTheme.typography.profileName,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.me_version, BuildConfig.VERSION_NAME),
                    style = WeTheme.typography.profileAccount,
                    color = colors.textTertiary,
                )
            }
            Icon(WeIcons.ChevronRight, null, tint = colors.arrow, modifier = Modifier.size(18.dp))
        }
        GroupSpacer()
        WeListRow(
            title = stringResource(R.string.me_settings),
            onClick = onOpenSettings,
            leading = { Icon(Icons.Outlined.Settings, null, tint = colors.link, modifier = Modifier.size(dimens.listRowIcon)) },
        )
    }
}

/** 个人信息：头像、昵称。联系人会用这个昵称称呼你。 */
@Composable
fun ProfileScreen(onBack: () -> Unit, viewModel: MeViewModel = hiltViewModel()) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    val nickname by viewModel.nickname.collectAsStateWithLifecycle()
    val avatar by viewModel.avatarPath.collectAsStateWithLifecycle()
    val crop by viewModel.cropSource.collectAsStateWithLifecycle()
    var showSheet by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(nickname) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.pickAvatar(uri)
    }

    ImeBackHandler()
    Column(Modifier.fillMaxSize().background(colors.pageBackground)) {
        WeTopBar(title = stringResource(R.string.profile_title), onBack = {
            viewModel.setNickname(name.trim())
            onBack()
        })
        GroupSpacer()
        WeListRow(
            title = stringResource(R.string.profile_avatar),
            onClick = { showSheet = true },
            showDivider = true,
            trailing = {
                Avatar(avatar, name.ifBlank { stringResource(R.string.default_nickname) }, SelfAvatarColor, dimens.listRowAvatar)
            },
        )
        FormRow(stringResource(R.string.profile_nickname)) {
            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                if (name.isEmpty()) {
                    Text(stringResource(R.string.profile_nickname_hint), style = WeTheme.typography.listTitle, color = colors.textSecondary)
                }
                BasicTextField(
                    value = name,
                    onValueChange = {
                        name = it.take(20)
                        viewModel.setNickname(name.trim())
                    },
                    singleLine = true,
                    textStyle = WeTheme.typography.listTitle.copy(color = colors.textPrimary),
                    cursorBrush = SolidColor(colors.brand),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (showSheet) {
        WeActionSheet(
            options = buildList {
                add(
                    SheetOption(stringResource(R.string.contact_avatar_pick)) {
                        pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                )
                if (avatar != null) add(SheetOption(stringResource(R.string.contact_avatar_reset)) { viewModel.resetAvatar() })
            },
            onDismiss = { showSheet = false },
        )
    }
    crop?.let { AvatarCropDialog(it, onDismiss = viewModel::cancelCrop, onCropped = viewModel::applyCrop) }
}

@Preview(name = "我 浅色", showBackground = true)
@Composable
private fun MeLightPreview() {
    AIChatTheme(darkTheme = false) { MeContent("Isomeria", null, {}, {}) }
}

@Preview(name = "我 深色", showBackground = true)
@Composable
private fun MeDarkPreview() {
    AIChatTheme(darkTheme = true) { MeContent("Isomeria", null, {}, {}) }
}
