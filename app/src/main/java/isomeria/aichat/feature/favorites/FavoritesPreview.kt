package isomeria.aichat.feature.favorites

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import isomeria.aichat.R
import isomeria.aichat.core.ChatTimeFormatter
import isomeria.aichat.core.TimeLabels
import isomeria.aichat.data.db.CallTurnStatus
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.Favorite
import isomeria.aichat.data.db.FavoriteTurn
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.Role
import isomeria.aichat.data.repo.FavoriteEntry
import isomeria.aichat.feature.call.CallDetailContent
import isomeria.aichat.feature.call.CallInfo
import isomeria.aichat.feature.call.CallPlayerUi
import isomeria.aichat.ui.theme.AvatarPalette
import isomeria.aichat.ui.theme.AIChatTheme

/** 收藏页、收藏详情的静态外观（0.9.0）：Compose Preview 和截图测试共用，虚构数据 */
private const val SAMPLE_NOW = 1_775_000_000_000L
private const val DAY = 86_400_000L

private val sampleSource = Contact(id = 1, name = "小雨", avatarColor = AvatarPalette[0], providerId = 1, model = "m", createdAt = 1, updatedAt = 1)

private fun favorite(id: Long, type: MessageType, role: Role, content: String = "", audio: String? = null, millis: Long = 0, daysAgo: Long = 0) = Favorite(
    id = id, type = type, role = role, contactId = 1, contactCreatedAt = 1, contactName = "小雨", contactAvatarColor = AvatarPalette[0],
    content = content, audioPath = audio, audioMillis = millis, messageId = id, messageCreatedAt = SAMPLE_NOW - daysAgo * DAY - 3_600_000,
    createdAt = SAMPLE_NOW - daysAgo * DAY,
)

private val sampleCall = FavoriteEntry(
    favorite(3, MessageType.CALL, Role.USER, millis = 201_000, daysAgo = 2),
    sampleSource,
    listOf(
        FavoriteTurn(id = 1, favoriteId = 3, seq = 0, role = Role.USER, text = "明天几点出发？", createdAt = 1),
        FavoriteTurn(id = 2, favoriteId = 3, seq = 1, role = Role.ASSISTANT, text = "七点吧，早点去人少", audioPath = "/f/a.m4a", audioMillis = 2_000, createdAt = 2),
        FavoriteTurn(id = 3, favoriteId = 3, seq = 2, role = Role.USER, text = "", status = CallTurnStatus.ASR_FAILED, createdAt = 3),
        FavoriteTurn(id = 4, favoriteId = 3, seq = 3, role = Role.ASSISTANT, text = "那我们车站见", audioPath = "/f/b.m4a", audioMillis = 1_500, createdAt = 4),
    ),
)

private val sampleEntries = listOf(
    FavoriteEntry(favorite(1, MessageType.TEXT, Role.ASSISTANT, "明天早上七点出发，记得带水和外套，山上风大。到了山顶我们拍张合照吧！"), sampleSource, emptyList()),
    FavoriteEntry(favorite(2, MessageType.VOICE, Role.USER, "好的，我再带点吃的", audio = "/f/v.m4a", millis = 4_000, daysAgo = 1), null, emptyList()),
    sampleCall,
    FavoriteEntry(favorite(4, MessageType.TEXT, Role.USER, "下次去看海", daysAgo = 30).copy(contactName = "阿哲", contactAvatarColor = AvatarPalette[3]), null, emptyList()),
)

@Composable
private fun sampleFormatter() = ChatTimeFormatter(
    TimeLabels(
        yesterday = stringResource(R.string.time_yesterday),
        weekdays = stringArrayResource(R.array.weekdays).toList(),
        monthDay = stringResource(R.string.time_month_day),
        yearMonthDay = stringResource(R.string.time_year_month_day),
    ),
)

@Composable
internal fun FavoritesSample(empty: Boolean = false, query: String = "") {
    FavoritesContent(
        entries = when {
            empty -> emptyList()
            query.isNotEmpty() -> sampleEntries.filter { matches(it, query) }
            else -> sampleEntries
        },
        query = query, searching = query.isNotEmpty(), playingId = null, formatter = sampleFormatter(),
        onBack = {}, onStartSearch = {}, onQueryChange = {}, onCancelSearch = {}, onOpen = {}, onToggleVoice = {}, onCopy = {}, onDelete = {},
        now = SAMPLE_NOW,
    )
}

@Composable
internal fun FavoriteDetailSample(call: Boolean = false) {
    val formatter = sampleFormatter()
    if (call) {
        CallDetailContent(
            detail = callDetailOf(sampleCall),
            info = CallInfo(formatter.forChat(sampleCall.favorite.messageCreatedAt, SAMPLE_NOW), "03:21", null),
            player = CallPlayerUi(total = 3_500),
            playable = setOf(2L, 4L),
            onBack = {}, onToggleTurn = {}, onTogglePlay = {}, onSeek = {},
            title = stringResource(R.string.favorite_detail_title),
        )
    } else {
        val entry = sampleEntries.first()
        FavoriteMessageDetail(
            entry = entry,
            time = formatter.forChat(entry.favorite.messageCreatedAt, SAMPLE_NOW),
            savedAt = formatter.forChat(entry.favorite.createdAt, SAMPLE_NOW),
            playing = false,
            onBack = {},
            onToggleVoice = {},
        )
    }
}

@Preview(name = "收藏 浅色", showBackground = true, widthDp = 400, heightDp = 860)
@Composable
private fun FavoritesPreview() {
    AIChatTheme(darkTheme = false) { FavoritesSample() }
}
