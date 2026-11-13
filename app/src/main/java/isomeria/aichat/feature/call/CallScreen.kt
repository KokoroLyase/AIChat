package isomeria.aichat.feature.call

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import isomeria.aichat.R
import isomeria.aichat.core.CallDurations
import isomeria.aichat.data.call.CallBlock
import isomeria.aichat.data.call.CallNotice
import isomeria.aichat.data.call.CallPhase
import isomeria.aichat.data.call.CallState
import isomeria.aichat.data.db.CallEndReason
import isomeria.aichat.data.db.Role as SpeakerRole
import isomeria.aichat.feature.chat.failReasonText
import isomeria.aichat.feature.chat.findActivity
import isomeria.aichat.ui.components.Avatar
import isomeria.aichat.ui.components.WeDialog
import isomeria.aichat.ui.components.WeIcons
import isomeria.aichat.ui.theme.WeTheme
import isomeria.aichat.ui.theme.loopingFloat
import kotlinx.coroutines.delay

/** 通话页显示的内容（不依赖 ViewModel，截图测试直接传进来） */
data class CallUi(
    val name: String,
    val avatarPath: String?,
    val avatarColor: Int,
    /** 名字下面一行：通话时长，或“正在接通…”“通话已结束” */
    val status: String,
    /** 现在在做什么：在听、正在说、正在识别、对方正在想 N 秒、对方正在说 */
    val phase: String = "",
    /** 字幕：最近一句（说话的人、内容）；不显示字幕或还没说话时为 null */
    val subtitle: Pair<String, String>? = null,
    val notice: String? = null,
    val hints: List<String> = emptyList(),
    val muted: Boolean = false,
    val speakerphone: Boolean = false,
    val subtitles: Boolean = true,
    /** 「说完了」（用户正在说时）或「打断」（对方在说时）；都没有时为 null */
    val action: CallAction? = null,
    /** 已经接通（按钮可用） */
    val active: Boolean = true,
    /** 现在在做什么，决定头像和状态下面的动效；还没接通时为 null */
    val stage: CallPhase? = CallPhase.LISTENING,
)

enum class CallAction { FINISH, INTERRUPT }

class CallActions(
    val onMute: (Boolean) -> Unit = {},
    val onSpeakerphone: (Boolean) -> Unit = {},
    val onHangUp: () -> Unit = {},
    val onAction: (CallAction) -> Unit = {},
    val onSubtitles: (Boolean) -> Unit = {},
    val onDismissNotice: () -> Unit = {},
)

/**
 * 语音通话页（0.8.0）：头像、名字、时长和状态，字幕（可以收起），提示；底下是静音、挂断、免提，用户正在说时多一个「说完了」，
 * 对方在说时多一个「打断」。和微信的通话页一样，深色、浅色模式下都是深色底。
 *
 * 进来时先检查能不能打（不能时说明原因、给出去设置的按钮），第一次通话先说明录音和文字发给谁，再要麦克风权限，然后接通。
 * 返回键不挂断：回到桌面，通话接着打（通知栏可以回来或挂断）。挂断后显示“通话已结束”和原因，稍后关闭页面。
 */
