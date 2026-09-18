package top.wkbin.taixu.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.put

class ContextWindowPolicyTest {
    @Test
    fun `generated image payload is omitted from provider context and token estimate`() {
        val payload = "A".repeat(1_000_000)
        val raw = "完成：![图](data:image/png;base64,$payload) 请继续"

        val sanitized = ContextWindowPolicy.assistantTextForContext(raw)

        assertTrue(sanitized.contains("生成了一张图片"))
        assertTrue(sanitized.contains("请继续"))
        assertFalse(sanitized.contains(payload.take(64)))
        assertTrue(ContextWindowPolicy.estimateTokens(sanitized) < 100)
    }
    @Test
    fun keepsRecentMessagesWithinBudget() {
        val messages = listOf(
            UserMessage("1", 1, "a".repeat(2_000)),
            AssistantText("2", 2, "b".repeat(2_000)),
            UserMessage("3", 3, "recent"),
        )

        // Budget must leave room after the input-fraction + output/schema reserves.
        val keepFrom = ContextWindowPolicy.computeKeepFromIndex(messages, budget = 18_000, systemTokens = 10)

        assertEquals(2, keepFrom)
        assertTrue(messages[keepFrom] is UserMessage)
    }

    @Test
    fun `exhausted budget retains only a minimal recent turn instead of all history`() {
        val messages = listOf(
            UserMessage("old-user", 1, "old request"),
            AssistantText("old-assistant", 2, "old answer"),
            UserMessage("latest-user", 3, "latest request"),
        )

        val keepFrom = ContextWindowPolicy.computeKeepFromIndex(messages, budget = 4_000, systemTokens = 4_000)

        assertEquals(2, keepFrom)
    }

    @Test
    fun `exhausted budget does not orphan the only tool result`() {
        val call = ToolCall("call", 1, HarnessTool.BASE, kotlinx.serialization.json.buildJsonObject {})
        val messages = listOf(call, ToolResult("result", 2, "call", true, "ok"))

        val keepFrom = ContextWindowPolicy.computeKeepFromIndex(messages, budget = 0, systemTokens = 0)

        assertEquals(0, keepFrom)
    }

    @Test
    fun `oversized system prompt is bounded`() {
        val fitted = ContextWindowPolicy.fitSystemPrompt("x".repeat(100_000), budget = 8_000)

        assertTrue(fitted.length < 100_000)
        assertTrue(fitted.contains("系统提示因上下文预算受限已截断"))
    }

    @Test
    fun compactsLongToolOutputWithHeadAndTail() {
        val compacted = ContextWindowPolicy.compactToolOutput(
            toolName = null,
            args = null,
            output = (1..10).joinToString("\n") { "line-$it" },
            success = true,
        )

        assertTrue(compacted.contains("line-1"))
        assertTrue(compacted.contains("line-10"))
        assertTrue(compacted.contains("已略去 5 行"))
    }

    @Test
    fun effectiveUsageReplacesCollapsedHistoryWithOneBoundedSummary() {
        val messages = buildList<HarnessMessage> {
            repeat(100) { index ->
                add(UserMessage("u-$index", index * 2L, "需求-$index " + "a".repeat(500)))
                add(AssistantText("a-$index", index * 2L + 1, "回复-$index " + "b".repeat(500)))
            }
            add(UserMessage("latest", 1_000, "最近问题"))
        }

        val usage = ContextWindowPolicy.estimateEffectiveUsage(
            messages = messages,
            budget = 18_000,
            systemTokens = 100,
            compactionEnabled = true,
        )

        assertTrue(usage.keepFromIndex > 0)
        assertTrue(usage.totalTokens < 18_000)
        assertTrue(usage.conversationTokens < messages.sumOf {
            when (it) {
                is UserMessage -> ContextWindowPolicy.estimateTokens(it.text)
                is AssistantText -> ContextWindowPolicy.estimateTokens(it.text)
                else -> 0
            }
        })
    }

    @Test
    fun `large context models keep all history that fits the token budget`() {
        val messages = buildList<HarnessMessage> {
            repeat(30) { index ->
                add(UserMessage("u-$index", index * 2L, "request $index"))
                add(AssistantText("a-$index", index * 2L + 1, "answer $index"))
            }
        }

        val keepFrom = ContextWindowPolicy.computeKeepFromIndex(
            messages = messages,
            budget = 1_000_000,
            systemTokens = 0,
        )

        assertEquals(0, keepFrom)
    }

