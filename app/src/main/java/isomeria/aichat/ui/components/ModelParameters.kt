package isomeria.aichat.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import isomeria.aichat.R
import isomeria.aichat.data.db.ProviderConfig
import isomeria.aichat.data.db.ProviderModel
import isomeria.aichat.data.llm.*
import isomeria.aichat.feature.settings.ChoiceRow
import isomeria.aichat.ui.theme.WeTheme
import java.util.Locale

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

@Composable
fun ThinkingSelector(model: ProviderModel?, value: String, title: String, onChange: (String) -> Unit) {
    val options = ModelRules.options(model)
    val current = options.firstOrNull { it.value == value }
    // 模型还没配置思考规则时没有档位可选，原因由调用方说明，这里不算保存值错了
    val invalid = current == null && options.isNotEmpty()
    ChoiceRow(title, current?.let { effortLabel(it) } ?: if (invalid) value else stringResource(R.string.rule_unknown),
        options = options.map { effortLabel(it) to it.value },
        valueColor = if (invalid) WeTheme.colors.danger else WeTheme.colors.textTertiary, onPick = onChange)
    if (invalid) ErrorNote(stringResource(R.string.thinking_pending, value))
}

@Composable
fun ModelParameterControls(config: ProviderConfig?, model: ProviderModel?, option: String, temperature: Float,
    onOption: (String) -> Unit, onTemperature: (Float) -> Unit) {
    val issue = config?.let { ModelRules.validate(it, model, option, temperature) }
    ThinkingSelector(model, option, stringResource(R.string.contact_thinking), onOption)
    val spec = if (model == null || model.legacy) {
        if (option == "off") TemperatureSpec(TemperatureMode.ADJUSTABLE) else TemperatureSpec(TemperatureMode.UNSUPPORTED)
    } else ModelRules.effective(model).temperature?.forOption(option)
    when (spec?.mode) {
        TemperatureMode.ADJUSTABLE -> {
            Column(Modifier.fillMaxWidth().background(WeTheme.colors.listBackground).padding(WeTheme.dimens.horizontalPadding)) {
                Text(stringResource(R.string.contact_temperature) + " · " + String.format(Locale.ROOT, "%.2f", temperature), style = WeTheme.typography.listTitle,
                    color = if (issue == ConfigurationIssue.TEMPERATURE) WeTheme.colors.danger else WeTheme.colors.textPrimary)
                if (spec.valid()) Slider(value = temperature.coerceIn(spec.min, spec.max), onValueChange = onTemperature, valueRange = spec.min..spec.max,
                    colors = SliderDefaults.colors(thumbColor = WeTheme.colors.brand, activeTrackColor = WeTheme.colors.brand, inactiveTrackColor = WeTheme.colors.switchTrackOff))
                FootnoteText(stringResource(R.string.temperature_range_note, spec.min.toString(), spec.max.toString()))
            }
        }
        TemperatureMode.FIXED -> FootnoteText(stringResource(R.string.temperature_fixed_note, spec.fixed.toString()))
        TemperatureMode.UNSUPPORTED -> FootnoteText(stringResource(R.string.temperature_omitted_note))
        null -> if (issue == null) FootnoteText(stringResource(R.string.model_configuration_required))
    }
    // 档位的问题已经在思考那一行下面说明了
    if (issue != null && issue != ConfigurationIssue.THINKING) ErrorNote(stringResource(configurationIssueText(issue)))
}
