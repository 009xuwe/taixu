package top.wkbin.taixu.harness.session

import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import top.wkbin.taixu.core.datastore.AgentPreferences
import top.wkbin.taixu.core.model.McpToolInfo
import top.wkbin.taixu.harness.ApiMessage
import top.wkbin.taixu.harness.ContextWindowPolicy
import top.wkbin.taixu.harness.HarnessApiMapper
import top.wkbin.taixu.harness.HarnessMessage
import top.wkbin.taixu.harness.ModelConfig
import top.wkbin.taixu.harness.MentionExtractor
import top.wkbin.taixu.harness.ToolCall
import top.wkbin.taixu.harness.ToolCallMode
import top.wkbin.taixu.harness.UserMessage
import top.wkbin.taixu.harness.compaction.CompactionManager
import top.wkbin.taixu.harness.compaction.SummaryRequestContext
import top.wkbin.taixu.harness.mcp.ActiveMcpToolCatalog
import top.wkbin.taixu.harness.prompt.MemoryRecallSelector
import top.wkbin.taixu.harness.prompt.SystemPromptBuilder

/**
 * API 请求上下文组装器：把会话实时消息投影成提供商协议消息列表。
 *
 * 从原 HarnessLoop.apiMessages 迁移而来，负责：
 * - 系统提示词注入（非纯净聊天模式；逐轮可变内容已全部外移，见下）
 * - 用户轮记忆召回后缀的持久化（recall_context entry，每轮只算一次）
 * - 上下文压缩摘要的头部注入（预算驱动的滑动窗口折叠）
 * - NATIVE / JSON_TEXT 两种工具调用协议的消息形态转换（经 [ApiMessageProjector]）
 * - 视觉能力关闭时剥离图片输入
 *
 * Prefix cache 稳定性契约：system prompt 内不再含逐轮变化的内容（recall 已移到
 * user 轮后缀、路由规则块与技能按全会话累计），相邻两轮请求的 system 消息字节级一致；
 * 变化只出现在本轮新增的消息上（本就未进入缓存）。
 */