    @Test
    fun `token boundary advances to the next complete user turn`() {
        val call = ToolCall(
            "call",
            2,
            HarnessTool.BASE,
            kotlinx.serialization.json.buildJsonObject {},
            reasoning = "x".repeat(4_000),
        )
        val messages = listOf(
            UserMessage("old", 1, "x".repeat(1_000)),
            call,
            ToolResult("result", 3, "call", true, "ok"),
            UserMessage("latest", 4, "now"),
        )

        val keepFrom = ContextWindowPolicy.computeKeepFromIndex(messages, budget = 18_000, systemTokens = 10)

        assertEquals(3, keepFrom)
        assertTrue(messages[keepFrom] is UserMessage)
    }

    @Test
    fun `parallel tool calls and results remain paired across a forced boundary`() {
        val call1 = ToolCall(
            "call-1",
            2,
            HarnessTool.BASE,
            kotlinx.serialization.json.buildJsonObject {},
            reasoning = "x".repeat(4_000),
        )
        val call2 = ToolCall("call-2", 3, HarnessTool.READ, kotlinx.serialization.json.buildJsonObject {})
        val messages = listOf(
            UserMessage("old-user", 1, "old request"),
            call1,
            call2,
            ToolResult("result-1", 4, "call-1", true, "first"),
            ToolResult("result-2", 5, "call-2", true, "second"),
            AssistantText("old-answer", 6, "done"),
            UserMessage("latest-user", 7, "latest request"),
        )

        val keepFrom = ContextWindowPolicy.computeKeepFromIndex(messages, budget = 18_000, systemTokens = 10)
        val kept = messages.drop(keepFrom)
        val keptCallIds = kept.filterIsInstance<ToolCall>().mapTo(mutableSetOf()) { it.id }

        assertEquals(6, keepFrom)
        assertTrue(kept.first() is UserMessage)
        assertTrue(kept.filterIsInstance<ToolResult>().all { it.toolCallId in keptCallIds })
    }

    @Test
    fun `interrupted cross-turn tool result pulls its call back into the window`() {
        val messages = listOf(
            UserMessage("old-user", 1, "old request"),
            ToolCall("call", 2, HarnessTool.BASE, kotlinx.serialization.json.buildJsonObject {}),
            UserMessage("latest-user", 3, "latest request"),
            ToolResult("late-result", 4, "call", true, "late"),
        )

        val keepFrom = ContextWindowPolicy.computeKeepFromIndex(messages, budget = 0, systemTokens = 0)
        val kept = messages.drop(keepFrom)

        assertEquals(1, keepFrom)
        assertTrue(kept.first() is ToolCall)
        assertTrue(kept.filterIsInstance<ToolResult>().all { result ->
            kept.filterIsInstance<ToolCall>().any { it.id == result.toolCallId }
        })
    }

    @Test
    fun historySummaryKeepsStructuredFactsAndToolSpecificFailureContext() {
        val call = ToolCall(
            "call",
            2,
            HarnessTool.BASE,
            kotlinx.serialization.json.buildJsonObject { put("command", "./gradlew test") },
        )
        val summary = ContextWindowPolicy.buildHistorySummary(
            listOf(
                UserMessage("u", 1, "必须保持 core 模块纯 Kotlin，不能引入 Android 依赖"),
                AssistantText("a", 2, "决定采用滑动窗口方案，修改 /workspace/app/src/Main.kt"),
                call,
                ToolResult("r", 4, "call", false, "FAILURE: tests failed with a stack trace"),
            ),
        )

        assertTrue(summary.contains("用户硬约束"))
        assertTrue(summary.contains("关键决定"))
        assertTrue(summary.contains("涉及文件"))
        assertTrue(summary.contains("失败根因线索"))
        assertTrue(summary.contains("gradlew"))
    }

