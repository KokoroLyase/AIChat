package isomeria.aichat.ui.components

import android.content.Context
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import isomeria.aichat.R
import isomeria.aichat.ui.theme.WeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 一组 emoji，如「笑脸与情感」 */
data class EmojiGroup(val key: String, val emojis: List<String>)

/**
 * 系统 emoji：assets/emoji.txt 是由 Unicode 官方列表生成的全部标准 emoji（见 tools/generate_emoji.py），
 * 加载时用 [Paint.hasGlyph] 去掉系统字体画不出来的，显示的就是本机系统自带的 emoji。
 */
object EmojiCatalog {
    private const val ASSET = "emoji.txt"

    @Volatile
    private var cached: List<EmojiGroup>? = null

    suspend fun load(context: Context): List<EmojiGroup> = cached ?: withContext(Dispatchers.Default) {
        val paint = Paint()
        context.assets.open(ASSET).bufferedReader().useLines { parse(it, paint::hasGlyph) }
    }.also { cached = it }

    fun parse(lines: Sequence<String>, supported: (String) -> Boolean): List<EmojiGroup> = lines
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) return@mapNotNull null
            val emojis = line.substring(tab + 1).split(' ').filter { it.isNotEmpty() && supported(it) }
            if (emojis.isEmpty()) null else EmojiGroup(line.substring(0, tab), emojis)
        }
        .toList()
}

/** 「最近使用」最多保留一行 */
const val RECENT_EMOJI_LIMIT = 8

private const val RECENT_KEY = "recent"

private data class EmojiSection(val key: String, val title: String, val icon: String?, val headerIndex: Int)

/**
 * 表情面板，样式参照微信：上面是可上下滑动的表情网格（「最近使用」在最前，然后按分类），
 * 右下角浮着「删除」和「发送」两个键，底部一排分类标签，点击跳到对应分类。
 * 删除键按住不放会连续删除。
 */
@Composable
fun EmojiPanel(
    groups: List<EmojiGroup>?,
    recent: List<String>,
    canSend: Boolean,
    canDelete: Boolean,
    onPick: (String) -> Unit,
    onDelete: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val recentTitle = stringResource(R.string.emoji_recent)
    val titles = groupTitles()
    val sections = remember(groups, recent, titles) {
        buildList {
            var index = 0
            if (recent.isNotEmpty()) {
                add(EmojiSection(RECENT_KEY, recentTitle, null, index))
                index += 1 + recent.size
            }
            groups.orEmpty().forEach { group ->
                add(EmojiSection(group.key, titles[group.key] ?: group.key, groupIcon(group), index))
                index += 1 + group.emojis.size
            }
        }
    }
    val selected by remember(sections) {
        derivedStateOf { sections.indexOfLast { it.headerIndex <= gridState.firstVisibleItemIndex }.coerceAtLeast(0) }
    }

    Column(modifier.fillMaxWidth().background(colors.barBackground)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(dimens.emojiColumns),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                // 底部留出浮动按键的高度，最后一行能滑到按键上方
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = dimens.emojiKeyHeight + 24.dp),
            ) {
                if (recent.isNotEmpty()) {
                    header(RECENT_KEY, recentTitle)
                    items(recent, key = { "$RECENT_KEY:$it" }, contentType = { CONTENT_EMOJI }) { EmojiCell(it, onPick) }
                }
                groups?.forEach { group ->
                    header(group.key, titles[group.key] ?: group.key)
                    items(group.emojis, key = { "${group.key}:$it" }, contentType = { CONTENT_EMOJI }) { EmojiCell(it, onPick) }
                }
            }
            FloatingKeys(
                canDelete = canDelete,
                canSend = canSend,
                onDelete = onDelete,
                onSend = onSend,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
        EmojiTabs(
            sections = sections,
            selected = selected,
            onSelect = { scope.launch { gridState.scrollToItem(sections[it].headerIndex) } },
        )
    }
}

private const val CONTENT_HEADER = 0
private const val CONTENT_EMOJI = 1

private fun LazyGridScope.header(key: String, title: String) {
    item(key = "header:$key", span = { GridItemSpan(maxLineSpan) }, contentType = CONTENT_HEADER) {
        Text(
            title,
            style = WeTheme.typography.listTime,
            color = WeTheme.colors.textTertiary,
            modifier = Modifier.padding(start = 8.dp, top = 14.dp, bottom = 6.dp),
        )
    }
}

@Composable
private fun EmojiCell(emoji: String, onPick: (String) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(WeTheme.dimens.emojiCellHeight)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onPick(emoji) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji, style = WeTheme.typography.emoji, maxLines = 1)
    }
}

