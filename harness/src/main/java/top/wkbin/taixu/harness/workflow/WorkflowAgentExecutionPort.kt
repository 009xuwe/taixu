package top.wkbin.taixu.harness.workflow

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import top.wkbin.taixu.core.database.AiModelRepository
import top.wkbin.taixu.core.database.HarnessSessionEntity
import top.wkbin.taixu.core.database.HarnessSessionRepository
import top.wkbin.taixu.core.model.ApprovalMode
import top.wkbin.taixu.harness.SubagentOrchestrator
import top.wkbin.taixu.harness.subagent.SubagentLaneRunner

data class WorkflowAgentRequest(
    val executionId: String,
    val nodeId: String,
    val nodeTitle: String,
    val prompt: String,
    val workspacePath: String,
    val modelId: String? = null,
    val modelVariant: String? = null,
    val role: String = "",
    val department: String = "",
    val agentQuery: String = "",
    val taskName: String = "",
    val writePaths: List<String> = emptyList(),
)

data class WorkflowAgentResult(
    val success: Boolean,
    val output: String,
    val toolCallCount: Int? = null,
)

/** Stable, awaitable Harness boundary consumed by workflow nodes. */
interface WorkflowAgentExecutionPort {
    suspend fun infer(request: WorkflowAgentRequest): WorkflowAgentResult
    suspend fun delegate(request: WorkflowAgentRequest): WorkflowAgentResult
}

/**
 * Runs workflow agents in a durable background session without changing the chat UI's
 * foreground session. Coroutine cancellation is propagated into the lane runner, which
 * records an aborted operation before unwinding.
 */
@Singleton
class HarnessWorkflowAgentExecutionPort @Inject constructor(
    private val sessions: HarnessSessionRepository,
    private val models: AiModelRepository,
    private val laneRunner: SubagentLaneRunner,
    private val subagentOrchestrator: SubagentOrchestrator,
) : WorkflowAgentExecutionPort {
    private val sessionCreationMutex = Mutex()

    override suspend fun infer(request: WorkflowAgentRequest): WorkflowAgentResult {
        val session = ensureSession(request)
        val result = laneRunner.run(
            sessionId = session.id,
            laneName = "subagent:workflow-agent:${request.nodeId}",
            prompt = request.prompt,
            workspace = request.workspacePath,
            modelId = request.modelId ?: session.modelId,
            modelVariant = request.modelVariant ?: session.modelVariant,
        )
        return WorkflowAgentResult(result.success, result.summary, result.toolCallCount)
    }

    override suspend fun delegate(request: WorkflowAgentRequest): WorkflowAgentResult {
        val session = ensureSession(request)
        val args = JsonObject(
            buildMap {
                put("prompt", JsonPrimitive(request.prompt))
                put("taskName", JsonPrimitive(request.taskName.ifBlank { request.nodeTitle }))
                request.role.takeIf(String::isNotBlank)?.let { put("role", JsonPrimitive(it)) }
                request.department.takeIf(String::isNotBlank)?.let { put("department", JsonPrimitive(it)) }
                request.agentQuery.takeIf(String::isNotBlank)?.let { put("agentQuery", JsonPrimitive(it)) }
                request.modelId?.takeIf(String::isNotBlank)?.let { put("model", JsonPrimitive(it)) }
                if (request.writePaths.isNotEmpty()) {
                    put("writePaths", JsonArray(request.writePaths.map(::JsonPrimitive)))
                }
            },
        )
        val (success, output) = subagentOrchestrator.executeSubagents(args, session.id)
        return WorkflowAgentResult(success, output)
    }

    private suspend fun ensureSession(request: WorkflowAgentRequest): HarnessSessionEntity {
        val sessionId = "workflow:${request.executionId}:${request.nodeId}"
        return sessionCreationMutex.withLock {
            sessions.findById(sessionId) ?: run {
                val now = System.currentTimeMillis()
                val activeModel = models.activeModel()
                HarnessSessionEntity(
                    id = sessionId,
                    title = "工作流 · ${request.nodeTitle}",
                    createdAt = now,
                    updatedAt = now,
                    modelId = request.modelId ?: activeModel?.id,
                    modelVariant = request.modelVariant
                        ?: activeModel?.takeIf { request.modelId == null || request.modelId == it.id }?.model?.substringBefore(',')?.trim()?.takeIf(String::isNotBlank),
                    workspace = request.workspacePath,
                    approvalMode = ApprovalMode.ASSISTED.id,
                ).also { sessions.upsert(it) }
            }
        }
    }
}