    @Test
    fun `estimateEffectiveUsage calculates 8-dimensional breakdown correctly`() {
        val messages = listOf(
            UserMessage("u1", 1, "Hello world"),
            AssistantText("a1", 2, "I will check the files."),
            ToolCall("t1", 3, HarnessTool.BASE, kotlinx.serialization.json.buildJsonObject {
                put("command", "ls -la")
            }),
            ToolResult("r1", 4, "t1", true, "file1.txt\nfile2.txt"),
        )

        val usage = ContextWindowPolicy.estimateEffectiveUsage(
            messages = messages,
            budget = 128_000,
            systemTokens = 5_000,
            compactionEnabled = true,
            systemPromptTokens = 576,
            toolDefinitionTokens = 3_600,
            rulesTokens = 1_400,
            skillsTokens = 500,
            mcpTokens = 800,
            subagentTokens = 1_100,
        )

        val bd = usage.breakdown
        assertEquals(576, bd.systemPromptTokens)
        assertEquals(3_600, bd.toolDefinitionTokens)
        assertEquals(1_400, bd.rulesTokens)
        assertEquals(500, bd.skillsTokens)
        assertEquals(800, bd.mcpTokens)
        assertEquals(1_100, bd.subagentTokens)
        assertEquals(0, bd.summarizedTokens)
        assertTrue(bd.conversationTokens > 0)
        assertEquals(bd.totalTokens, usage.totalTokens)
    }

    @Test
    fun `resolveBudget prefers the current model window then the fallback`() {
        assertEquals(128_000, ContextWindowPolicy.resolveBudget(null, 128_000))
        assertEquals(1, ContextWindowPolicy.resolveBudget(0, 128_000))
        assertEquals(128_000, ContextWindowPolicy.resolveBudget(128_000, 1_000_000))
    }

    @Test
    fun `oversized single turn is split inside the turn instead of kept whole`() {
        // 单个用户轮次自身超预算：最后一个用户轮次包含 10 条大 assistant 消息
        val messages = buildList<HarnessMessage> {
            add(UserMessage("u0", 1, "start"))
            add(AssistantText("a0", 2, "ok"))
            add(UserMessage("u1", 3, "huge task"))
            repeat(10) { index ->
                add(AssistantText("a-$index", 4L + index, "step $index " + "x".repeat(2_000)))
            }
        }

        val keepFrom = ContextWindowPolicy.computeKeepFromIndex(messages, budget = 18_000, systemTokens = 10)

        // 旧行为会把整个巨型轮次保留（keepFrom == 2 起点且 kept 超限）；split-turn 必须切在轮内
        assertTrue(keepFrom > 2)
        val firstKept = messages[keepFrom]
        assertTrue(
            "boundary must be user/assistant/tool_call, was $firstKept",
            firstKept is UserMessage || firstKept is AssistantText || firstKept is ToolCall,
        )
        val keptTokens = messages.drop(keepFrom).sumOf { message ->
            when (message) {
                is UserMessage -> ContextWindowPolicy.estimateTokens(message.text)
                is AssistantText -> ContextWindowPolicy.estimateTokens(message.text)
                is ToolCall -> ContextWindowPolicy.estimateTokens(message.args.toString())
                is ToolResult -> ContextWindowPolicy.estimateTokens(message.output)
                else -> 0
            }
        }
        assertTrue("kept tokens $keptTokens must fit the limit", keptTokens < 18_000 * 0.75)
    }

    @Test
    fun `split turn boundary never separates a tool call from its result`() {
        val messages = buildList<HarnessMessage> {
            add(UserMessage("u0", 1, "start"))
            add(UserMessage("u1", 3, "huge task"))
            repeat(8) { index ->
                add(ToolCall("call-$index", 4L + index * 3, HarnessTool.BASE, kotlinx.serialization.json.buildJsonObject {}))
                add(ToolResult("result-$index", 5L + index * 3, "call-$index", true, "x".repeat(3_000)))
                add(AssistantText("note-$index", 6L + index * 3, "n".repeat(1_500)))
            }
        }

        val keepFrom = ContextWindowPolicy.computeKeepFromIndex(messages, budget = 18_000, systemTokens = 10)
        val kept = messages.drop(keepFrom)

        if (keepFrom > 0) {
            val keptCallIds = kept.filterIsInstance<ToolCall>().mapTo(mutableSetOf()) { it.id }
            assertTrue(
                kept.filterIsInstance<ToolResult>().all { it.toolCallId in keptCallIds },
            )
        }
    }

