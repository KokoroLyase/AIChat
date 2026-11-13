package isomeria.aichat.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import isomeria.aichat.R
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.Role
import isomeria.aichat.data.repo.FailReason
import isomeria.aichat.ui.components.ChatInputBar
import isomeria.aichat.ui.components.RecordingOverlay
import isomeria.aichat.ui.components.InputPanel
import isomeria.aichat.ui.components.TopBarIcon
import isomeria.aichat.ui.components.WeIcons
import isomeria.aichat.ui.components.WeTopBar
import isomeria.aichat.ui.theme.AIChatTheme
import isomeria.aichat.ui.theme.AvatarPalette
import isomeria.aichat.ui.theme.WeTheme

internal val sampleContact = Contact(
    id = 1,
    name = "小雨",
    avatarColor = AvatarPalette[0],
    providerId = 1,
    model = "deepseek-flash",
    createdAt = 0,
    updatedAt = 0,
)

private fun msg(id: Long, role: Role, text: String, reasoning: String? = null, status: MessageStatus = MessageStatus.SUCCESS) =
    Message(
        id = id,
        contactId = 1,
        role = role,
        type = MessageType.TEXT,
        content = text,
        reasoning = reasoning,
        reasoningMillis = if (reasoning != null) 6_000 else 0,
        status = status,
        errorCode = if (status == MessageStatus.FAILED) FailReason.NETWORK.code else 0,
        createdAt = id,
    )

internal val sampleRows: List<ChatRow> = listOf(
    ChatRow.TimeBar("昨天 22:41", "t0"),
    ChatRow.Item(msg(1, Role.USER, "今天加班到好晚，好累")),
    ChatRow.Item(msg(2, Role.ASSISTANT, "辛苦啦，到家了吗？")),
    ChatRow.Item(msg(3, Role.ASSISTANT, "记得吃点东西再睡，别空着肚子")),
    ChatRow.TimeBar("21:30", "t1"),
    ChatRow.Item(msg(4, Role.USER, "推荐一部轻松点的电影吧")),
    ChatRow.Item(
        msg(5, Role.ASSISTANT, "《海蒂和爷爷》怎么样？画面很治愈，节奏也慢，适合放松。豆瓣页面：https://movie.douban.com", reasoning = "对方累了，想看轻松的……"),
        canRegenerate = true,
    ),
    ChatRow.Item(msg(6, Role.USER, "好呀，周末一起看", status = MessageStatus.FAILED)),
)

private fun voice(id: Long, role: Role, seconds: Int, text: String, played: Boolean = true, showText: Boolean = false, status: MessageStatus = MessageStatus.SUCCESS) =
    Message(
        id = id,
        contactId = 1,
        role = role,
        type = MessageType.VOICE,
        content = text,
        voicePath = "voice/$id.m4a",
        voiceMillis = seconds * 1_000L,
        voicePlayed = played,
        voiceShowText = showText,
        status = status,
        errorCode = if (status == MessageStatus.FAILED) FailReason.ASR_FAILED.code else 0,
        createdAt = id,
    )

/** 语音消息（0.7.0）：自己的转了文字、对方没听过（红点）、正在播放、长语音转了文字、正在识别、识别失败 */
internal val voiceRows: List<ChatRow> = listOf(
    ChatRow.TimeBar("21:30", "t0"),
    ChatRow.Item(voice(11, Role.USER, 6, "今天下雨了，你那边呢", showText = true)),
    ChatRow.Item(voice(12, Role.ASSISTANT, 3, "这边也在下", played = false)),
    ChatRow.Item(voice(13, Role.ASSISTANT, 15, "我刚到家，衣服都淋湿了")),
    ChatRow.Item(voice(14, Role.ASSISTANT, 42, "你记得带伞呀，明天好像还要下一整天，出门前看看天气预报，别又淋湿了。", showText = true)),
    ChatRow.Item(voice(15, Role.USER, 2, "", status = MessageStatus.FAILED)),
    ChatRow.Item(voice(16, Role.USER, 9, "", status = MessageStatus.SENDING)),
)