@Composable
fun CallScreen(
    onClose: () -> Unit,
    onEditContact: (Long) -> Unit,
    onOpenVoiceSettings: () -> Unit,
    onOpenProvider: (Long) -> Unit,
    viewModel: CallViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val contact by viewModel.contact.collectAsStateWithLifecycle()
    val call by viewModel.call.collectAsStateWithLifecycle()
    val prepare by viewModel.prepare.collectAsStateWithLifecycle()
    val subtitles by viewModel.subtitles.collectAsStateWithLifecycle()
    var showMicDenied by remember { androidx.compose.runtime.mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.prepare() }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.start() else showMicDenied = true
    }
    LaunchedEffect(prepare) {
        if (prepare != CallPrepare.NeedPermission) return@LaunchedEffect
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            viewModel.start()
            return@LaunchedEffect
        }
        val activity = context.findActivity()
        if (viewModel.micPermissionAsked && activity != null && !activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            showMicDenied = true
        } else {
            viewModel.micPermissionAsked = true
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val ended = prepare == CallPrepare.Running && call?.phase == CallPhase.ENDED
    // 挂断后停一会儿再关，看得到“通话已结束”和原因
    LaunchedEffect(ended) {
        if (ended) {
            delay(ENDED_CLOSE_MILLIS)
            onClose()
        }
    }
    // 返回不挂断：通话中回到桌面，接着打
    BackHandler {
        if (prepare == CallPrepare.Running && !ended) context.findActivity()?.moveTaskToBack(true) else onClose()
    }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000 - System.currentTimeMillis() % 1_000)
            now = System.currentTimeMillis()
        }
    }

    val ui = callUi(contact?.name.orEmpty(), contact?.avatarPath, contact?.avatarColor ?: 0, prepare, call, now, subtitles, thinkingOn = contact?.let { isThinking(it) } == true)
    CallContent(
        ui,
        CallActions(
            onMute = viewModel::setMuted,
            onSpeakerphone = viewModel::setSpeakerphone,
            onHangUp = { if (prepare == CallPrepare.Running && !ended) viewModel.hangUp() else onClose() },
            onAction = { if (it == CallAction.FINISH) viewModel.finishSpeaking() else viewModel.interrupt() },
            onSubtitles = viewModel::setSubtitles,
            onDismissNotice = viewModel::dismissNotice,
        ),
    )

    when (val current = prepare) {
        is CallPrepare.Blocked -> {
            val (message, action) = blockText(current.block)
            WeDialog(
                title = stringResource(R.string.call_block_title),
                message = message,
                confirmText = action?.let { stringResource(it) } ?: stringResource(R.string.action_ok),
                dismissText = if (action != null) stringResource(R.string.action_cancel) else null,
                onDismiss = onClose,
                onConfirm = {
                    onClose()
                    when (current.block) {
                        CallBlock.ASR_UNAVAILABLE, CallBlock.TTS_UNAVAILABLE -> onOpenVoiceSettings()
                        CallBlock.CONTACT_ASR_UNAVAILABLE, CallBlock.CONTACT_TTS_UNAVAILABLE, CallBlock.MODEL_CONFIG -> onEditContact(viewModel.contactId)
                        CallBlock.NO_API_KEY -> contact?.let { onOpenProvider(it.providerId) }
                        else -> Unit
                    }
                },
            )
        }
        is CallPrepare.Intro -> WeDialog(
            title = stringResource(R.string.call_intro_title),
            message = stringResource(
                R.string.call_intro_message,
                current.asrName?.let { stringResource(R.string.call_intro_asr_provider, it) } ?: stringResource(R.string.call_intro_asr_local),
                current.ttsName?.let { stringResource(R.string.call_intro_tts_provider, it) } ?: stringResource(R.string.call_intro_tts_local),
            ),
            confirmText = stringResource(R.string.call_intro_confirm),
            onDismiss = onClose,
            onConfirm = viewModel::introAccepted,
        )
        else -> Unit
    }
    if (showMicDenied) {
        WeDialog(
            title = stringResource(R.string.voice_mic_denied_title),
            message = stringResource(R.string.call_mic_denied_message),
            confirmText = stringResource(R.string.action_go_settings),
            onDismiss = onClose,
            onConfirm = {
                onClose()
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
        )
    }
}

/** 联系人开着思考：每轮可能要等较久 */
private fun isThinking(contact: isomeria.aichat.data.db.Contact): Boolean =
    (contact.thinkingOption ?: isomeria.aichat.data.llm.ModelRules.legacyOption(contact.thinking)) != THINKING_OFF

/** 思考关闭时的选项值（和 ModelRules 一样） */
private const val THINKING_OFF = "off"