    @Test
    fun `keepRecentTokens override tightens the retained window`() {
        val messages = buildList<HarnessMessage> {
            repeat(20) { index ->
                add(UserMessage("u-$index", index * 2L, "request $index " + "a".repeat(300)))
                add(AssistantText("a-$index", index * 2L + 1, "answer $index " + "b".repeat(300)))
            }
        }

        val base = ContextWindowPolicy.computeKeepFromIndex(messages, budget = 18_000, systemTokens = 10)
        assertTrue(base > 0)

        val tightened = ContextWindowPolicy.computeKeepFromIndex(
            messages,
            budget = 18_000,
            systemTokens = 10,
            keepRecentTokens = 400,
        )

        assertTrue("tightened($tightened) should be beyond base($base)", tightened > base)
        val keptTokens = messages.drop(tightened).sumOf { message ->
            when (message) {
                is UserMessage -> ContextWindowPolicy.estimateTokens(message.text)
                is AssistantText -> ContextWindowPolicy.estimateTokens(message.text)
                else -> 0
            }
        }
        assertTrue("kept tokens $keptTokens should stay near the 400 cap", keptTokens < 1_600)
    }

    @Test
    fun `keepRecentTokens is inert while the budget still fits`() {
        val messages = buildList<HarnessMessage> {
            repeat(5) { index ->
                add(UserMessage("u-$index", index * 2L, "request $index"))
                add(AssistantText("a-$index", index * 2L + 1, "answer $index"))
            }
        }

        val keepFrom = ContextWindowPolicy.computeKeepFromIndex(
            messages,
            budget = 128_000,
            systemTokens = 10,
            keepRecentTokens = 100,
        )

        assertEquals(0, keepFrom)
    }

    @Test
    fun `per-model reserveTokens override raises the compaction threshold`() {
        val messages = buildList<HarnessMessage> {
            repeat(10) { index ->
                add(UserMessage("u-$index", index * 2L, "request $index " + "a".repeat(600)))
                add(AssistantText("a-$index", index * 2L + 1, "answer $index " + "b".repeat(600)))
            }
        }

        val withDefaultReserve = ContextWindowPolicy.computeKeepFromIndex(
            messages,
            budget = 128_000,
            systemTokens = 10,
        )
        assertEquals(0, withDefaultReserve)

        val withHugeReserve = ContextWindowPolicy.computeKeepFromIndex(
            messages,
            budget = 128_000,
            systemTokens = 10,
            reserveTokens = 90_000,
        )
        assertTrue(withHugeReserve > 0)
    }

    @Test
    fun `stale oversized tool results are compacted with a history read pointer`() {
        // 8 条属于上一轮的结果 + 3 条当前轮（"latest" 之后）的结果。
        // keepRecentResults=2：当前轮 3 条靠轮次语义保护（不是靠下限兜底）。
        val messages = buildList<HarnessMessage> {
            add(UserMessage("u0", 1, "start"))
            repeat(8) { index ->
                val callId = "call-$index"
                add(ToolCall(callId, 2L + index, HarnessTool.MCP, kotlinx.serialization.json.buildJsonObject {}))
                add(ToolResult("result-$index", 3L + index, callId, true, "{\"refs\":[${"e$index,".repeat(100)}]}"))
            }
            add(UserMessage("latest", 20, "now"))
            repeat(3) { index ->
                val callId = "current-call-$index"
                add(ToolCall(callId, 21L + index, HarnessTool.MCP, kotlinx.serialization.json.buildJsonObject {}))
                add(ToolResult("current-result-$index", 22L + index, callId, true, "{\"refs\":[${"c$index,".repeat(100)}]}"))
            }
        }
        val details = messages.filterIsInstance<ToolCall>().associate {
            it.id to ("mcp__browser__snapshot" to it.args)
        }
        fun resultIndexOf(id: String) = messages.indexOfFirst { it.id == id }

        val truncated = ContextWindowPolicy.truncateStaleToolResults(messages, details, keepRecentResults = 2)

        // 条数与顺序不变：NATIVE 协议下丢消息会产生非法 transcript
        assertEquals(messages.size, truncated.size)
        assertEquals(messages.map { it.id }, truncated.map { it.id })
        // 当前轮的 3 条结果原样保留（轮次保护，超出 floor=2 的部分也保留）
        (0..2).forEach { index ->
            val id = "current-result-$index"
            assertEquals(
                (messages[resultIndexOf(id)] as ToolResult).output,
                (truncated[resultIndexOf(id)] as ToolResult).output,
            )
        }
        // 上一轮 8 条全部超过阈值且不受保护，压缩并带 history_read 指针
        (0..7).forEach { index ->
            val id = "result-$index"
            val at = resultIndexOf(id)
            val original = (messages[at] as ToolResult).output
            val output = (truncated[at] as ToolResult).output
            assertTrue("$id should be compacted", output.length < original.length)
            assertTrue(output.contains("history_read(message_id=\"$id"))
        }
    }

