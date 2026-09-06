package top.wkbin.taixu.harness.workflow

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wkbin.taixu.core.model.workflow.FailurePolicy
import top.wkbin.taixu.core.model.workflow.NodeExecutionOutput
import top.wkbin.taixu.core.model.workflow.NodeRunStatus
import top.wkbin.taixu.core.model.workflow.WorkflowDefinition
import top.wkbin.taixu.core.model.workflow.WorkflowEdge
import top.wkbin.taixu.core.model.workflow.WorkflowNode
import top.wkbin.taixu.core.model.workflow.WorkflowNodeType
import top.wkbin.taixu.core.model.workflow.WorkflowRunStatus
import top.wkbin.taixu.core.model.workflow.WorkflowRuntimeContext
import top.wkbin.taixu.core.model.workflow.WorkflowValidator

class WorkflowSchedulerTest {
    @Test
    fun rejectsCycles() {
        val workflow = definition(
            nodes = listOf(node("a"), node("b")),
            edges = listOf(edge("a", "b"), edge("b", "a")),
        )
        assertTrue(WorkflowValidator.validate(workflow).any { it.message.contains("DAG") })
    }

    @Test
    fun runsIndependentReadyNodesConcurrently() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val executor = fakeExecutor { _, _ ->
            val now = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, now) }
            delay(80)
            active.decrementAndGet()
            NodeExecutionOutput(NodeRunStatus.SUCCESS)
        }
        val scheduler = WorkflowScheduler(setOf(executor), WorkflowApprovalBroker())
        val handle = scheduler.execute(
            definition(nodes = listOf(node("a"), node("b"))),
            emptyMap(),
            "/workspace/test",
            this,
        )
        val result = handle.state.first { it.status in TERMINAL }
        assertEquals(WorkflowRunStatus.SUCCESS, result.status)
        assertEquals(2, peak.get())
    }

    @Test
    fun skipsUnselectedBranchAndContinuesJoin() = runBlocking {
        val executor = fakeExecutor { node, _ ->
            NodeExecutionOutput(NodeRunStatus.SUCCESS, exitCode = if (node.id == "branch") 0 else 0, textOutput = "ok")
        }
        val scheduler = WorkflowScheduler(setOf(executor), WorkflowApprovalBroker())
        val workflow = definition(
            nodes = listOf(node("branch"), node("yes"), node("no"), node("join")),
            edges = listOf(
                WorkflowEdge("e1", "branch", "success", "yes", conditionExpression = "output contains ok"),
                WorkflowEdge("e2", "branch", "success", "no", conditionExpression = "output contains missing"),
                edge("yes", "join"),
                edge("no", "join"),
            ),
        )
        val result = scheduler.execute(workflow, emptyMap(), "/workspace/test", this).state.first { it.status in TERMINAL }
        assertEquals(NodeRunStatus.SKIPPED, result.nodeStates.getValue("no").status)
        assertEquals(NodeRunStatus.SUCCESS, result.nodeStates.getValue("join").status)
    }

    @Test
    fun routesFailurePortToRecoveryBranch() = runBlocking {
        val executor = fakeExecutor { node, _ ->
            if (node.id == "source") {
                NodeExecutionOutput(NodeRunStatus.FAILED, exitCode = 7, textOutput = "network timeout", error = "timeout")
            } else {
                NodeExecutionOutput(NodeRunStatus.SUCCESS)
            }
        }
        val scheduler = WorkflowScheduler(setOf(executor), WorkflowApprovalBroker())
        val workflow = definition(
            nodes = listOf(
                node("source").copy(failurePolicy = FailurePolicy.CONTINUE),
                node("recover"),
                node("normal"),
            ),
            edges = listOf(
                WorkflowEdge("recover-edge", "source", "failure", "recover", conditionExpression = "output contains timeout"),
                WorkflowEdge("normal-edge", "source", "success", "normal"),
            ),
        )

        val result = scheduler.execute(workflow, emptyMap(), "/workspace/test", this).state.first { it.status in TERMINAL }
        assertEquals(WorkflowRunStatus.FAILED, result.status)
        assertEquals(NodeRunStatus.SUCCESS, result.nodeStates.getValue("recover").status)
        assertEquals(NodeRunStatus.SKIPPED, result.nodeStates.getValue("normal").status)
    }

    @Test
    fun retriesOnce() = runBlocking {
        val calls = AtomicInteger()
        val executor = fakeExecutor { _, _ ->
            if (calls.incrementAndGet() == 1) NodeExecutionOutput(NodeRunStatus.FAILED, error = "first")
            else NodeExecutionOutput(NodeRunStatus.SUCCESS)
        }
        val scheduler = WorkflowScheduler(setOf(executor), WorkflowApprovalBroker())
        val workflow = definition(nodes = listOf(node("retry").copy(failurePolicy = FailurePolicy.RETRY_ONCE)))
        val result = scheduler.execute(workflow, emptyMap(), "/workspace/test", this).state.first { it.status in TERMINAL }
        assertEquals(WorkflowRunStatus.SUCCESS, result.status)
        assertEquals(2, calls.get())
    }

    private fun fakeExecutor(block: suspend (WorkflowNode, WorkflowRuntimeContext) -> NodeExecutionOutput) = object : NodeExecutor {
        override val supportedTypes = setOf(WorkflowNodeType.BASH_COMMAND)
        override suspend fun execute(
            node: WorkflowNode,
            context: WorkflowRuntimeContext,
            onProgress: suspend (NodeRunStatus, String) -> Unit,
        ) = block(node, context)
    }

    private fun definition(nodes: List<WorkflowNode>, edges: List<WorkflowEdge> = emptyList()) =
        WorkflowDefinition("test", "Test", nodes = nodes, edges = edges)

    private fun node(id: String) = WorkflowNode(id, WorkflowNodeType.BASH_COMMAND, id)
    private fun edge(from: String, to: String) = WorkflowEdge("$from-$to", from, "success", to)

    private companion object {
        val TERMINAL = setOf(WorkflowRunStatus.SUCCESS, WorkflowRunStatus.FAILED, WorkflowRunStatus.CANCELLED)
    }
}