@Composable
private fun callUi(
    name: String,
    avatarPath: String?,
    avatarColor: Int,
    prepare: CallPrepare,
    call: CallState?,
    now: Long,
    subtitles: Boolean,
    thinkingOn: Boolean,
): CallUi {
    val running = prepare == CallPrepare.Running && call != null
    if (!running || call == null) {
        return CallUi(name, avatarPath, avatarColor, status = stringResource(R.string.call_status_connecting), subtitles = subtitles, active = false, stage = null)
    }
    val ended = call.phase == CallPhase.ENDED
    val status = if (ended) stringResource(R.string.call_status_ended) else CallDurations.format(now - call.startedAt)
    val phase = when (call.phase) {
        CallPhase.LISTENING -> stringResource(if (call.muted) R.string.call_phase_muted else R.string.call_phase_listening)
        CallPhase.HEARING -> stringResource(R.string.call_phase_hearing)
        CallPhase.RECOGNIZING -> stringResource(R.string.call_phase_recognizing)
        CallPhase.THINKING -> stringResource(R.string.call_phase_thinking, ((now - call.thinkingSince).coerceAtLeast(0) / 1_000).toInt())
        CallPhase.SPEAKING -> stringResource(R.string.call_phase_speaking)
        CallPhase.PAUSED -> stringResource(R.string.call_phase_paused)
        CallPhase.ENDED -> endText(call.endReason)
    }
    val notice = when (call.notice) {
        null -> null
        CallNotice.ASR_FAILED -> stringResource(R.string.call_notice_asr_failed)
        CallNotice.ASR_UNAVAILABLE -> stringResource(R.string.call_notice_asr_unavailable)
        CallNotice.REPLY_FAILED -> stringResource(R.string.call_notice_reply_failed, stringResource(failReasonText(call.noticeReason ?: isomeria.aichat.data.repo.FailReason.UNKNOWN)))
        CallNotice.TTS_FAILED -> stringResource(R.string.call_notice_tts_failed)
        CallNotice.TIME_WARNING -> stringResource(R.string.call_notice_time_warning)
    }
    val hints = buildList {
        if (!call.modelVad) add(stringResource(R.string.call_hint_energy))
        if (thinkingOn) add(stringResource(R.string.call_hint_thinking))
    }
    val subtitle = call.subtitle?.takeIf { subtitles }?.let { line ->
        (if (line.role == SpeakerRole.USER) stringResource(R.string.call_subtitle_me) else name) to line.text
    }
    return CallUi(
        name = name,
        avatarPath = avatarPath,
        avatarColor = avatarColor,
        status = status,
        phase = phase,
        subtitle = subtitle,
        notice = if (ended) null else notice,
        hints = if (ended) emptyList() else hints,
        muted = call.muted,
        speakerphone = call.speakerphone,
        subtitles = subtitles,
        action = when {
            ended -> null
            call.phase == CallPhase.HEARING -> CallAction.FINISH
            call.phase == CallPhase.SPEAKING -> CallAction.INTERRUPT
            else -> null
        },
        active = !ended,
        stage = call.phase,
    )
}

@Composable
private fun endText(reason: CallEndReason?): String = when (reason) {
    CallEndReason.AUDIO_LOST -> stringResource(R.string.call_end_audio_lost)
    CallEndReason.FAILURES -> stringResource(R.string.call_end_failures)
    CallEndReason.TIME_LIMIT -> stringResource(R.string.call_end_time_limit)
    CallEndReason.IDLE -> stringResource(R.string.call_end_idle)
    CallEndReason.MICROPHONE -> stringResource(R.string.call_end_microphone)
    else -> ""
}