    @Test
    fun `stale results below the tool threshold stay verbatim`() {
        val messages = listOf(
            ToolCall("call-read", 1, HarnessTool.READ, kotlinx.serialization.json.buildJsonObject {}),
            ToolResult("short", 2, "call-read", true, "line\n".repeat(5)),
            ToolResult("also-short", 3, "call-read", true, "y".repeat(200)),
        )
        val details = mapOf(
            "call-read" to ("read" to (messages[0] as ToolCall).args),
        )

        val truncated = ContextWindowPolicy.truncateStaleToolResults(messages, details, keepRecentResults = 0)

        // 均低于 read 阈值（800），原样保留且返回同一实例（避免无谓复制）
        assertEquals(messages, truncated)
        assertTrue(truncated === messages)
    }

    // ------------------------------------------------------------------
    // 单一真相源回归：设置页「折叠线预览」必须与实际生效折叠线同源
    // ------------------------------------------------------------------

    @Test
    fun `declared model tokens win over global budget fallback`() {
        // 有模型声明 → 以声明值为准
        assertEquals(1_000_000, ContextWindowPolicy.resolveBudget(1_000_000, 250_938))
        assertEquals(250_938, ContextWindowPolicy.resolveBudget(250_938, 128_000))
        // 无声明 → 回退默认预算
        assertEquals(128_000, ContextWindowPolicy.resolveBudget(null, 128_000))
    }

    @Test
    fun `effective budget is clamped to sane bounds`() {
        // 下界：小于 1 的值被抬到 1（resolveBudget 内部 coerceAtLeast(1)）
        assertEquals(1, ContextWindowPolicy.clampedBudget(0, 0))
        // 上界：超过 MAX_CONTEXT_BUDGET 的声明被钳到上限
        assertEquals(ContextWindowPolicy.MAX_CONTEXT_BUDGET, ContextWindowPolicy.clampedBudget(3_000_000, 128_000))
        // 上限内的正常值原样通过
        assertEquals(150_000, ContextWindowPolicy.clampedBudget(150_000, 128_000))
    }

    @Test
    fun `settings preview folding line matches engine for declared-model scenario`() {
        // 真实场景：模型档案 contextTokens 优先于全局预算；两侧必须走 clampedBudget + 同一套预留。
        val declared = 150_000
        val globalBudget = 80_000
        val ratio = 40
        val systemTokens = ContextWindowPolicy.DEFAULT_SYSTEM_PROMPT_TOKENS

        val engineBudget = ContextWindowPolicy.clampedBudget(declared, globalBudget)
        val previewBudget = ContextWindowPolicy.clampedBudget(declared, globalBudget)
        val engineLine = ContextWindowPolicy.foldingLimitFor(engineBudget, ratio, systemTokens)
        val previewLine = ContextWindowPolicy.foldingLimitFor(previewBudget, ratio, systemTokens)

        assertEquals(declared, engineBudget)
        assertEquals(engineLine, previewLine)

        val ratioOrFraction = minOf((engineBudget * 0.75).toInt(), engineBudget * ratio / 100)
        val expected = minOf(
            ratioOrFraction - systemTokens -
                ContextWindowPolicy.RESERVED_OUTPUT_TOKENS -
                ContextWindowPolicy.TOOL_SCHEMA_RESERVE_TOKENS,
            ContextWindowPolicy.SAFE_GENERATION_CAP,
        )
        assertEquals(expected, engineLine)

        val withoutReserves = ratioOrFraction
        assertTrue(
            "漏扣 system/输出/工具 schema 会让预览高于真实折叠线",
            withoutReserves > engineLine,
        )

        // 反证：若错误地拿被模型声明覆盖掉的全局预算当分母，折叠线会明显偏低。
        val buggyLine = ContextWindowPolicy.foldingLimitFor(globalBudget, ratio, systemTokens)
        assertTrue(
            "修复前的算法必须与真实折叠线不符，否则本测试无判别力",
            buggyLine != engineLine,
        )
    }

