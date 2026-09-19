# DeepSeek-Reasonix 对标分析与借鉴清单

> 对标对象：[esengine/DeepSeek-Reasonix](https://github.com/esengine/DeepSeek-Reasonix)（Go 单二进制终端编码智能体，DeepSeek 原生）
> 分析日期：2026-09-19。方法：通读其 `docs/`（SPEC、TOOL_CONTRACT、SESSION_MEMORY_RETRIEVAL、CHECKPOINTS、TOOL_RECOVERY、SUBAGENT_PROFILES、TASK_CONTRACT 等），逐条对照太墟 harness 现状（文中太墟侧均给出文件:行号证据）。

## 结论速览

Reasonix 的架构能力与太墟大体同一档次，压缩、子代理租约、审批校验两边各有所长。它真正的差异化是把 **provider 侧 prefix-cache 稳定性**当成第一工程目标（DeepSeek 缓存命中与未命中价差约一个量级），并围绕它做了一系列机制设计。对照下来，太墟有三个具体缺口（MCP 工具数组不稳定、摘要请求不命中缓存、每轮 recall 注入 system prompt），外加若干子代理/审批语义值得吸收。

| 优先级 | 借鉴条目 | 改动规模 | 预期收益 | 状态 |
| :--- | :--- | :--- | :--- | :--- |
| P0 | 摘要请求复用原 system + tool schemas | 小（改 CompactionSummarizer 请求形状） | 长会话压缩成本降约一个量级 | ✅ 已落地（2026-09-19） |
| P0 | 每轮 recall / routedBlocks 移出 system prompt | 小（移注入位置） | 消除逐轮前缀缓存击穿 | ✅ 已落地（2026-09-19） |
| P1 | MCP 稳定代理 `use_capability` | 大（动 provider 可见面） | 缓存稳定 + 延迟连接缓解启动慢 | ✅ 已落地（⑫：统一代理 + 延迟连接；@ 裁剪移除见⑨） |
| P1 | 子代理完成 claim 的 host 裁定 | 中 | 防止子代理虚报完成 | ✅ 已落地（2026-09-19） |
| P2 | 写租约收缩/扩张语义 | 中 | 补上 shell 写边界 | 待做（移动端语义取舍待定） |
| P2 | 审批"本会话内记住"粒度 | 中 | 减少 REQUEST 模式重复打扰 | ✅ 已落地（2026-09-19） |
| P2 | 记忆召回 BM25 + CJK bigram + 预算 | 中 | 召回质量（表结构已就绪） | ✅ 已落地（2026-09-19） |
| P3 | 中散小件（见第七节） | 小 | 各自独立 | ✅ 全部完成（⑦⑧⑩⑪：字节预算/storm breaker/委托经济学/冲突检测/undo rewind/compress） |

## 落地记录（P0，2026-09-19）

**① 逐轮 recall 移出 system prompt（user 轮持久化后缀）**

- 新增 `harness/prompt/MemoryRecallSelector.kt`：召回逻辑自 SystemPromptBuilder 抽出，渲染为 `<recalled_memory>` 低权威后缀块。
- `SessionTreeStore.appendRecallBlock`：以 `recall_context` entry 紧随用户轮持久化，entry id 由 userMessageId 确定性推导（天然幂等、无查重读放大）；不入消息流，UI/检索不可见，降级安全。
- `CompactedContext.recallBlocks` + `CompactionManager.project()` 解码映射；`ApiContextAssembler` 对最新用户轮**只算一次**并持久化，同轮多轮工具循环字节级复用。
- SystemPromptBuilder：recallSection 移除；技能 @提及与 PromptRouter 规则块改为**全会话累计**（只增不减，枚举上限有界），system prompt 相邻轮字节级一致；MCP 能力章节改用全量活跃清单（新接口 `ActiveMcpToolCatalog` + Hilt @Binds），@ 裁剪不再影响提示词。

**② 压缩摘要请求 cache-replay**

- 新增 `SummaryRequestContext`（systemPrompt / summaryLayer / toolCallMode / visionEnabled / recallBlocks / replayPrefix），由组装器在触发压缩时传入。
- 新增 `ApiMessageProjector`（session 包）：消息→API 投影从组装器原样抽出，主对话与摘要重放共用同一口径（含 DeepSeek reasoning 回传、悬空调用丢弃）。
- `CompactionSummarizer` + 纯函数对象 `SummaryReplayRequests`：摘要请求改为重放原 system + 原摘要层 + 与主请求字节一致的折叠前缀（截断已发生过），末尾仅追加压缩指令——输入命中 provider KV 缓存；估算超窗或请求失败自动回退原独立叙事路径，压缩永不因形状问题中断。

验证：`:harness:testDebugUnitTest` 全绿（新增 `CompactionSummarizerReplayTest` 5 项 + ApiContextAssemblerTest 召回持久化/历史冻结/规则块累计/技能累计 4 项，共 16 项）；`:app:compileDebugKotlin` 通过（Hilt 图完整）。

**③ 子代理完成 claim 的 host 裁定（P1，2026-09-19）**

- 新增 `harness/subagent/SubagentClaim.kt`：文本协议（与 PlannerProtocolParser 同风格）——结论文末 ```json 块提交 `{status, summary, acceptance_criteria}`，type 仅三种（verification 带 command / files 带 paths / manual）。解析缺失或非法 = 无 claim，**旧行为原样保留（fail-open）**；取最后一个合法块。
- host 用 lane transcript 的**成功凭据**逐条核验：verification 对应真实成功命令（精确相等或 ≥8 字符子串，防 "test" 误匹配）、files 对应 write/edit/download 成功写入（经 `normalizeWritePath` 归一化）、manual 永不背书。任一 unsatisfied 的 complete 主张整体降级 partial；**host 永不升格**。
- `SubagentTermination.CLAIM_DOWNGRADED` 新终态；`isSuccess` 随降级转负（父汇总 ⚠️ + 终止原因）；裁定逐条结论渲染进状态头，**原始 claim JSON 不进父上下文**（正文剔除协议块）。只对 host 已判 CONCLUDED 的结论生效。
- `prompts/subagent_task.md` 增补 claim 协议指引（宁 partial 不虚报 complete；无凭据不提交）。

**④ 记忆召回 BM25 升级（P2，2026-09-19）**

- `MemoryRecallSelector` 重写：BM25（k1=1.2/b=0.75）+ **CJK 字符 bigram** 分词（要求真实词重叠）+ 拉丁整词小写；project ×1.2 / session ×0.9 加权；stale 按 updatedAt 年龄降权（<30d ×1.0 / <180d ×0.85 / 其余 ×0.7，降权不删除）；泛化轮次抑制（"继续""ok" 等短语与全泛化 token 不触发）；预算 ≤5 条 + 块总量 4000 字符（持久化字节必须有界）。
- 行为变化：不再"无命中就注入最近记忆"——不相关记忆是噪声，必须常驻的指令应 pinned。
- 持久化契约不变：每用户轮只算一次、`recall_context` entry 冻结字节。

验证（③④）：`:harness:testDebugUnitTest` 全绿（新增 `SubagentClaimTest` 10 项 + `MemoryRecallSelectorTest` 9 项）；`:app:compileDebugKotlin` 通过。

**⑤ 审批"本会话内记住"（P2，2026-09-19）**

- 新增 `harness/approval/SessionApprovalGrants.kt`：纯内存 per-session 授权表（无永久授权，进程退出即消失、会话删除即清理），键为**规范化类别**而非精确参数：`cmd:` 命令前缀（剥环境变量后取前两个 token，前缀匹配）、`dir:` 写目标父目录（目录层级匹配）、`mcp:` server 段、`exact:` 其余操作走 argumentsJson SHA-256。单会话上限 64 条，LRU 淘汰。
- 查：`ToolExecutor.execute` 的 `decision.required` 分支内、创建审批请求之前，命中授权表直接执行（只豁免本条判定，策略引擎其余约束不变）。
- 写：`HarnessLoop.resolveApproval(requestId, approved, rememberForSession)` 批准分支写入授权表；**critical 风险永不记忆**（UI 隐藏 + host 拒绝双防线）。
- UI（feature/chat）：`ApprovalRequestCard` 增加"本会话内同类操作不再询问"复选框（设计系统 `RuntimeCheckbox`，critical 隐藏），回调链 ChatMessageList → ChatScreen → ChatViewModel 沿线扩为三参；webchat 网关走默认参数不受影响。
- 清理：`HarnessLoop` 会话删除路径同步 `revokeSession`。

**⑥ checkpoint 字节预算（P3，2026-09-19）**

- `FileCheckpointPersistence` 增加单会话总量预算（默认 64 MiB，构造器可注入）：MAX_KEPT 只限轮数，快照是整文件 pre-image，长会话反复编辑大文件可轻松破百 MB——移动端私有目录必须有总量护栏。
- 超预算按轮号从最旧开始整轮淘汰（索引 + 内容目录），永不触碰当前轮；内存态本轮内 rewind 仍可用，重启后按磁盘实况恢复。

验证（⑤⑥）：`:harness:testDebugUnitTest` 全绿（新增 `SessionApprovalGrantsTest` 8 项 + `CheckpointByteBudgetTest` 3 项）；`:app:compileDebugKotlin` 通过。

**⑦ storm breaker + 委托经济学 + 批内失败隔离核查（P3，2026-09-19）**

- **批内失败隔离**：核查确认太墟已具备——Phase A 校验失败逐条回写继续、Phase B 每项独立捕获异常，一个工具失败不跳过同批后续调用；审批暂停中止未开始调用是刻意语义，无需改。
- **storm breaker（软收敛提示）**：太墟已有硬熔断（默认连续 8 轮全失败即停）。补上 Reasonix 的软提示层：连续 3 轮工具全失败时，通过既有 steering 队列一次性注入收敛提示（早于硬熔断给模型一次换方法自纠的机会；成功清零、每次运行只提示一次；硬阈值被调低到 3 以下时自然不触发）。
- **委托经济学**：子代理 lane transcript 的 AssistantText 用量聚合为 `SubagentTokenUsage`（输入/缓存命中/输出 + 命中率），渲染进父汇总状态头——委派是否划算有数据可看。未引入价格表，只报事实用量。

验证（⑦）：`:harness:testDebugUnitTest` 全绿（新增 `SubagentTokenUsageTest` 2 项 + `HarnessLoopStormHintTest` 2 项）；`:app:compileDebugKotlin` 通过。

**⑧ checkpoint restore 冲突检测（P3，2026-09-19）**

- `FileSnap` 增加 `afterContent`（改动后凭据）：write 成功后采用入参内容、edit 成功后从盘上重读最终状态，与 pre-image 同受 1MiB 上限约束（常量上移为 `CheckpointStore.SNAPSHOT_MAX_BYTES`）；持久化索引新增 `afterFiles` 段，旧索引按无凭据处理。
- `RewindController.commit` 恢复前逐路径比对当前文件与 `latestAfterImage`：内容不一致 / 文件被外部删除 / 文件膨胀超限 → 记为冲突**跳过恢复**并写进 `RewindResult.conflicts` + note（partial=true），避免 rewind 静默覆盖用户或外部工具的会话外修改。无凭据的路径不检测，维持原行为。
- agent 自己的写入与凭据天然一致，正常 rewind 不受影响；冲突只在真正的会话外改动时出现。

验证（⑧）：`:harness:testDebugUnitTest` 全绿（`RewindControllerTest` +3 项、`CheckpointPersistenceTest` +1 项）；`:app:compileDebugKotlin` 通过。

**⑨ use_capability 第一步：@ 裁剪移出 tools 数组（P1，2026-09-19）**

- `HarnessProviderRunner.resolveEffectiveModel` 不再按 @提及裁剪 `model.dynamicMcpTools`：原实现把 provider 可见 tools 数组在有提及的轮次裁到被提及的 server、下一轮恢复全集——两次字节漂移各击穿一次整个前缀缓存，被击穿重计费的代价（全前缀 × 全价）远大于保留全集 schema 的增量 token。
- @提及仍写入能力挂载事件（UI 展示）；工具可用性只由 MCP server 的启用/连接状态决定。系统提示词同步改为"@ 提及只产生能力挂载记录，不影响工具可用性"。
- provider 可见 tools 数组的剩余变化源只剩 MCP server 启停（既定缓存重置事件）；完整 use_capability 代理（list/inspect/call/decline + 延迟连接）仍需单独规划一轮。

验证（⑨）：`:harness:testDebugUnitTest` 全绿；`:app:compileDebugKotlin` 通过。

**⑩ undo rewind（P3，2026-09-19）**

- commit 时记录实际被改动路径在**改动前**的磁盘状态（`RewindUndoRecord`：applied = rewind 写入的状态作冲突基线，undoSnaps = 与之下标对齐的还原目标；超快照上限的路径不进记录）。
- `RewindController.undoLastRewind`：单层级（消费即失效），冲突基线是 rewind 实际写入的状态——rewind 后智能体再写入（capture 使记录失效）或用户外部改动都会跳过对应路径并报告 conflicts。
- 对话侧不在 undo 范围：CONVERSATION/BOTH 的 fork 不改动原会话，切回原会话即可（原链路永不截断）。
- UI：rewind 成功通知从 Toast 迁到 Snackbar 并附「撤销回滚」动作（Toast 无法承载动作），失败/无锚点仍走 Toast。

验证（⑩）：`:harness:testDebugUnitTest` 全绿（`RewindControllerTest` +3 项：undo 还原、新写入使记录失效、外部改动跳过）；`:app:compileDebugKotlin` 通过。

**⑪ compress 手动压缩工具（P3，2026-09-19）**

- 对齐 Reasonix 的 `compress`：用户明确要求压缩上下文时模型可调用，`mode=before` 折叠锚点轮之前的全部历史（保留锚点轮及之后）、`mode=after` 折叠除当前进行中轮次外的全部已完成历史；`anchor` 必须原样、唯一地摘自某条用户消息（≥8 字符），零匹配/多匹配均拒绝并指引换更长摘录——绝不猜边界。
- 锚点解析抽为 `CompactionManager.resolveCompressAnchor` 纯函数；摘要走与自动压缩相同的 cache-replay 路径；被折叠原文仍可 `history_read` 回读。
- 审批白名单按只读放行；provider schema（`ProviderClient.TOOLS`）/ tools.md 指引 / UI 工具名与 Diff 视图同步注册。


验证（⑪）：`:harness:testDebugUnitTest` 全绿（`CompressAnchorTest` 6 项）；`:app:compileDebugKotlin` 通过。

**⑫ use_capability 统一代理 + MCP 延迟连接（P1，2026-09-19）**

- **provider 可见面**：`mcp__*` 工具 schema 全部退出 tools 数组，只留一个固定 schema 的 `use_capability`（action=list/inspect/call/decline）——MCP 服务增删/启停/发现结果变化都不再改变 provider 可见字节；`BuiltinToolContractTest` 改写为代理时代断言（代理在场 + mcp__ 零泄漏 + 数组逐字节稳定）。
- **延迟连接**：请求路径上的 MCP 发现全部移除（`resolveModel/resolveConfigured` 不再覆盖 dynamicMcpTools、McpManager 启动预热删除）——**服务器进程只在第一次真实 call 其工具时启动**。list/inspect 绝不启动进程：list 读设置库 + 缓存计数；inspect 只读缓存，未连接则提示直接 call。禁用服务的传输清理移入 list 路径的 sweep。
- **分发**：`ToolExecutor.executeCapability` 四动作路由；直接 `mcp__*` 调用保留为兼容路径（历史消息/模型习惯仍可执行）。
- **审批适配**：list/inspect/decline 只读免审；`use_capability(call)` 从 arguments 合成 `mcp__<server>__<tool>` 复用既有浏览器风险矩阵——内置浏览器低风险工具的免审体验不回归。
- **会话授权**：`SessionApprovalGrants` 对 use_capability(call) 按 args.server 记 `mcp:<server>` 键，"本会话内记住"继续生效。
- **提示词**：MCP 能力章节重写为"发现-调用"指引（数据源=设置库启用清单，零发现零进程）；`CapabilityEventWriter` 的 MCP 挂载事件随裁剪移除而停用（@ 对 MCP 可用性无影响）。

验证（⑫）：`:harness:testDebugUnitTest` 全量通过（`BuiltinToolContractTest` 改写为代理断言）；`:app:compileDebugKotlin` 通过。

## 一、太墟已对齐的能力（不要重复建设）

以下维度两边设计等价或太墟更细，对比时确认过，无需动作：

- **压缩主干**。先截断工具输出再判定压缩（`harness/session/ApiContextAssembler.kt:86-126`："只需截断即可回到预算线内的会话，不应再触发整段压缩"）；压缩落库为不可变 `compaction` entry、原始 message 不改写、provider 视图投影生成（`CompactionManager.kt:16,28-66,164-174`）；`history_read(message_id)` 按需回读落库原文（`ToolExecutor.kt:609-628`）；LLM 摘要失败回退机械摘要、压缩永不中断（`CompactionSummarizer.kt:186-192`）。竞态处理（laneLock 内重读对账 + `sourceWatermarkSequence` 水位线自愈，`CompactionManager.kt:44-55,131-142`）比 Reasonix 的描述更细。
- **子代理写租约**。三态租约（空=只读 / `["*"]`=整工作区 / 精确路径白名单）+ 执行期闸门 + 冲突分波（`SubagentLaneContracts.kt:177-210`、`SubagentOrchestrator.kt:364-435`），对应 Reasonix 的 write claims（其 SPEC §3.12）。
- **Checkpoint/Rewind**。CODE/CONVERSATION/BOTH 三段、每用户轮快照、同轮同路径去重、保留 100 轮、会话回滚即 fork（`RewindController.kt:31-77`、`CheckpointStore.kt:72-80,181`、`SessionForkConversationRewinder.kt:25-64`），与其 CHECKPOINTS.md 基本同构。
- **记忆表结构**。`agent_memories` 的 scope/kind/subjectKey 冲突去重/revision/pinned/expiresAt/volatility（`AgentContextEntities.kt:8-59`）与 Reasonix Context Engine v2 的事实模型几乎一一对应——只差检索与写入路径。
- **双模型独立会话**。Planner 独立消息序列 + 字节级前缀稳定提示词（`DualAgentCoordinator.kt:101-130`、`PlannerPromptBuilder.kt:6-12`），Executor 步骤走独立物理 lane，符合其"两个模型的会话永不混用"原则。
- **reasoning effort 按 adapter/model 隔离**。无状态 per-provider 映射（`ReasoningAdapter.kt:26-176`），切模型同步重算压缩预算（`SessionModelSwitcher.kt:47-100`）。

## 二、P0：摘要请求复用 KV cache

**Reasonix 机制**（SPEC §3.6）：压缩摘要请求不是独立 prompt，而是**重放原 system message + 原 tool schemas + 被摘要的 message 前缀，只在末尾追加一条压缩指令**。这个形状下，摘要请求的输入大部分命中 provider 侧 KV 缓存（输出 cap 8192 tokens）。

**太墟现状**：`CompactionSummarizer.kt:241-270` 用独立两条消息 + 自带 `SYSTEM_PROMPT` 的纯文本叙事请求做摘要——输入是全价。压缩发生在长会话，恰恰是输入最大的时候。

**落地建议**：把摘要请求改为三段形状：主对话的 system prompt（`SystemPromptBuilder` 的 pinned 稳定前缀部分即可，不必含逐轮 recall 段）+ 原样复用的 tool schemas + 序列化历史作为对话消息 + 末尾一条 user 压缩指令。输出解析逻辑（结构化摘要 + `<read-files>/<modified-files>` 提取）不变。注意：若 tool schemas 含已断连 MCP 的条目，按当前活跃集发送即可，缓存损失可接受。

## 三、P0：动态内容移出 system prompt

**Reasonix 机制**：自动记忆召回的结果只作为**低权威后缀附加到当轮 user turn**，绝不改写 system prompt 或 tool schema（SESSION_MEMORY_RETRIEVAL.md "Cache and privacy contract" 一节；SPEC §3.6）。任务契约、goal 指令同理，全部走 user turn（TASK_CONTRACT.md）。

**太墟现状**：system prompt 每轮重建（`ApiContextAssembler.kt:63-76`），其中 relevant recall 段逐轮随用户消息变化（`SystemPromptBuilder.kt:104-149`）、`routedBlocks` 按任务上下文路由（`:225-230`）、installedTools/MCP 章节随启停变。虽然可变段刻意排尾、pinned 前缀稳定，但 system 是第 0 条消息——它任何字节变化，其后**全部对话**的缓存都失效。现有缓解只有排序和 `detectDynamicSystemPromptPatterns` 审计（`ContextWindowPolicy.kt:633-675`），是"软保障"。

**落地建议**：把逐轮 recall 段从 system 尾部移到当轮 user message 的后缀（带"以下为背景资料，不改变当前指令"的低权威声明）。`routedBlocks` 中逐任务变化的部分同样处理。system 里只留真正会话级稳定的内容。改动小，收益直接；可配合在 `detectDynamicSystemPromptPatterns` 基础上加一个"相邻两轮 system prompt 字节级 diff 为空"的断言测试。

## 四、P1：MCP 稳定代理 `use_capability`

**Reasonix 机制**（TOOL_CONTRACT.md）：provider 可见面只有**一个固定 schema 的代理工具** `use_capability`（action ∈ `list` / `inspect` / `call` / `decline`），MCP server 增删、工具上下线都**不改变 provider 可见的 name/description/schema/排序**。模型先 `search`/`inspect` 发现能力，再 `call`。附带三个红利：

1. **延迟连接**：server 进程在权限审批通过后才启动（`mcp_connect__<server>` 独立审批身份），未连接的 server 也可以被 inspect（读缓存 schema）；
2. **@ 提及/权限过滤移到 host 侧 dispatch**，不再当轮收窄 tools 数组；
3. 有 CI 测试 `TestBootToolContractMatchesProviderVisibleSurface` 锁住"启动注册表 == provider 请求面"。

**太墟现状**：每请求重新发现 MCP 工具并直接改 provider 可见 tools 数组（`ProviderClient.kt:795-801`），连接/断开 server 即击穿前缀；@ 提及当轮收窄 MCP 工具集（`HarnessProviderRunner.kt:33-51`）；已有缓解仅是按 `(serverId, name)` 排序保证同组字节一致（`buildDynamicTools`，`ProviderClient.kt:1277-1298`）。

**落地建议**：这是唯一需要动 provider 可见面的大改，单独规划一轮。分两步走：第一步先把 @ 提及过滤从 schema 侧移到 dispatch 侧（数组保持全集，未提及的 server 工具在 host 侧拒调），零协议改动即可稳定数组；第二步再评估 `use_capability` 代理（权衡：省缓存 vs 模型多一次 round-trip 发现）。延迟连接可以独立先做——它与太墟长期存在的 MCP 启动慢/发现超时问题（见 KNOWN_ISSUES）同源。

## 五、P1：子代理完成 claim 的 host 裁定

**Reasonix 机制**（SPEC §3.11-3.13）：writer 子代理结束必须调 `complete_subtask` 提交 status + summary + acceptance_criteria（每条附其跑过的命令或改过的路径）。host 在把结果交给父代理前**用自己的 receipts 逐条核验**：`verification` 条目必须对应 host 真实记录过的命令执行，`diff/files` 条目必须是 host 观察到的写路径；核验不背书的条目降级为 `unsatisfied`，持有不可背书条目的报告不能保持 `complete`。**host 永远不升格状态**。另外每次运行记录 `ContextCapsule`（system prompt 来源与哈希、工具域与 schema 哈希、模型/effort、父会话与工具调用 id），"为什么这个 reviewer 没看到某约束"可从记录回答。

**太墟现状**：子代理结果直接注入父上下文（限额 12,000 字符 + 落盘 `.taixu-subagent/<lane>.md`，`SubagentOrchestrator.kt:447-451,539-578`），没有"模型声明 ≠ 事实"的裁定层；结构化终止原因有了，但 acceptance criteria 与 host receipts 之间没有核验。

**落地建议**：在子代理 lane 的结构化终止协议里加 `acceptanceCriteria` 字段（类型：verification 命令 / files 路径 / manual 说明），lane runner 已记录工具执行 receipt（transcript 落库，`SubagentOrchestrator.kt:186-209`），核验是纯本地匹配。降级规则照抄：不可背书 → `unsatisfied`，报告不得标 `complete`。ContextCapsule 可以先做轻量版（把 lane 的 system prompt 哈希 + 工具白名单快照写进 transcript 头）。

## 六、P2 三项

### 6.1 写租约收缩/扩张语义

**Reasonix**（SPEC §3.12）：漏声明 `write_paths` 不判只读，而是**整工作区 claim**（调度器排队，不失败）；纯 path-bound 写之后租约**收缩**到实际触碰的文件，兄弟任务可写别处；一旦发生 bash 或 MCP 写，claim **重新扩为整工作区**；`bash` 仅当 OS 沙箱能把写根绑到 claim 时才保留，否则从子代理注册表里整个移除——"声明路径换来并行，代价是没有沙箱绑定的 host 上失去 bash"。

**太墟现状**：漏声明判只读 + 显式警示（`SubagentLaneContracts.kt:235-239`）；shell 写无法静态判定，仅提示词约束（`:171-174`）。

**建议**：采纳"bash/MCP 写使 claim 扩为整工作区"作为可机械执行的保守规则（base lane 发生任何写类命令 → 运行时把该 lane 的租约升级并让同波冲突者重排队）。漏声明是否改为整工作区排队可再议——移动端算力下"判只读+警示"更省资源，但至少要把整工作区排队作为可选项。

### 6.2 审批"本会话内记住"粒度

**Reasonix**（TOOL_APPROVAL_MODES.md）：审批卡只有三选——allow once / **allow for session**（按规范化的目录、命令前缀、server capability 授权）/ deny；**没有永久授权**；会话授权存内存、可检查可撤销；用权限 revision 计数器与审批提交串行化，旧 revision 的回复作废。

**太墟现状**：只有会话级 `ApprovalMode`（REQUEST/ASSISTED/FULL_ACCESS）+ 单请求状态机（10 分钟 TTL、argsHash、operationId 归属四重校验，`ApprovalResumePolicy.kt:10-69`）——安全性好，但 REQUEST 模式下同类操作逐次打扰，缺中间粒度。

**建议**：加会话内授权表（键：规范化命令前缀 / 目标目录 / MCP server），存内存、随会话结束销毁、设置页可查看可撤销。四重校验保留，授权表只作为 REQUEST 模式下的免打扰记忆，不做跨会话持久——"没有永久授权"这条值得照抄。

### 6.3 记忆召回升级 BM25 + CJK bigram + 预算

**Reasonix**（SESSION_MEMORY_RETRIEVAL.md）：召回用 BM25，**中文按字符 bigram** 匹配（要求真实词重叠而非散落常用字）；泛化轮次（"继续"）不触发召回；project 事实压过等价 global；stale 降权不删除；预算 ≤4 条 / 2400 字符；召回轨迹（query、命中 id/revision、得分、新鲜度、省略数）可查（`/memory recall`）。新鲜度按类型给默认窗口（reference 14/45/45 天，project 30/180/180，user/feedback 90/365/365），`volatility` 可覆盖，`expires_at` 硬过期，`last_verified_at` 续期。

**太墟现状**：召回是 SQL `LIKE '%query%'`（`AgentContextDao.kt:73-80`），无排序、无新鲜度降权、无预算条数控制（上限 32 条偏松）、无召回可观测性。表字段已齐（expiresAt/volatility 都在）。

**建议**：在 Kotlin 侧把候选行取出后做内存 BM25 打分（量级小，无需 FTS 表迁移），bigram 分词、freshness 降权、4 条/2400 字符预算、泛化轮次抑制逐条照抄；recall 段落里加"可能过期、不得覆盖当前指令"的低权威声明。写入侧照抄其自动创建白名单：仅 create-only、project/reference 类型、无密钥特征、无重名时才免审批自动落库，其余仍走 MEMORY 工具 + 审批。

## 七、P3：中散小件（各自独立，按需取用）

| 条目 | Reasonix 机制 | 太墟现状与建议 |
| :--- | :--- | :--- |
| 工具中断执行事实 | 五态：not_started / completed / failed / interrupted-after-start / unknown；call-start 先于工具体落库；unknown 只补一次配对结果；**永不自动重放、不从当前状态合成成功、不装全局只读闸**（TOOL_RECOVERY.md） | 已有 `DanglingToolCallPlanner` / `ToolReplayPolicy`（`harness/effects/`），对照校准两点：dangling 结果是否一次性持久化（而非逐轮动态拼 prompt）、是否明确"中断后先查状态再重副作用"的指引 |
| Planner 协议 fail-closed | planner 限时不出 plan → 整条路由 fail-closed、回滚未完成轮次，executor 不启动（SPEC §3.5） | `PlannerProtocolParser.kt:44-60` 解析失败兜底继续执行（fail-open）。垃圾 plan 静默变执行值得重权衡；至少对"多级容错都失败"的场景改为显式报错终止该次 dual 调用 |
| fleet 依赖图 | 任务项声明 `id` + `depends_on` 两个词的极简图；失败/跳过传播整条下游分支，`fail_fast` 只停"开始新任务"、不中断已运行的 writer（SPEC §3.14） | 太墟工作流 DAG 已有条件边/失败策略（docs/WORKFLOW.md）；"失败跳下游但让在跑的 writer 跑完"这一失败传播语义可对照采纳 |
| 委托经济学 | 用真实 cache 命中价核算子代理是否划算（实测单个子任务均摊 ¥0.017，SPEC §3.17） | `RunMetrics` 已有 token/耗时口径，加 cache 命中拆分即可评估"子代理并行是否真省" |
| compress 手动压缩工具 | 用户显式触发的手动压缩，锚定到一条唯一匹配的用户轮摘录，before/after 两个方向（TOOL_CONTRACT.md） | 太墟压缩全自动；可加一个用户入口复用 CompactionManager |
| checkpoint 补强 | restore 前比对当前 existence/SHA-256/mode，外部改动报冲突不覆盖；undo rewind（回滚后保留 after-image 可再撤销）；1 GiB 软字节预算 + 32 MiB 单文件上限（CHECKPOINTS.md） | 太墟有 1 MiB 单快照上限、无字节预算、无冲突检测。移动端空间敏感，字节预算值得加；冲突检测和 undo 按需 |
| 工具契约 CI 断言 | `TestBootToolContractMatchesProviderVisibleSurface` 锁启动注册表 == provider 请求面 | 已有 `detectDynamicSystemPromptPatterns`；加一条"相邻两轮 tools 数组字节级 diff（排除已知合法变化）"的测试 |
| 风暴熔断 | 连续 3 个等价失败批次后给软收敛提示（不改 schema、不加轮数） | `RoundBudget` 已有轮数预算；失败收敛提示是廉价的补充 |
| 批内失败隔离 | 同批一个工具失败不跳过同批其他独立调用 | 检查 `HarnessToolRoundRunner` 批执行语义是否已如此 |

## 八、明确不适用的部分

Go 单二进制分发、ACP 编辑器协议、桌面 host 协议、Extension sidecar 进程协议（Android 进程模型不适配，MCP 已覆盖工具注入）、OS 级沙箱后端（Seatbelt/bubblewrap/Job Objects——太墟的沙箱边界本来就是 PRoot 环境）、Windows 安装器/签名运维类文档。

## 附：参考资料

**Reasonix 侧**（原始文档见其仓库 `docs/`，本地副本曾下载于 `C:\Users\wkbin\AppData\Local\Temp\reasonix-docs\`，含 SPEC.md 73KB 全文）：

- `TOOL_CONTRACT.md` — use_capability 稳定代理全文、统一启动面、参数校验恢复
- `SPEC.md` §3.5（双模型协作）、§3.6（上下文管理）、§3.11-3.15（子代理 claim/租约/继承/依赖图）
- `SESSION_MEMORY_RETRIEVAL.md` — Context Engine v2 全文（事实模型、召回、缓存与隐私契约）
- `CHECKPOINTS.md`、`TOOL_RECOVERY.md`、`TOOL_APPROVAL_MODES.md`、`TASK_CONTRACT.md`、`SUBAGENT_PROFILES.md`

**太墟侧关键文件**（对照证据，均相对 `harness/src/main/java/top/wkbin/taixu/harness/`）：

- 压缩：`compaction/CompactionManager.kt`、`compaction/CompactionSummarizer.kt`、`session/ApiContextAssembler.kt`、`ContextWindowPolicy.kt`
- MCP：`ProviderClient.kt`（`buildDynamicTools` / `activeMcpToolsOrEmpty`）、`HarnessProviderRunner.kt`
- 子代理：`SubagentOrchestrator.kt`、`subagent/SubagentLaneContracts.kt`、`dual/DualAgentCoordinator.kt`、`dual/PlannerProtocolParser.kt`
- 审批/回滚/记忆：`ApprovalPolicyEngine.kt`、`approval/ApprovalResumePolicy.kt`、`checkpoint/`、`prompt/SystemPromptBuilder.kt`
