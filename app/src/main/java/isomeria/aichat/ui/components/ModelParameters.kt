package isomeria.aichat.ui.components

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

@Composable
fun ThinkingSelector(model: ProviderModel?, value: String, title: String, onChange: (String) -> Unit) {
    val options = ModelRules.options(model)
    val current = options.firstOrNull { it.value == value }
    ChoiceRow(title, current?.let { effortLabel(it) } ?: value,
        options = options.map { effortLabel(it) to it.value }, onPick = onChange)
    if (current == null) FootnoteText(stringResource(R.string.thinking_pending, value))
}

@Composable
fun ModelParameterControls(config: ProviderConfig?, model: ProviderModel?, option: String, temperature: Float,
    onOption: (String) -> Unit, onTemperature: (Float) -> Unit) {
    ThinkingSelector(model, option, stringResource(R.string.contact_thinking), onOption)
    val spec = if (model == null || model.legacy) {
        if (option == "off") TemperatureSpec(TemperatureMode.ADJUSTABLE) else TemperatureSpec(TemperatureMode.UNSUPPORTED)
    } else ModelRules.effective(model).temperature?.forOption(option)
    when (spec?.mode) {
        TemperatureMode.ADJUSTABLE -> {
            Column(Modifier.fillMaxWidth().background(WeTheme.colors.listBackground).padding(WeTheme.dimens.horizontalPadding)) {
                Text(stringResource(R.string.contact_temperature) + " · " + String.format(Locale.ROOT, "%.2f", temperature), style = WeTheme.typography.listTitle, color = WeTheme.colors.textPrimary)
                if (spec.valid()) Slider(value = temperature.coerceIn(spec.min, spec.max), onValueChange = onTemperature, valueRange = spec.min..spec.max,
                    colors = SliderDefaults.colors(thumbColor = WeTheme.colors.brand, activeTrackColor = WeTheme.colors.brand, inactiveTrackColor = WeTheme.colors.switchTrackOff))
                FootnoteText(stringResource(R.string.temperature_range_note, spec.min.toString(), spec.max.toString()))
            }
        }
        TemperatureMode.FIXED -> FootnoteText(stringResource(R.string.temperature_fixed_note, spec.fixed.toString()))
        TemperatureMode.UNSUPPORTED -> FootnoteText(stringResource(R.string.temperature_omitted_note))
        null -> FootnoteText(stringResource(R.string.model_configuration_required))
    }
    if (config != null && ModelRules.validate(config, model, option, temperature) != null) FootnoteText(stringResource(R.string.error_configuration))
}