class ApiContextAssembler @Inject constructor(
    private val compactionManager: CompactionManager,
    private val settingsDataStore: AgentPreferences,
    private val systemPromptBuilder: SystemPromptBuilder,
    private val sessionStore: SessionTreeStore,
    private val memoryRecallSelector: MemoryRecallSelector,
    private val mcpCatalog: ActiveMcpToolCatalog,
) {
    suspend fun assemble(
        sessId: String,
        model: ModelConfig,
        workspacePath: String,
        projectTypeOverride: String = "",
        thinkingMode: Boolean = false,
    ): List<ApiMessage> {
        val compactionEnabled = runCatching { settingsDataStore.contextCompactionEnabled.first() }.getOrDefault(true)
        // 「历史折叠线比例」：让历史在预算的一部分处就开始折叠。
        // 与面板同源读取同一个偏好，保证两侧折叠决策一致。
        val foldingRatioPercent = runCatching { settingsDataStore.contextFoldingRatioPercent.first() }
            .getOrDefault(ContextWindowPolicy.DEFAULT_FOLDING_RATIO_PERCENT)
        // 与 SessionModelSwitcher 共用 clampedBudget：占用判定与实际请求必须是同一预算口径
        val budgetTokens = ContextWindowPolicy.clampedBudget(
            model.contextTokens,
            runCatching { settingsDataStore.contextBudgetTokens.first() }.getOrDefault(128_000),
        )
        val toolCallMode = if (model.pureChatMode) ToolCallMode.DISABLED else model.toolCallMode

        var compactedContext = compactionManager.project(sessId)
        var msgs = compactedContext.messages

        // 用户轮记忆召回后缀（低权威背景资料）：只对最新用户轮计算一次并持久化到
        // 会话树（appendRecallBlock 幂等），此后该轮的投影永远携带同一段字节。
        // 这取代了旧的「system prompt 尾部注入」——那种逐轮重算会击穿整个前缀缓存。
        // 持久化失败时放弃挂载：未持久化的字节进投影会导致下一轮组装漂移。
        if (!model.pureChatMode) {
            val latestUser = msgs.filterIsInstance<UserMessage>().lastOrNull()
            if (latestUser != null && latestUser.id !in compactedContext.recallBlocks) {
                val block = try {
                    memoryRecallSelector.recallBlock(
                        projectOwnerId = workspacePath.trim().trimEnd('/'),
                        sessionId = sessId,
                        userMessage = latestUser.text,
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    ""
                }
                if (block.isNotBlank() && sessionStore.appendRecallBlock(sessId, latestUser.id, block)) {
                    compactedContext = compactedContext.copy(
                        recallBlocks = compactedContext.recallBlocks + (latestUser.id to block),
                    )
                }
            }
        }

        // 技能 @提及按全会话累计（一旦提及，规则常驻本会话）：避免「下一轮未提及→章节
        // 撤出」造成的 system prompt 漂移。MCP @ 裁剪（工具面）仍按当轮最新消息，见
        // HarnessProviderRunner.resolveEffectiveModel——那条路影响的是 tools 数组而非提示词。
        val mentionedNames = msgs.filterIsInstance<UserMessage>()
            .flatMapTo(mutableSetOf()) { MentionExtractor.parse(it.text) }
        val userMessageTexts = msgs.filterIsInstance<UserMessage>().map { it.text }

        val rawSystemPrompt = if (!model.pureChatMode) {
            systemPromptBuilder.build(
                workspacePath,
                toolCallMode,
                mentionedNames,
                sessId,
                projectTypeOverride,
                userMessageTexts,
                mcpTools = promptMcpTools(model),
            )
        } else {
            ""
        }
        val systemPrompt = ContextWindowPolicy.fitSystemPrompt(rawSystemPrompt, budgetTokens)

        // 召回后缀的 token 计入预算占用：它们会随 user 消息进入 provider 请求
        val recallTokens = ContextWindowPolicy.estimateTokens(compactedContext.recallBlocks.values.joinToString("\n"))

        return buildList {
            if (systemPrompt.isNotEmpty()) {
                add(ApiMessage(role = "system", content = systemPrompt))
            }

            // 老轮次工具结果截断（先于压缩判定）：预算线未越过时，历史轮的大输出（浏览器快照、
            // 长 read）仍会原样重复发送。最近若干条原样保留，更老的超过按工具阈值即压缩并附
            // history_read 指针——只影响发给 Provider 的正文，落库 transcript 与 UI 不变。
            // 关闭上下文压缩 = 用户要原始历史，此时同样不截断。
            // 顺序必须在压缩判定之前：只需截断即可回到预算线内的会话，不应再触发整段压缩
            // （一次额外 LLM 调用 + 历史永久降级为摘要）。
            if (compactionEnabled) {
                msgs = ContextWindowPolicy.truncateStaleToolResults(msgs, toolCallDetailsOf(msgs))
            }

            // 预算驱动的滑动窗口：从最近一轮往回累加 token，超出预算则更早的历史进入压缩态。
            // 是否裁剪原文只由真实 token 预算决定，不再按用户轮次阈值强制折叠。
            // 每模型压缩预算覆盖（pi 式 modelOverrides）：keepRecent 收紧 + reserve 预留。
            val computedKeepFromIndex = if (compactionEnabled) {
                ContextWindowPolicy.computeKeepFromIndex(
                    msgs,
                    budgetTokens,
                    ContextWindowPolicy.estimateTokens(systemPrompt) +
                        ContextWindowPolicy.estimateTokens(compactedContext.summaryLayer) +
                        recallTokens,
                    keepRecentTokens = model.compactionKeepRecentTokens ?: 0,
                    reserveTokens = model.compactionReserveTokens,
                    foldingRatioPercent = foldingRatioPercent,
                )
            } else {
                0
            }
            if (computedKeepFromIndex > 0) {
                // LLM 结构化压缩摘要（pi 式）：当前模型生成，失败回退机械摘要。
                // summaryContext 让摘要请求重放主对话的 system + 摘要层 + 原始消息前缀，
                // 命中 provider KV 缓存（cache-replay 形状，对齐 Reasonix）。
                compactedContext = compactionManager.compact(
                    sessId,
                    compactedContext,
                    computedKeepFromIndex,
                    model = model,
                    summaryContext = SummaryRequestContext(
                        systemPrompt = systemPrompt,
                        summaryLayer = compactedContext.summaryLayer,
                        toolCallMode = toolCallMode,
                        visionEnabled = model.visionEnabled,
                        recallBlocks = compactedContext.recallBlocks,
                        // 被折叠区域的 provider 可见形态：截断已发生过，与主对话实际发送的字节一致
                        replayPrefix = msgs.take(computedKeepFromIndex),
                    ),
                )
                msgs = compactedContext.messages
                // compact 返回的保留窗口来自原始 transcript（未截断），重放一次截断，
                // 保证与压缩判定时同一口径。
                if (compactionEnabled) {
                    msgs = ContextWindowPolicy.truncateStaleToolResults(msgs, toolCallDetailsOf(msgs))
                }
            }
            if (compactionEnabled) {
                // 巨型用户消息兜底（与 computeKeepFromIndex 同一条折叠线，foldingLimitFor
                // 内部自钳比例）：单条自身超线的用户消息（粘贴长文档/日志）无法按边界折叠，
                // kept 恒超预算 → 每轮请求必被 provider 400。对保留区超大用户消息做
                // 投影级头尾截断，落库 transcript 与 UI 不受影响。
                msgs = ContextWindowPolicy.truncateOversizedUserMessages(
                    msgs,
                    ContextWindowPolicy.foldingLimitFor(
                        budget = budgetTokens,
                        ratioPercent = foldingRatioPercent,
                        systemTokens = ContextWindowPolicy.estimateTokens(systemPrompt) +
                            ContextWindowPolicy.estimateTokens(compactedContext.summaryLayer) +
                            recallTokens,
                        reserveTokens = model.compactionReserveTokens,
                    ),
                )
            }
            val summaryLayer = compactedContext.summaryLayer
            if (summaryLayer.isNotBlank()) {
                add(
                    ApiMessage(
                        role = "system",
                        content = summaryLayer,
                    ),
                )
            }
            addAll(
                ApiMessageProjector.project(
                    msgs = msgs,
                    toolCallMode = toolCallMode,
                    visionEnabled = model.visionEnabled,
                    recallSuffixes = compactedContext.recallBlocks,
                ),
            )
        }
    }

    /**
     * System prompt 的 MCP 能力章节使用**全量**活跃工具清单，而非 @ 裁剪后的
     * model.dynamicMcpTools：@ 裁剪只应收窄本轮 tools 数组，不应让提示词章节随
     * 提及漂移（那会击穿前缀缓存）。与 ProviderClient 请求路径同源（缓存命中，无额外发现成本）。
     */
    private suspend fun promptMcpTools(model: ModelConfig): List<McpToolInfo> =
        if (model.pureChatMode) {
            emptyList()
        } else {
            try {
                mcpCatalog.getActiveMcpTools()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                model.dynamicMcpTools
            }
        }

    private fun toolCallDetailsOf(msgs: List<HarnessMessage>) =
        msgs.filterIsInstance<ToolCall>().associate {
            it.id to ((it.rawToolName ?: HarnessApiMapper.apiName(it.tool)) to it.args)
        }
}
