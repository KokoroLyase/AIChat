package isomeria.aichat.core

/**
 * 测试拆条、间隔和投递计时用的固定回复偏好（0.5.0 开发时的取值：等 3 秒、每条 14 字、1～5 条、间隔 3～10 秒）。
 * 这些测试验证的是计算方式，不随默认值变化；默认值本身由 [ReplyRhythmTest.defaults_areTheValuesChosenAfterDeviceTesting] 等测试守住。
 */
val RHYTHM_TEST_PREFERENCES = ReplyPreferences(
    waitSeconds = 3,
    targetLength = 14,
    minCount = 1,
    maxCount = 5,
    stripPunctuation = true,
    minIntervalSeconds = 3,
    maxIntervalSeconds = 10,
)
