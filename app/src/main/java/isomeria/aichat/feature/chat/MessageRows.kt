package isomeria.aichat.feature.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.PhoneInTalk
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import isomeria.aichat.R
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.Role
import isomeria.aichat.ui.components.Avatar
import isomeria.aichat.ui.components.MenuItem
import isomeria.aichat.ui.components.TextBubble
import isomeria.aichat.ui.components.UnplayedDot
import isomeria.aichat.ui.components.VoiceBubble
import isomeria.aichat.data.voice.PlayMode
import isomeria.aichat.ui.components.WeBubbleMenu
import isomeria.aichat.ui.components.WeIcons
import isomeria.aichat.ui.theme.WeTheme
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** 聊天页时间条 */
@Composable
fun TimeBarRow(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        // 设了聊天背景时时间垫一层半透明底，图片花哨也看得清
        val onImage = LocalChatHasBackground.current
        Text(
            text,
            style = WeTheme.typography.small,
            color = WeTheme.colors.chatTimeText,
            modifier = if (onImage) {
                Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(WeTheme.colors.pageBackground.copy(alpha = 0.85f))
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            } else {
                Modifier
            },
        )
    }
}

/** 当前聊天有没有设背景；时间条等直接画在背景上的文字据此加底 */
val LocalChatHasBackground = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * 当前聊天背景：铺满消息区、居中裁剪。深色模式压暗一些，不刺眼，也不抢气泡。
 * 图片解码失败时什么也不画，露出默认背景。
 */
@Composable
fun ChatBackground(file: java.io.File, modifier: Modifier = Modifier) {
    ChatBackgroundFrame(modifier) {
        AsyncImage(
            model = file,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )
    }
}

/** 背景图外层：图片铺满，深色模式加一层压暗。截图测试直接传位图进来 */
@Composable
internal fun ChatBackgroundFrame(modifier: Modifier = Modifier, image: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    Box(modifier) {
        image()
        if (WeTheme.colors.isDark) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.35f)))
    }
}

data class MessageActions(
    val onCopy: (String) -> Unit,
    val onDelete: (Message) -> Unit,
    val onRegenerate: () -> Unit,
    val onCancelReply: () -> Unit,
    val onFailedClick: (Message) -> Unit,
    val onImageClick: (String) -> Unit,
    val onAvatarClick: () -> Unit,
    /** 点语音条：播放或停止（0.7.0） */
    val onVoiceClick: (Message) -> Unit = {},
    /** 长按菜单里的听筒播放、后台播放、连续朗读 */
    val onVoicePlay: (Message, PlayMode) -> Unit = { _, _ -> },
    /** 转文字 / 取消转文字 */
    val onVoiceShowText: (Message, Boolean) -> Unit = { _, _ -> },
)

/**
 * 一条消息。自己的在右、绿色气泡；联系人的在左、白色（深色模式深灰）气泡。
 * 图片本身就是消息，没有气泡底色。
 */