/** 通话消息（0.8.0）：打完的写时长，正在打的写「通话中」，和前后的文字消息放在一起 */
internal val callRows: List<ChatRow> = listOf(
    ChatRow.TimeBar("21:30", "t0"),
    ChatRow.Item(msg(21, Role.USER, "晚上有空吗")),
    ChatRow.Item(msg(22, Role.ASSISTANT, "有呀，打给我吧")),
    ChatRow.Item(Message(id = 23, contactId = 1, role = Role.USER, type = MessageType.CALL, status = MessageStatus.SUCCESS, voiceMillis = 201_000, createdAt = 23)),
    ChatRow.TimeBar("22:05", "t1"),
    ChatRow.Item(Message(id = 24, contactId = 1, role = Role.USER, type = MessageType.CALL, status = MessageStatus.SUCCESS, createdAt = 24)),
)

/** 多选（0.9.0）：文字、语音、图片、通话可以选，等待回复中的不能选 */
internal val selectRows: List<ChatRow> = listOf(
    ChatRow.TimeBar("21:30", "t0"),
    ChatRow.Item(msg(31, Role.USER, "周末去爬山吗")),
    ChatRow.Item(msg(32, Role.ASSISTANT, "好呀，去哪座山")),
    ChatRow.Item(voice(33, Role.ASSISTANT, 6, "要不要去香山，听说红叶正好")),
    ChatRow.Item(Message(id = 34, contactId = 1, role = Role.USER, type = MessageType.CALL, status = MessageStatus.SUCCESS, voiceMillis = 201_000, createdAt = 34)),
    ChatRow.Item(msg(35, Role.USER, "那就这么定了", status = MessageStatus.SENDING)),
)

/** 引用（0.9.0）：引用对方的文字、引用自己的语音，下面是引用块 */
internal val quoteRows: List<ChatRow> = listOf(
    ChatRow.TimeBar("21:30", "t0"),
    ChatRow.Item(msg(41, Role.ASSISTANT, "明天早上七点出发，记得带水和外套，山上风大")),
    ChatRow.Item(voice(42, Role.USER, 4, "好的，我再带点吃的")),
    ChatRow.Item(
        msg(43, Role.USER, "七点会不会太早了").copy(quoteId = 41, quoteRole = Role.ASSISTANT, quoteType = MessageType.TEXT, quoteText = "明天早上七点出发，记得带水和外套，山上风大"),
    ),
    ChatRow.Item(
        msg(44, Role.ASSISTANT, "带吃的好，我带水果").copy(quoteId = 42, quoteRole = Role.USER, quoteType = MessageType.VOICE, quoteText = "好的，我再带点吃的"),
    ),
)

/** 翻译（0.9.0）：显示译文、正在翻译、翻译失败 */
internal val translateRows: List<ChatRow> = listOf(
    ChatRow.TimeBar("21:30", "t0"),
    ChatRow.Item(msg(51, Role.ASSISTANT, "See you at the station tomorrow!").copy(translation = "明天车站见！", translationShown = true)),
    ChatRow.Item(msg(52, Role.USER, "好的，我会准时到").copy(translation = "OK, I'll be there on time.", translationShown = true)),
    ChatRow.Item(msg(53, Role.ASSISTANT, "Don't forget your umbrella.")),
    ChatRow.Item(msg(54, Role.ASSISTANT, "It might rain in the afternoon.")),
)

internal val translateStates: Map<Long, TranslationUi> = mapOf(53L to TranslationUi.Loading, 54L to TranslationUi.Failed(isomeria.aichat.data.repo.FailReason.NETWORK))

/** 截图里的长按菜单和真实聊天页一样有“引用”（不在通话中） */
private val noActions = MessageActions({}, {}, {}, {}, {}, {}, {}, onQuote = {})