    @Test
    fun `preview and engine agree when no model declares context tokens`() {
        val globalBudget = 180_000
        val noDeclared: Int? = null
        val engineBudget = ContextWindowPolicy.clampedBudget(noDeclared, globalBudget)
        val previewBudget = ContextWindowPolicy.clampedBudget(noDeclared, globalBudget)

        assertEquals(globalBudget, engineBudget)
        assertEquals(engineBudget, previewBudget)
        assertEquals(
            ContextWindowPolicy.foldingLimitFor(engineBudget, 40, systemTokens = 500),
            ContextWindowPolicy.foldingLimitFor(previewBudget, 40, systemTokens = 500),
        )
    }

    @Test
    fun `oversized declared window is clamped before folding preview`() {
        val engineBudget = ContextWindowPolicy.clampedBudget(1_000_000, 250_938)
        val previewBudget = ContextWindowPolicy.clampedBudget(1_000_000, 250_938)
        assertEquals(ContextWindowPolicy.MAX_CONTEXT_BUDGET, engineBudget)
        assertEquals(engineBudget, previewBudget)
        assertEquals(
            ContextWindowPolicy.foldingLimitFor(engineBudget, 40, systemTokens = 0),
            ContextWindowPolicy.foldingLimitFor(previewBudget, 40, systemTokens = 0),
        )
    }

    @Test
    fun `foldingLimitFor subtracts system reserve and schema like the engine`() {
        val budget = 18_000
        val ratio = 80
        val systemTokens = 200
        val limit = ContextWindowPolicy.foldingLimitFor(budget, ratio, systemTokens)
        val ratioOrFraction = minOf((budget * 0.75).toInt(), budget * ratio / 100)
        assertEquals(
            ratioOrFraction - systemTokens -
                ContextWindowPolicy.RESERVED_OUTPUT_TOKENS -
                ContextWindowPolicy.TOOL_SCHEMA_RESERVE_TOKENS,
            limit,
        )
        assertTrue(limit > 0)

        val messages = buildList<HarnessMessage> {
            repeat(24) { index ->
                add(UserMessage("u-$index", index * 2L, "request $index " + "a".repeat(400)))
                add(AssistantText("a-$index", index * 2L + 1, "answer $index " + "b".repeat(400)))
            }
        }
        val keepFrom = ContextWindowPolicy.computeKeepFromIndex(
            messages,
            budget = budget,
            systemTokens = systemTokens,
            foldingRatioPercent = ratio,
        )
        assertTrue(keepFrom > 0)
        val kept = messages.drop(keepFrom).sumOf(::estimateMessageTokens)
        assertTrue("kept=$kept should stay within fold line $limit", kept <= limit)
    }

    @Test
    fun `default ratio 100 folding line matches previous engine formula`() {
        val budget = 128_000
        val systemTokens = 10
        val expected = minOf(
            (budget * 0.75).toInt() - systemTokens -
                ContextWindowPolicy.RESERVED_OUTPUT_TOKENS -
                ContextWindowPolicy.TOOL_SCHEMA_RESERVE_TOKENS,
            ContextWindowPolicy.SAFE_GENERATION_CAP,
        )
        assertEquals(
            expected,
            ContextWindowPolicy.foldingLimitFor(budget, ContextWindowPolicy.DEFAULT_FOLDING_RATIO_PERCENT, systemTokens),
        )
    }

    @Test
    fun `panel denominator is the fold line not the declared window`() {
        val declared = 1_000_000
        val budget = ContextWindowPolicy.clampedBudget(declared, 128_000)
        val limit = ContextWindowPolicy.foldingLimitFor(budget, 100, systemTokens = 1_000)
        assertTrue(limit < declared)
        assertTrue(limit <= ContextWindowPolicy.SAFE_GENERATION_CAP)
        assertTrue(limit < budget)
    }

    // ------------------------------------------------------------------
    // 单一真相源回归：用量面板「已用量」必须基于引擎同口径投影（含老工具结果截断）
    // ------------------------------------------------------------------

