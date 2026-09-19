package top.wkbin.taixu.harness.mcp

import top.wkbin.taixu.core.model.McpToolInfo

/**
 * 活跃 MCP 工具清单的只读目录。
 *
 * 抽象出可测接缝：提示词/上下文组装路径（ApiContextAssembler）需要**全量**活跃工具清单
 * 来渲染稳定的能力章节（不随 @ 提及裁剪漂移），而不应直接依赖 McpManager 的完整生命周期门面。
 */
interface ActiveMcpToolCatalog {
    suspend fun getActiveMcpTools(): List<McpToolInfo>
}
