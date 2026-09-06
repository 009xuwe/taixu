package top.wkbin.taixu.harness.workflow

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import top.wkbin.taixu.core.model.workflow.WorkflowApprovalDecision
import top.wkbin.taixu.core.model.workflow.WorkflowApprovalRequest

@Singleton
class WorkflowApprovalBroker @Inject constructor() {
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<WorkflowApprovalDecision>>()
    private val _currentRequest = MutableStateFlow<WorkflowApprovalRequest?>(null)
    val currentRequest: StateFlow<WorkflowApprovalRequest?> = _currentRequest.asStateFlow()

    suspend fun await(request: WorkflowApprovalRequest): WorkflowApprovalDecision {
        val key = key(request.executionId, request.nodeId)
        val deferred = CompletableDeferred<WorkflowApprovalDecision>()
        check(waiting.putIfAbsent(key, deferred) == null) { "审批请求已存在：$key" }
        _currentRequest.value = request
        return try {
            deferred.await()
        } finally {
            waiting.remove(key)
            if (_currentRequest.value == request) _currentRequest.value = null
        }
    }

    fun decide(executionId: String, nodeId: String, decision: WorkflowApprovalDecision): Boolean =
        waiting[key(executionId, nodeId)]?.complete(decision) == true

    fun cancelExecution(executionId: String) {
        waiting.entries.filter { it.key.startsWith("$executionId:") }.forEach { (_, deferred) ->
            deferred.cancel()
        }
        if (_currentRequest.value?.executionId == executionId) _currentRequest.value = null
    }

    private fun key(executionId: String, nodeId: String) = "$executionId:$nodeId"
}