/** 聊天页的静态外观：顶栏、消息、输入栏。Compose Preview 和截图测试共用。 */
@Composable
internal fun ChatPageSample(
    typing: Boolean = false,
    input: String = "",
    /** 聊天背景：截图测试传入固定位图 */
    background: androidx.compose.ui.graphics.ImageBitmap? = null,
    /** 语音（0.7.0）：输入栏是「按住 说话」、正在录音的浮层、录音转文字正在识别 */
    voiceMode: Boolean = false,
    recording: isomeria.aichat.ui.components.RecordingUi? = null,
    transcribing: Boolean = false,
    rows: List<ChatRow> = sampleRows,
    /** 正在播放的语音、打开长按菜单的消息 */
    playingId: Long? = null,
    menuOpenId: Long? = null,
    /** 多选（0.9.0）：选中的消息；不为 null 时顶栏是“已选 N 条”，输入栏换成底部操作栏 */
    selection: Set<Long>? = null,
    /** 引用条（0.9.0）的文字 */
    quote: String? = null,
    /** 正在翻译、翻译失败的消息（0.9.0） */
    translating: Map<Long, TranslationUi> = emptyMap(),
) {
    Column(Modifier.fillMaxSize().background(WeTheme.colors.pageBackground)) {
        if (selection != null) {
            WeTopBar(
                title = if (selection.isEmpty()) stringResource(R.string.chat_selected_none) else stringResource(R.string.chat_selected_count, selection.size),
                showLine = true,
            ) {
                isomeria.aichat.ui.components.TopBarTextAction(stringResource(R.string.action_cancel), {})
            }
        } else {
            WeTopBar(
                title = if (typing) stringResource(R.string.chat_typing) else sampleContact.name,
                onBack = {},
                showLine = true,
            ) {
                TopBarIcon(WeIcons.More, "", {})
            }
        }
        androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
            if (background != null) {
                ChatBackgroundFrame(Modifier.matchParentSize()) {
                    androidx.compose.foundation.Image(
                        background,
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.matchParentSize(),
                    )
                }
            }
            androidx.compose.runtime.CompositionLocalProvider(LocalChatHasBackground provides (background != null)) {
                ChatMessageList(
                    rows = rows,
                    contact = sampleContact,
                    myName = "我",
                    myAvatarPath = null,
                    waitingReply = typing,
                    actions = noActions,
                    playingId = playingId,
                    menuOpenId = menuOpenId,
                    selection = selection,
                    translating = translating,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            recording?.let { RecordingOverlay(it, 60_000, {}, {}, {}) }
        }
        if (selection != null) {
            val any = selection.isNotEmpty()
            isomeria.aichat.ui.components.SelectionBar(
                listOf(
                    isomeria.aichat.ui.components.SelectionAction(stringResource(R.string.action_copy), WeIcons.Copy, any) {},
                    isomeria.aichat.ui.components.SelectionAction(stringResource(R.string.chat_menu_favorite), WeIcons.Star, any) {},
                    isomeria.aichat.ui.components.SelectionAction(stringResource(R.string.action_delete), WeIcons.Delete, any, danger = true) {},
                ),
            )
            return@Column
        }
        quote?.let { isomeria.aichat.ui.components.QuoteBar(it, onClose = {}) }
        ChatInputBar(
            value = TextFieldValue(input),
            onValueChange = {},
            voiceMode = voiceMode,
            panel = InputPanel.NONE,
            onToggleVoice = {},
            onTogglePanel = {},
            onSend = {},
            onInputFocused = {},
            focusRequester = remember { FocusRequester() },
            voice = isomeria.aichat.ui.components.VoiceInputActions(recording = recording, transcribing = transcribing),
        )
    }
}

@Preview(name = "聊天页 浅色", showBackground = true, widthDp = 400, heightDp = 860)
@Composable
private fun ChatLightPreview() {
    AIChatTheme(darkTheme = false) { ChatPageSample() }
}

@Preview(name = "聊天页 深色", showBackground = true, widthDp = 400, heightDp = 860)
@Composable
private fun ChatDarkPreview() {
    AIChatTheme(darkTheme = true) { ChatPageSample(input = "好的") }
}