    @Test
    fun `projectForUsage compacts stale tool results like the engine`() {
        // 构造：1 条老用户轮 + 8 条超大工具结果（不受保护）+ 1 条最新用户轮 + 3 条当前轮结果
        val big = "x".repeat(5_000)
        val messages = buildList<HarnessMessage> {
            add(UserMessage("old", 1, "old request"))
            repeat(8) { index ->
                val callId = "call-$index"
                add(ToolCall(callId, 2L + index, HarnessTool.BASE, kotlinx.serialization.json.buildJsonObject {}))
                add(ToolResult("result-$index", 3L + index, callId, true, big))
            }
            add(UserMessage("latest", 20, "now"))
            repeat(3) { index ->
                val callId = "current-$index"
                add(ToolCall(callId, 21L + index, HarnessTool.BASE, kotlinx.serialization.json.buildJsonObject {}))
                add(ToolResult("current-result-$index", 22L + index, callId, true, big))
            }
        }

        val projected = ContextWindowPolicy.projectForUsage(messages, compactionEnabled = true)

        // 条数与顺序必须不变（NATIVE 协议丢消息会产生非法 transcript）
        assertEquals(messages.size, projected.size)
        assertEquals(messages.map { it.id }, projected.map { it.id })
        // 老轮次结果：最近 4 条 ToolResult 受保护（含 result-7），其余应被投影截断
        val oldResults = projected.filterIsInstance<ToolResult>()
            .filter { it.id.matches(Regex("result-\\d+")) }
        val protectedOld = oldResults.last().id           // takeLast(4) 保护了它
        val compactedOld = oldResults.filter { it.id != protectedOld }
        assertTrue("应存在被压缩的老轮次结果", compactedOld.isNotEmpty())
        compactedOld.forEach {
            assertTrue("${it.id} 应被压缩", it.output.length < big.length)
            assertTrue("${it.id} 应带 history_read 指针", it.output.contains("history_read(message_id="))
        }
        // 受保护的那条原样保留
        assertEquals(big.length, oldResults.last().output.length)
        // 当前轮结果原样保留
        projected.filterIsInstance<ToolResult>()
            .filter { it.id.startsWith("current-result-") }
            .forEach { assertEquals(big.length, it.output.length) }

        // 关闭压缩时完全不动（用户要原始历史）
        val untouched = ContextWindowPolicy.projectForUsage(messages, compactionEnabled = false)
        assertEquals(messages, untouched)
    }

    @Test
    fun `usage after projection is far below unprojected usage`() {
        // 反证锚点：不投影会显著虚高 —— 这正是「面板 457.8K vs 实际发送 141K」的缺陷。
        val big = "y".repeat(6_000)
        val messages = buildList<HarnessMessage> {
            add(UserMessage("old", 1, "old"))
            repeat(12) { index ->
                val callId = "c-$index"
                add(ToolCall(callId, 2L + index, HarnessTool.BASE, kotlinx.serialization.json.buildJsonObject {}))
                add(ToolResult("r-$index", 3L + index, callId, true, big))
            }
            add(UserMessage("latest", 50, "now"))
        }
        val systemTokens = 5_000
        // 大预算 → 不触发整段折叠（keepFrom == 0），差异只来自工具结果截断，便于单独观察。
        val hugeBudget = 10_000_000

        fun usageOf(msgs: List<HarnessMessage>) = ContextWindowPolicy.estimateEffectiveUsage(
            messages = msgs,
            budget = hugeBudget,
            systemTokens = systemTokens,
            compactionEnabled = true,
            systemPromptTokens = systemTokens,
        )

        val unprojected = usageOf(messages).totalTokens
        val projected = usageOf(ContextWindowPolicy.projectForUsage(messages, compactionEnabled = true)).totalTokens

        assertTrue("投影后用量应明显低于未投影（$projected vs $unprojected）", projected < unprojected)
        // 反证锚点：不投影时 old 结果全文计入，投影后应至少省下一半以上
        assertTrue(
            "两者差距必须显著，否则本测试无判别力（$projected vs $unprojected）",
            unprojected - projected > unprojected / 2,
        )
    }

    private fun estimateMessageTokens(message: HarnessMessage): Int = when (message) {
        is UserMessage -> ContextWindowPolicy.estimateTokens(message.text)
        is AssistantText -> ContextWindowPolicy.estimateTokens(message.text) +
            ContextWindowPolicy.estimateTokens(message.reasoning.orEmpty())
        is ToolCall -> ContextWindowPolicy.estimateTokens(message.args.toString()) +
            ContextWindowPolicy.estimateTokens(message.reasoning.orEmpty())
        is ToolResult -> ContextWindowPolicy.estimateTokens(message.output)
        else -> 0
    }
}