@Composable
fun MessageRow(
    message: Message,
    contactName: String,
    contactAvatarPath: String?,
    contactAvatarColor: Int,
    myName: String,
    myAvatarPath: String?,
    myAvatarColor: Int,
    canRegenerate: Boolean,
    waitingReply: Boolean,
    maxBubbleWidth: Dp,
    actions: MessageActions,
    playing: Boolean = false,
    menuInitiallyOpen: Boolean = false,
) {
    val dimens = WeTheme.dimens
    val sent = message.role == Role.USER
    var menuOpen by remember { mutableStateOf(menuInitiallyOpen) }

    val voice = message.type == MessageType.VOICE
    // 语音转写的文字：用户的语音识别成功前为空
    val hasText = message.content.isNotBlank()
    val menuItems = buildList {
        if (message.type == MessageType.TEXT || (voice && hasText)) {
            add(MenuItem(stringResource(R.string.action_copy), Icons.Outlined.ContentCopy) { actions.onCopy(message.content) })
        }
        add(MenuItem(stringResource(R.string.action_delete), Icons.Outlined.DeleteOutline) { actions.onDelete(message) })
        if (voice) {
            add(MenuItem(stringResource(R.string.voice_menu_earpiece), Icons.Outlined.PhoneInTalk) { actions.onVoicePlay(message, PlayMode.EARPIECE) })
            add(MenuItem(stringResource(R.string.voice_menu_background), Icons.Outlined.Headphones) { actions.onVoicePlay(message, PlayMode.BACKGROUND) })
            if (hasText) {
                add(
                    MenuItem(
                        stringResource(if (message.voiceShowText) R.string.voice_menu_hide_text else R.string.voice_menu_to_text),
                        Icons.Outlined.TextFields,
                    ) { actions.onVoiceShowText(message, !message.voiceShowText) },
                )
            }
            add(MenuItem(stringResource(R.string.voice_menu_continuous), Icons.AutoMirrored.Outlined.PlaylistPlay) { actions.onVoicePlay(message, PlayMode.CONTINUOUS) })
        }
        if (canRegenerate) {
            add(MenuItem(stringResource(R.string.chat_menu_regenerate), Icons.Outlined.Refresh, actions.onRegenerate))
        }
        if (waitingReply) {
            add(MenuItem(stringResource(R.string.chat_menu_cancel_reply), Icons.Outlined.Block, actions.onCancelReply))
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = dimens.chatAvatarEdge, vertical = dimens.messageSpacing / 2),
        horizontalArrangement = if (sent) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!sent) {
            Avatar(
                contactAvatarPath,
                contactName,
                contactAvatarColor,
                dimens.chatAvatar,
                Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = actions.onAvatarClick,
                ),
            )
            Spacer(Modifier.width(dimens.bubbleAvatarGap - dimens.bubbleTailWidth))
        } else {
            Spacer(Modifier.width(dimens.bubbleOppositeMinGap - dimens.chatAvatarEdge))
            if (message.status == MessageStatus.FAILED) {
                FailedMark(Modifier.align(Alignment.CenterVertically)) { actions.onFailedClick(message) }
                Spacer(Modifier.width(8.dp))
            }
        }

        Column(
            Modifier.weight(1f, fill = false).widthIn(max = maxBubbleWidth),
            horizontalAlignment = if (sent) Alignment.End else Alignment.Start,
        ) {
            if (!sent && !message.reasoning.isNullOrBlank()) {
                ThinkingBlock(message.reasoning, message.reasoningMillis)
                Spacer(Modifier.size(4.dp))
            }
            Box {
                when (message.type) {
                    MessageType.TEXT -> TextBubble(
                        text = message.content,
                        sent = sent,
                        onLongClick = { menuOpen = true },
                    )
                    MessageType.IMAGE -> ImageMessage(
                        message = message,
                        sent = sent,
                        onClick = { message.imagePath?.let(actions.onImageClick) },
                        onLongClick = { menuOpen = true },
                    )
                    MessageType.VOICE -> VoiceMessage(
                        message = message,
                        sent = sent,
                        playing = playing,
                        onClick = { actions.onVoiceClick(message) },
                        onLongClick = { menuOpen = true },
                        onToText = { actions.onVoiceShowText(message, true) },
                    )
                }
                if (menuOpen) WeBubbleMenu(menuItems) { menuOpen = false }
            }
            if (voice) VoiceTranscript(message, sent) { menuOpen = true }
        }

        if (sent) {
            Spacer(Modifier.width(dimens.bubbleAvatarGap - dimens.bubbleTailWidth))
            Avatar(myAvatarPath, myName, myAvatarColor, dimens.chatAvatar)
        } else {
            Spacer(Modifier.width(dimens.bubbleOppositeMinGap - dimens.chatAvatarEdge))
        }
    }
}

/**
 * 语音消息（0.7.0）：语音条；对方没听过的旁边有红点；有转写文字、还没展开时旁边有「转文字」。
 * 自己的语音在识别出文字前不显示「转文字」（下面显示「正在识别…」）。
 */
