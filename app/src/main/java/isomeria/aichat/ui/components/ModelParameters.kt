package isomeria.aichat.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import isomeria.aichat.R
import isomeria.aichat.data.db.ProviderConfig
import isomeria.aichat.data.db.ProviderModel
import isomeria.aichat.data.llm.*
import isomeria.aichat.feature.settings.ChoiceRow
import isomeria.aichat.ui.theme.WeTheme
import java.util.Locale
import kotlin.math.roundToInt

@Composable fun effortLabel(option: NativeEffort): String = if (option.label.isNotBlank()) option.label else stringResource(when (option.value) {
    "off" -> R.string.thinking_off; "on" -> R.string.thinking_on; "high" -> R.string.thinking_high; "max" -> R.string.thinking_max; else -> R.string.rule_unknown
})

/** 配置问题的具体原因。记忆固定用温度 0.3，也有自己的模型选择，所以个别说法不同。 */
@StringRes
fun configurationIssueText(issue: ConfigurationIssue, memory: Boolean = false): Int = when (issue) {
    ConfigurationIssue.MODEL -> if (memory) R.string.memory_issue_model else R.string.contact_model_unavailable
    ConfigurationIssue.INCOMPLETE -> R.string.config_issue_incomplete
    ConfigurationIssue.THINKING -> R.string.config_issue_thinking
    ConfigurationIssue.TEMPERATURE -> if (memory) R.string.memory_issue_temperature else R.string.config_issue_temperature
    ConfigurationIssue.ENCODING -> R.string.config_issue_encoding
}

/** 拖动时按 0.1 吸附；范围的两端不一定是 0.1 的整数倍，拖到头时取端点本身。 */
internal fun snapTemperature(raw: Float, min: Float, max: Float): Float = when {
    raw <= min -> min
    raw >= max -> max
    else -> ((raw * 10).roundToInt() / 10f).coerceIn(min, max)
}

/** 如实显示保存的值：1 显示 1.0，0.99 显示 0.99，不四舍五入成别的数。 */
internal fun temperatureText(value: Float): String {
    val text = String.format(Locale.ROOT, "%.2f", value).trimEnd('0')
    return if (text.endsWith('.')) text + "0" else text
}

@Composable
fun ThinkingSelector(model: ProviderModel?, value: String, title: String, showDivider: Boolean = false, onChange: (String) -> Unit) {
    val options = ModelRules.options(model)
    val current = options.firstOrNull { it.value == value }
    // 模型还没配置思考规则时没有档位可选，原因由调用方说明，这里不算保存值错了
    val invalid = current == null && options.isNotEmpty()
    ChoiceRow(title, current?.let { effortLabel(it) } ?: if (invalid) value else stringResource(R.string.rule_unknown),
        options = options.map { effortLabel(it) to it.value },
        valueColor = if (invalid) WeTheme.colors.danger else WeTheme.colors.textTertiary, showDivider = showDivider,
        isSelected = { _, option -> option == value }, onPick = onChange)
    if (invalid) ErrorNote(stringResource(R.string.thinking_pending, value))
}

@Composable
fun ModelParameterControls(config: ProviderConfig?, model: ProviderModel?, option: String, temperature: Float,
    onOption: (String) -> Unit, onTemperature: (Float) -> Unit) {
    val issue = config?.let { ModelRules.validate(it, model, option, temperature) }
    val spec = if (model == null || model.legacy) {
        if (option == "off") TemperatureSpec(TemperatureMode.ADJUSTABLE) else TemperatureSpec(TemperatureMode.UNSUPPORTED)
    } else ModelRules.effective(model).temperature?.forOption(option)
    ThinkingSelector(model, option, stringResource(R.string.contact_thinking), showDivider = spec != null, onChange = onOption)
    when (spec?.mode) {
        TemperatureMode.ADJUSTABLE -> TemperatureSlider(temperature, spec, outOfRange = issue == ConfigurationIssue.TEMPERATURE, onTemperature)
        // 固定和不发送也占一行，不然看起来像温度设置消失了
        TemperatureMode.FIXED -> {
            WeListRow(stringResource(R.string.contact_temperature), value = stringResource(R.string.temperature_value_fixed, temperatureText(spec.fixed)))
            FootnoteText(stringResource(R.string.temperature_fixed_note))
        }
        TemperatureMode.UNSUPPORTED -> {
            WeListRow(stringResource(R.string.contact_temperature), value = stringResource(R.string.rule_no_send))
            FootnoteText(stringResource(R.string.temperature_omitted_note))
        }
        null -> if (issue == null) FootnoteText(stringResource(R.string.model_configuration_required))
    }
    // 档位的问题已经在思考那一行下面说明了
    if (issue != null && issue != ConfigurationIssue.THINKING) ErrorNote(stringResource(configurationIssueText(issue)))
}

/** 微信式滑块：细轨道 + 白色圆形滑块。只有拖动才会改值，保存的值超出范围时不会被悄悄改写。 */
@OptIn(ExperimentalMaterial3Api::class) // 自定义滑块和轨道的 Slider 重载仍是实验性 API
@Composable
private fun TemperatureSlider(value: Float, spec: TemperatureSpec, outOfRange: Boolean, onChange: (Float) -> Unit) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.listBackground)
            .padding(horizontal = dimens.horizontalPadding, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.contact_temperature),
                style = WeTheme.typography.listTitle,
                color = if (outOfRange) colors.danger else colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                temperatureText(value),
                style = WeTheme.typography.listTitle,
                color = if (outOfRange) colors.danger else colors.textTertiary,
            )
        }
        if (spec.valid()) {
            val sliderColors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = colors.brand,
                inactiveTrackColor = colors.switchTrackOff,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            )
            Slider(
                value = value.coerceIn(spec.min, spec.max),
                onValueChange = { onChange(snapTemperature(it, spec.min, spec.max)) },
                valueRange = spec.min..spec.max,
                colors = sliderColors,
                thumb = {
                    Box(
                        Modifier
                            .size(dimens.sliderThumb)
                            .shadow(2.dp, CircleShape)
                            .background(Color.White, CircleShape),
                    )
                },
                track = { sliderState ->
                    SliderDefaults.Track(
                        sliderState = sliderState,
                        modifier = Modifier.height(dimens.sliderTrack),
                        colors = sliderColors,
                        drawStopIndicator = null,
                        drawTick = { _, _ -> },
                        thumbTrackGapSize = 0.dp,
                        trackInsideCornerSize = dimens.sliderTrack / 2,
                    )
                },
            )
        }
        Text(
            stringResource(R.string.temperature_range_note, temperatureText(spec.min), temperatureText(spec.max)),
            style = WeTheme.typography.listTime,
            color = colors.textTertiary,
        )
    }
}
