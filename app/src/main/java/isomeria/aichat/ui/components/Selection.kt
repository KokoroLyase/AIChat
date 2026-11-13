package isomeria.aichat.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import isomeria.aichat.ui.theme.WeMotion
import isomeria.aichat.ui.theme.WeTheme

/**
 * 多选（0.9.0）时消息左边的勾选圈：没选是灰色细圈，选中是绿底白勾；[enabled] 为 false（等待回复中的消息、正在进行的通话）时更淡。
 * 只画出来，点击由整行负责
 */
@Composable
fun SelectCircle(selected: Boolean, enabled: Boolean, modifier: Modifier = Modifier) {
    val colors = WeTheme.colors
    Box(
        modifier
            .size(22.dp)
            .clip(CircleShape)
            .then(
                if (selected) {
                    Modifier.background(colors.brand)
                } else {
                    Modifier.border(1.2.dp, if (enabled) colors.textTertiary else colors.disabled, CircleShape)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(WeIcons.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
    }
}

/**
 * 多选进出时底部的切换（0.9.1）：输入栏和操作栏交叉淡变，高度跟着过渡。
 * [content] 收到的选中集合不为 null 时画操作栏，为 null 时画输入栏；淡出中的操作栏沿用最后一次的选中。
 */
@Composable
fun SelectionCrossfade(selection: Set<Long>?, content: @Composable (selected: Set<Long>?) -> Unit) {
    val last = remember { mutableStateOf<Set<Long>>(emptySet()) }
    selection?.let { last.value = it }
    AnimatedContent(
        targetState = selection != null,
        transitionSpec = {
            fadeIn(tween(WeMotion.SELECTION)) togetherWith fadeOut(tween(WeMotion.SELECTION)) using SizeTransform(clip = false)
        },
        label = "selectionBar",
    ) { selecting ->
        Column(Modifier.fillMaxWidth()) {
            content(if (selecting) selection ?: last.value else null)
        }
    }
}

/** 多选底部操作栏的一项：图标和下面一行字 */
data class SelectionAction(val label: String, val icon: ImageVector, val enabled: Boolean, val danger: Boolean = false, val onClick: () -> Unit)

/**
 * 多选（0.9.0）时代替输入栏的底部操作栏：复制、收藏、删除，平分宽度；上面一条分隔线，下面让出导航栏
 */
@Composable
fun SelectionBar(actions: List<SelectionAction>, modifier: Modifier = Modifier) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    Row(
        modifier
            .fillMaxWidth()
            .background(colors.barBackground)
            .drawBehind { drawLine(colors.divider, Offset.Zero, Offset(size.width, 0f), dimens.dividerThickness.toPx()) }
            .navigationBarsPadding()
            .heightIn(min = dimens.bottomBarHeight),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (action in actions) {
            val tint = when {
                !action.enabled -> colors.textTertiary
                action.danger -> colors.dangerText
                else -> colors.textPrimary
            }
            Column(
                Modifier
                    .weight(1f)
                    .heightIn(min = dimens.bottomBarHeight)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = action.enabled,
                        role = Role.Button,
                        onClick = action.onClick,
                    )
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(action.icon, null, tint = tint, modifier = Modifier.size(dimens.topBarIcon))
                Text(action.label, style = WeTheme.typography.small, color = tint, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}
