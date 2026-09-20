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