@Composable
private fun VoiceMessage(message: Message, sent: Boolean, playing: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, onToText: () -> Unit) {
    val unplayed = !sent && !message.voicePlayed
    val toText = @Composable {
        if (message.content.isNotBlank() && !message.voiceShowText) {
            Text(
                stringResource(R.string.voice_menu_to_text),
                style = WeTheme.typography.small,
                color = WeTheme.colors.link,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onToText)
                    .padding(horizontal = 6.dp, vertical = 10.dp),
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (sent) toText()
        VoiceBubble(
            millis = message.voiceMillis,
            sent = sent,
            playing = playing,
            unplayed = unplayed,
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (!sent) {
            if (unplayed) {
                Spacer(Modifier.width(6.dp))
                UnplayedDot()
            }
            toText()
        }
    }
}

/** 语音下面：展开的转写文字，或自己的语音正在识别 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VoiceTranscript(message: Message, sent: Boolean, onLongClick: () -> Unit) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    val side = Modifier.padding(start = if (sent) 0.dp else dimens.bubbleTailWidth, end = if (sent) dimens.bubbleTailWidth else 0.dp, top = 4.dp)
    when {
        message.voiceShowText && message.content.isNotBlank() -> {
            val description = stringResource(R.string.voice_text_desc, message.content)
            Text(
                message.content,
                style = WeTheme.typography.message,
                color = if (sent) colors.bubbleSentText else colors.bubbleReceivedText,
                modifier = side
                    .clip(RoundedCornerShape(dimens.bubbleCorner))
                    .background(if (sent) colors.bubbleSent else colors.bubbleReceived)
                    .combinedClickable(onClick = {}, onLongClick = onLongClick)
                    .semantics { contentDescription = description }
                    .padding(horizontal = dimens.bubblePaddingHorizontal, vertical = dimens.bubblePaddingVertical),
            )
        }
        sent && message.content.isBlank() && message.status == MessageStatus.SENDING -> {
            Text(stringResource(R.string.voice_transcribing), style = WeTheme.typography.small, color = colors.textSecondary, modifier = side)
        }
    }
}

/** 发送失败的红色感叹号，点击重试 */
@Composable
private fun FailedMark(modifier: Modifier, onClick: () -> Unit) {
    val colors = WeTheme.colors
    val description = stringResource(R.string.chat_failed_mark)
    Box(
        modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(colors.badge)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text("!", color = Color.White, style = WeTheme.typography.badge)
    }
}

/** 开启思考的联系人：回复上方可折叠的灰色区域「已深度思考（用时 N 秒）」，默认折叠 */
@Composable
private fun ThinkingBlock(reasoning: String, millis: Long) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    var expanded by rememberSaveable(reasoning) { mutableStateOf(false) }
    val expandedText = stringResource(if (expanded) R.string.state_expanded else R.string.state_collapsed)
    Column(
        Modifier
            .padding(start = dimens.bubbleTailWidth)
            .clip(RoundedCornerShape(dimens.bubbleCorner))
            .background(colors.thinkingBackground)
            .clickable(role = androidx.compose.ui.semantics.Role.Button) { expanded = !expanded }
            .semantics { stateDescription = expandedText }
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .animateContentSize(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.chat_thinking_done, max(1, (millis / 1000.0).roundToInt())),
                style = WeTheme.typography.small,
                color = colors.thinkingText,
            )
            Icon(
                if (expanded) WeIcons.ChevronUp else WeIcons.ChevronDown,
                null,
                tint = colors.thinkingText,
                modifier = Modifier.padding(start = 2.dp).size(16.dp),
            )
        }
        if (expanded) {
            Text(
                reasoning,
                style = WeTheme.typography.thinking,
                color = colors.thinkingText,
                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
            )
        }
    }
}

/** 图片消息：按原比例缩成缩略图，长边最多 140dp，圆角 4dp */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageMessage(message: Message, sent: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val dimens = WeTheme.dimens
    val maxSide = dimens.imageMaxSide
    val w = message.imageWidth.coerceAtLeast(1).toFloat()
    val h = message.imageHeight.coerceAtLeast(1).toFloat()
    val minSide = 48.dp
    val (width, height) = if (w >= h) {
        maxSide to (maxSide * (h / w)).coerceAtLeast(minSide)
    } else {
        (maxSide * (w / h)).coerceAtLeast(minSide) to maxSide
    }
    AsyncImage(
        model = message.imagePath?.let(::File),
        contentDescription = stringResource(R.string.preview_image),
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .padding(start = if (sent) 0.dp else dimens.bubbleTailWidth, end = if (sent) dimens.bubbleTailWidth else 0.dp)
            .size(width, height)
            .clip(RoundedCornerShape(dimens.imageCorner))
            .background(WeTheme.colors.listPressed)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    )
}
