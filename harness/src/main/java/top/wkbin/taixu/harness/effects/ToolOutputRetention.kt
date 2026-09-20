package top.wkbin.taixu.harness.effects

/** 工具输出超限时的保留方向（对标 Pi `ToolDefinition.retention`）。 */
enum class OutputRetention { HEAD, TAIL }

/**
 * 差异化截断策略：不同工具的关键信息位置不同，截断方向必须随工具切换。
 *
 * - [OutputRetention.TAIL]：命令执行 / 构建 / 进程日志。panic、断言失败、编译错误
 *   永远出现在末尾，保留尾部才能让模型直击报错核心，而不是被前面的下载进度刷屏。
 * - [OutputRetention.HEAD]：read / search 等，关键信息通常在最前面（文件头、命中项）。
 */
object ToolOutputRetention {
    fun forTool(toolName: String?): OutputRetention = when (toolName?.trim()?.lowercase()) {
        "base", "process", "build_script" -> OutputRetention.TAIL
        else -> OutputRetention.HEAD
    }
}

/**
 * 头部截断并对齐完整行：优先在字符预算内最后一个换行处切分，避免把一行切成两半；
 * 单行超过预算时退化为硬截断（无法按行对齐）。
 */
internal fun keepHeadWholeLines(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    val cut = text.lastIndexOf('\n', maxChars).let { if (it <= 0) maxChars else it }
    return text.substring(0, cut)
}

/** 尾部截断并对齐完整行：从末尾往前取整行，保证不切在行中。 */
internal fun keepTailWholeLines(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    val from = (text.length - maxChars).coerceAtLeast(0)
    val newline = text.indexOf('\n', from)
    val cut = if (newline < 0) text.length - maxChars else newline + 1
    return text.substring(cut.coerceIn(0, text.length))
}