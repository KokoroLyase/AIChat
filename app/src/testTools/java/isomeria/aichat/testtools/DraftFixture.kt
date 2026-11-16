package isomeria.aichat.testtools

import java.io.File

/**
 * 测试工具“draft”（1.0.0）：`adb input` 输不了中文，真机测试时把要发的中文写进联系人的草稿，发送仍在界面上点。
 * 文字用 UTF-8 写在 App 的外部私有目录的 `draft.txt` 里（`adb push` 就能放进去，避免 adb shell 的编码问题），
 * 读完就删。只在 debug、perf 包里有。
 */
object DraftFixture {
    const val FILE = "draft.txt"
    const val MAX_CHARS = 4_000

    /** 读出 [source] 的文字并删掉文件；没有文件、为空或太长时返回 null。结尾的换行去掉（adb push 的文件常带一个） */
    fun take(source: File): String? {
        if (!source.isFile) return null
        val text = try { source.readText(Charsets.UTF_8) } finally { source.delete() }
        val trimmed = text.removePrefix("﻿").trimEnd('\r', '\n')
        return trimmed.takeIf { it.isNotEmpty() && it.length <= MAX_CHARS }
    }
}