/** 不能打的原因和按钮（null 表示只有「知道了」） */
@Composable
private fun blockText(block: CallBlock): Pair<String, Int?> = when (block) {
    CallBlock.SAME_CALL, CallBlock.OTHER_CALL -> stringResource(R.string.call_block_other_call) to null
    CallBlock.BUSY_REPLY -> stringResource(R.string.call_block_busy_reply) to null
    CallBlock.ASR_UNAVAILABLE -> stringResource(R.string.call_block_asr) to R.string.action_go_settings
    CallBlock.CONTACT_ASR_UNAVAILABLE -> stringResource(R.string.call_block_contact_asr) to R.string.call_block_edit_contact
    CallBlock.TTS_UNAVAILABLE -> stringResource(R.string.call_block_tts) to R.string.action_go_settings
    CallBlock.CONTACT_TTS_UNAVAILABLE -> stringResource(R.string.call_block_contact_tts) to R.string.call_block_edit_contact
    CallBlock.NO_API_KEY -> stringResource(R.string.call_block_no_key) to R.string.action_go_settings
    CallBlock.MODEL_CONFIG -> stringResource(R.string.call_block_model_config) to R.string.call_block_edit_contact
    CallBlock.AUDIO_BUSY -> stringResource(R.string.call_block_audio_busy) to null
    CallBlock.MICROPHONE -> stringResource(R.string.call_block_microphone) to null
    CallBlock.NO_CONTACT -> stringResource(R.string.chat_contact_missing) to null
}