/** 右下角的「删除」「发送」。背后一块和面板同色的底，上边和左边渐隐，滑到下面的表情不会突然被切掉 */
@Composable
private fun FloatingKeys(
    canDelete: Boolean,
    canSend: Boolean,
    onDelete: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier,
) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    val background = colors.barBackground
    Row(
        modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                val fade = 20.dp.toPx()
                drawRect(background)
                drawRect(
                    Brush.horizontalGradient(0f to Color.Transparent, fade / size.width to Color.Black),
                    blendMode = BlendMode.DstIn,
                )
                drawRect(
                    Brush.verticalGradient(0f to Color.Transparent, fade / size.height to Color.Black),
                    blendMode = BlendMode.DstIn,
                )
                drawContent()
            }
            .padding(start = 24.dp, top = 24.dp, end = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DeleteKey(canDelete, onDelete)
        Box(
            Modifier
                .size(dimens.emojiKeyWidth, dimens.emojiKeyHeight)
                .clip(RoundedCornerShape(6.dp))
                .background(if (canSend) colors.brand else colors.emojiKey)
                .clickable(
                    enabled = canSend,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = onSend,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.chat_send),
                style = WeTheme.typography.small,
                color = if (canSend) Color.White else colors.textSecondary,
            )
        }
    }
}

/** 按一下删一个字符；按住 0.4 秒后连续删除 */
@Composable
private fun DeleteKey(enabled: Boolean, onDelete: () -> Unit) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    val currentOnDelete by rememberUpdatedState(onDelete)
    val description = stringResource(R.string.emoji_delete)
    Box(
        Modifier
            .size(dimens.emojiKeyWidth, dimens.emojiKeyHeight)
            .clip(RoundedCornerShape(6.dp))
            .background(colors.emojiKey)
            .semantics {
                contentDescription = description
                role = Role.Button
                onClick {
                    currentOnDelete()
                    true
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                coroutineScope {
                    awaitEachGesture {
                        awaitFirstDown()
                        currentOnDelete()
                        val repeat = launch {
                            delay(400)
                            while (true) {
                                currentOnDelete()
                                delay(60)
                            }
                        }
                        waitForUpOrCancellation()
                        repeat.cancel()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.Backspace,
            contentDescription = stringResource(R.string.emoji_backspace),
            tint = if (enabled) colors.textPrimary else colors.textSecondary,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** 底部分类标签；当前所在分类有浅色底 */
@Composable
private fun EmojiTabs(sections: List<EmojiSection>, selected: Int, onSelect: (Int) -> Unit) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    Row(
        Modifier
            .fillMaxWidth()
            .height(dimens.emojiTabHeight)
            .background(colors.listBackground)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        sections.forEachIndexed { index, section ->
            Box(
                Modifier
                    .size(width = 48.dp, height = 34.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (index == selected) colors.listPressed else Color.Transparent)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Tab,
                        onClickLabel = section.title,
                        onClick = { onSelect(index) },
                    )
                    .semantics { contentDescription = section.title },
                contentAlignment = Alignment.Center,
            ) {
                if (section.icon == null) {
                    Icon(Icons.Outlined.AccessTime, null, tint = colors.textTertiary, modifier = Modifier.size(22.dp))
                } else {
                    Text(section.icon, fontSize = 20.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun groupTitles(): Map<String, String> = mapOf(
    "smileys" to stringResource(R.string.emoji_group_smileys),
    "people" to stringResource(R.string.emoji_group_people),
    "animals" to stringResource(R.string.emoji_group_animals),
    "food" to stringResource(R.string.emoji_group_food),
    "travel" to stringResource(R.string.emoji_group_travel),
    "activities" to stringResource(R.string.emoji_group_activities),
    "objects" to stringResource(R.string.emoji_group_objects),
    "symbols" to stringResource(R.string.emoji_group_symbols),
    "flags" to stringResource(R.string.emoji_group_flags),
)

/** 分类标签上的代表表情；本机没有时用这一组的第一个 */
private fun groupIcon(group: EmojiGroup): String {
    val preferred = when (group.key) {
        "smileys" -> "😀"
        "people" -> "👋"
        "animals" -> "🐻"
        "food" -> "🍔"
        "travel" -> "🚗"
        "activities" -> "⚽"
        "objects" -> "💡"
        "symbols" -> "🔣"
        "flags" -> "🏳️"
        else -> null
    }
    return preferred?.takeIf { it in group.emojis } ?: group.emojis.first()
}