@Composable
fun CallContent(ui: CallUi, actions: CallActions) {
    val colors = WeTheme.colors
    val typography = WeTheme.typography
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.callBackground)
            .statusBarsPadding()
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 右上角：字幕开关
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
            val label = stringResource(if (ui.subtitles) R.string.call_subtitles_hide else R.string.call_subtitles_show)
            Text(
                label,
                style = typography.small,
                color = colors.callTextSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.callButton.copy(alpha = 0.12f))
                    .clickable(role = Role.Button) { actions.onSubtitles(!ui.subtitles) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.size(16.dp))
            CallAvatar(ui)
            Spacer(Modifier.size(12.dp))
            Text(ui.name, style = typography.contactName, color = colors.callText, textAlign = TextAlign.Center)
            Spacer(Modifier.size(8.dp))
            Text(ui.status, style = typography.listTitle, color = colors.callTextSecondary, textAlign = TextAlign.Center)
            // 现在在做什么：换状态（不是每秒变的“想了几秒”）时淡入淡出；用户在说时下面跳动的音量条
            AnimatedContent(
                targetState = ui,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) using SizeTransform(clip = false) },
                contentKey = { it.stage to it.muted },
                label = "callPhase",
            ) { state ->
                val phase = state.phase
                if (phase.isNotEmpty()) {
                    Text(
                        phase,
                        style = typography.small,
                        color = if (state.stage == CallPhase.HEARING) colors.brand else colors.callTextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            Box(Modifier.height(28.dp), contentAlignment = Alignment.Center) {
                androidx.compose.animation.AnimatedVisibility(ui.stage == CallPhase.HEARING, enter = fadeIn(), exit = fadeOut()) { LevelBars(colors.brand) }
                androidx.compose.animation.AnimatedVisibility(ui.stage == CallPhase.THINKING || ui.stage == CallPhase.RECOGNIZING, enter = fadeIn(), exit = fadeOut()) {
                    WaitingDots(colors.callTextSecondary)
                }
            }
            // 字幕：一句换下一句时从下面淡入
            AnimatedContent(
                targetState = ui.subtitle,
                transitionSpec = {
                    (fadeIn(tween(260)) + slideInVertically(tween(260)) { it / 4 }) togetherWith fadeOut(tween(140)) using SizeTransform(clip = false)
                },
                contentAlignment = Alignment.Center,
                label = "callSubtitle",
            ) { subtitle ->
                if (subtitle != null) {
                    val (speaker, text) = subtitle
                    Column(
                        Modifier
                            .padding(top = 8.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.callButton.copy(alpha = 0.10f))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(speaker, style = typography.listTime, color = colors.callTextSecondary, textAlign = TextAlign.Center)
                        Spacer(Modifier.size(6.dp))
                        Text(text, style = typography.message, color = colors.callText, textAlign = TextAlign.Center)
                    }
                } else {
                    Spacer(Modifier.fillMaxWidth())
                }
            }
            AnimatedVisibility(
                ui.notice != null,
                enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
            ) {
                // 消失时还要显示原来的字
                var last by remember { androidx.compose.runtime.mutableStateOf(ui.notice.orEmpty()) }
                if (ui.notice != null) last = ui.notice
                val dismiss = stringResource(R.string.call_notice_dismiss)
                Row(
                    Modifier
                        .padding(top = 16.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(colors.callNotice)
                        .clickable(role = Role.Button, onClickLabel = dismiss, onClick = actions.onDismissNotice)
                        .padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(last, style = typography.small, color = colors.callText, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.size(6.dp))
                    Icon(WeIcons.Close, contentDescription = null, tint = colors.callTextSecondary, modifier = Modifier.size(16.dp))
                }
            }
            for (hint in ui.hints) {
                Spacer(Modifier.size(12.dp))
                Text(hint, style = typography.listTime, color = colors.callTextSecondary, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.size(24.dp))
        }
        // 「说完了」「打断」：出现时放大淡入
        Box(Modifier.heightIn(min = 60.dp).padding(bottom = 12.dp), contentAlignment = Alignment.Center) {
            AnimatedContent(
                targetState = ui.action,
                transitionSpec = { (fadeIn(tween(200)) + scaleIn(tween(200), initialScale = 0.85f)) togetherWith (fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.85f)) },
                label = "callAction",
            ) { action ->
                if (action != null) ActionPill(action) { actions.onAction(action) } else Spacer(Modifier.size(1.dp))
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Top,
        ) {
            CallButton(
                icon = if (ui.muted) WeIcons.MicOff else WeIcons.Mic,
                label = stringResource(R.string.call_action_mute),
                on = ui.muted,
                enabled = ui.active,
                modifier = Modifier.weight(1f),
            ) { actions.onMute(!ui.muted) }
            CallButton(
                icon = WeIcons.HangUp,
                label = stringResource(R.string.call_action_hang_up),
                on = false,
                enabled = true,
                danger = true,
                modifier = Modifier.weight(1f),
                onClick = actions.onHangUp,
            )
            CallButton(
                icon = WeIcons.Speaker,
                label = stringResource(R.string.call_action_speaker),
                on = ui.speakerphone,
                enabled = ui.active,
                modifier = Modifier.weight(1f),
            ) { actions.onSpeakerphone(!ui.speakerphone) }
        }
    }
}

/**
 * 头像和它周围的动效：对方在说时一圈圈向外扩散；接通中、对方在想时轻轻呼吸；通话结束后变暗。
 * 系统移除动画时（开发者选项、省电）不跑循环：对方在说时停着两圈，头像不呼吸（0.9.1）。
 */
@Composable
private fun CallAvatar(ui: CallUi) {
    val colors = WeTheme.colors
    val size = 96.dp
    val corner = WeTheme.dimens.avatarCorner
    val ripple by loopingFloat(0f, 1f, infiniteRepeatable(tween(1_800, easing = LinearEasing)), still = 0.3f, label = "ripple")
    val breath by loopingFloat(0f, 1f, infiniteRepeatable(tween(1_400, easing = FastOutSlowInEasing), RepeatMode.Reverse), still = 0f, label = "breath")
    val speaking = ui.stage == CallPhase.SPEAKING
    val breathing = ui.stage == null || ui.stage == CallPhase.THINKING
    val ringAlpha by animateFloatAsState(if (speaking) 1f else 0f, tween(300), label = "ringAlpha")
    val dim by animateFloatAsState(if (ui.stage == CallPhase.ENDED) 0.45f else 1f, tween(400), label = "avatarDim")
    val ringColor = colors.callText
    Box(
        Modifier
            .size(size + 72.dp)
            .drawBehind {
                if (ringAlpha <= 0f) return@drawBehind
                val base = size.toPx()
                val radius = corner.toPx()
                for (offset in listOf(0f, 0.5f)) {
                    val p = (ripple + offset) % 1f
                    val grow = base * (1f + 0.7f * p)
                    drawRoundRect(
                        color = ringColor.copy(alpha = 0.32f * (1f - p) * ringAlpha),
                        topLeft = androidx.compose.ui.geometry.Offset((this.size.width - grow) / 2, (this.size.height - grow) / 2),
                        size = androidx.compose.ui.geometry.Size(grow, grow),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius * (1f + 0.7f * p)),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val scale = if (breathing) 1f + 0.035f * breath else 1f
        Avatar(
            ui.avatarPath,
            ui.name,
            ui.avatarColor,
            size,
            modifier = Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = dim
            },
        )
    }
}

/** 用户在说：五根高低起伏的绿色竖条 */
@Composable
private fun LevelBars(color: Color) {
    val t by loopingFloat(0f, 1f, infiniteRepeatable(tween(1_000, easing = LinearEasing)), still = 0.1f, label = "levelPhase")
    Canvas(Modifier.size(width = 44.dp, height = 20.dp).clearAndSetSemantics {}) {
        val bar = 4.dp.toPx()
        val gap = (size.width - bar * 5) / 4
        for (i in 0 until 5) {
            val wave = (kotlin.math.sin((t + i * 0.18f) * 2 * kotlin.math.PI) + 1) / 2
            val h = size.height * (0.3f + 0.7f * wave.toFloat())
            drawRoundRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(i * (bar + gap), (size.height - h) / 2),
                size = androidx.compose.ui.geometry.Size(bar, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(bar / 2),
            )
        }
    }
}

/** 正在识别、对方在想：三个依次亮起的点 */
@Composable
private fun WaitingDots(color: Color) {
    val t by loopingFloat(0f, 3f, infiniteRepeatable(tween(1_200, easing = LinearEasing)), still = 0.5f, label = "dotsPhase")
    Canvas(Modifier.size(width = 36.dp, height = 12.dp).clearAndSetSemantics {}) {
        val r = 3.dp.toPx()
        val gap = (size.width - r * 6) / 2
        for (i in 0 until 3) {
            val distance = kotlin.math.abs(((t - i) % 3 + 3) % 3 - 0.5f)
            val alpha = (1f - distance.coerceAtMost(1.5f) / 1.5f) * 0.75f + 0.25f
            drawCircle(color.copy(alpha = alpha), radius = r, center = androidx.compose.ui.geometry.Offset(r + i * (2 * r + gap), size.height / 2))
        }
    }
}

/** 「说完了」（绿色，用户在说时）/「打断」（白色描边，对方在说时） */
@Composable
private fun ActionPill(action: CallAction, onClick: () -> Unit) {
    val colors = WeTheme.colors
    val finish = action == CallAction.FINISH
    val label = stringResource(if (finish) R.string.call_action_finish else R.string.call_action_interrupt)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "actionPress")
    Text(
        label,
        style = WeTheme.typography.button,
        color = if (finish) Color.White else colors.callText,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(24.dp))
            .background(if (finish) colors.brand else colors.callButton)
            .then(if (finish) Modifier else Modifier.border(1.dp, colors.callTextSecondary.copy(alpha = 0.5f), RoundedCornerShape(24.dp)))
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 32.dp, vertical = 12.dp),
    )
}

/** 圆形按钮和下面的文字；[on] 时亮起（静音、免提开着），[danger] 是红色的挂断。按下时缩一下，开关时颜色渐变 */
@Composable
private fun CallButton(
    icon: ImageVector,
    label: String,
    on: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = WeTheme.colors
    val state = if (danger) "" else stringResource(if (on) R.string.call_state_on else R.string.call_state_off)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, label = "callButtonPress")
    val background by animateColorAsState(
        when {
            danger -> if (pressed) colors.callHangUpPressed else colors.callHangUp
            on -> colors.callButtonOn
            else -> colors.callButton
        },
        tween(180),
        label = "callButtonColor",
    )
    val tint by animateColorAsState(
        when {
            danger -> colors.callText
            on -> colors.callButtonOnIcon
            enabled -> colors.callText
            else -> colors.callTextSecondary
        },
        tween(180),
        label = "callButtonTint",
    )
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = label
                if (state.isNotEmpty()) stateDescription = state
            }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(64.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(CircleShape)
                .background(background),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.size(8.dp))
        Text(label, style = WeTheme.typography.small, color = colors.callTextSecondary, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 120.dp))
    }
}

private const val ENDED_CLOSE_MILLIS = 1_500L
