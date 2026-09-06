package top.wkbin.taixu.ui.workflow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import top.wkbin.taixu.core.database.WorkflowRepository
import top.wkbin.taixu.core.model.workflow.BuiltinWorkflows
import top.wkbin.taixu.core.model.workflow.FailurePolicy
import top.wkbin.taixu.core.model.workflow.WorkflowApprovalRequest
import top.wkbin.taixu.core.model.workflow.WorkflowDefinition
import top.wkbin.taixu.core.model.workflow.WorkflowEdge
import top.wkbin.taixu.core.model.workflow.WorkflowNode
import top.wkbin.taixu.core.model.workflow.WorkflowNodeType
import top.wkbin.taixu.core.model.workflow.WorkflowRunStatus
import top.wkbin.taixu.core.model.workflow.WorkflowRuntimeState
import top.wkbin.taixu.core.model.workflow.WorkflowTrigger
import top.wkbin.taixu.harness.workflow.WorkflowRunHandle
import top.wkbin.taixu.harness.workflow.WorkflowScheduler

@HiltViewModel
class WorkflowViewModel @Inject constructor(
    private val repository: WorkflowRepository,
    private val scheduler: WorkflowScheduler,
) : ViewModel() {
    val definitions: StateFlow<List<WorkflowDefinition>> = repository.observeDefinitions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BuiltinWorkflows.all)

    private val _activeState = MutableStateFlow<WorkflowRuntimeState?>(null)
    val activeState = _activeState.asStateFlow()
    private val _approvalRequest = MutableStateFlow<WorkflowApprovalRequest?>(null)
    val approvalRequest = _approvalRequest.asStateFlow()
    private val _editorState = MutableStateFlow<WorkflowEditorUiState?>(null)
    val editorState = _editorState.asStateFlow()

    private var activeHandle: WorkflowRunHandle? = null
    private var observerJob: Job? = null
    private var editHistory: WorkflowEditHistory? = null

    init {
        viewModelScope.launch { repository.ensureBuiltins() }
    }

    fun start(definition: WorkflowDefinition, projectName: String, variables: Map<String, String> = emptyMap()) {
        activeHandle?.cancel()
        observerJob?.cancel()
        val safeProject = projectName.trim().trim('/').takeIf { it.isNotEmpty() }
        val workspace = safeProject?.let { "/workspace/$it" } ?: "/workspace"
        val handle = scheduler.execute(definition, variables, workspace, viewModelScope)
        activeHandle = handle
        observerJob = viewModelScope.launch {
            launch { handle.approvalRequest.collect { _approvalRequest.value = it?.takeIf { request -> request.executionId == handle.state.value.executionId } } }
            handle.state.collect { state ->
                _activeState.value = state
                if (state.status in TERMINAL) repository.saveExecution(state)
            }
        }
    }

    fun decide(nodeId: String, approved: Boolean, variables: Map<String, String> = emptyMap()) {
        activeHandle?.decide(nodeId, approved, variables)
    }

    fun cancel() = activeHandle?.cancel()
    fun closeRun() {
        if (_activeState.value?.status !in TERMINAL) activeHandle?.cancel()
        observerJob?.cancel()
        activeHandle = null
        _activeState.value = null
        _approvalRequest.value = null
    }

    fun createWorkflow() {
        val suffix = UUID.randomUUID().toString().take(8)
        openEditor(
            WorkflowDefinition(
                id = "workflow_$suffix",
                name = "新工作流",
                description = "在画布中添加并连接节点",
                category = "自定义",
                trigger = WorkflowTrigger.Manual(),
                nodes = listOf(
                    WorkflowNode("start", WorkflowNodeType.TRIGGER, "手动触发", canvasX = 80f, canvasY = 160f),
                    WorkflowNode("done", WorkflowNodeType.TERMINAL_OUTPUT, "完成", canvasX = 420f, canvasY = 160f),
                ),
                edges = listOf(WorkflowEdge("edge_start_done", "start", "success", "done")),
            ),
            initiallySaved = false,
        )
    }

    fun edit(definition: WorkflowDefinition) {
        if (definition.isBuiltin) {
            val suffix = UUID.randomUUID().toString().take(8)
            openEditor(
                definition.copy(
                    id = "workflow_$suffix",
                    name = "${definition.name} 副本",
                    isBuiltin = false,
                    trigger = WorkflowTrigger.Manual(),
                    createdAt = 0L,
                    updatedAt = 0L,
                ),
                initiallySaved = false,
            )
        } else {
            openEditor(definition, initiallySaved = true)
        }
    }

    private fun openEditor(definition: WorkflowDefinition, initiallySaved: Boolean) {
        val history = WorkflowEditHistory(definition, initiallySaved)
        editHistory = history
        publishEditor(history, selectedNodeId = definition.nodes.firstOrNull()?.id)
    }

    fun closeEditor() {
        editHistory = null
        _editorState.value = null
    }

    fun selectNode(nodeId: String?) {
        _editorState.value = _editorState.value?.copy(selectedNodeId = nodeId, message = null)
    }

    fun beginConnection(nodeId: String) {
        _editorState.value = _editorState.value?.copy(connectionSourceId = nodeId, selectedNodeId = nodeId, message = "请选择目标节点")
    }

    fun cancelConnection() {
        _editorState.value = _editorState.value?.copy(connectionSourceId = null, message = null)
    }

    fun connectTo(targetNodeId: String) {
        val source = _editorState.value?.connectionSourceId ?: return
        mutate {
            WorkflowGraphEditor.connect(
                it,
                WorkflowEdge("edge_${UUID.randomUUID().toString().take(8)}", source, "success", targetNodeId),
            )
        }
        _editorState.value = _editorState.value?.copy(connectionSourceId = null)
    }

    fun addNode(type: WorkflowNodeType) {
        val state = _editorState.value ?: return
        val suffix = UUID.randomUUID().toString().take(8)
        val node = WorkflowNode(
            id = "node_$suffix",
            type = type,
            title = type.defaultTitle(),
            canvasX = 120f + (state.definition.nodes.size % 4) * 260f,
            canvasY = 120f + (state.definition.nodes.size / 4) * 160f,
            config = type.defaultConfig(),
            failurePolicy = FailurePolicy.ABORT,
        )
        mutate(selectedNodeId = node.id) { WorkflowGraphEditor.addNode(it, node) }
    }

    fun updateMetadata(name: String, description: String, category: String) = mutate {
        it.copy(name = name, description = description, category = category, updatedAt = System.currentTimeMillis())
    }

    fun updateNode(node: WorkflowNode) = mutate(selectedNodeId = node.id) { WorkflowGraphEditor.updateNode(it, node) }

    fun moveNode(nodeId: String, x: Float, y: Float) = mutate(selectedNodeId = nodeId) { definition ->
        val node = definition.nodes.firstOrNull { it.id == nodeId } ?: return@mutate definition
        WorkflowGraphEditor.updateNode(definition, node.copy(canvasX = x.coerceAtLeast(0f), canvasY = y.coerceAtLeast(0f)))
    }

    fun removeSelectedNode() {
        val nodeId = _editorState.value?.selectedNodeId ?: return
        mutate(selectedNodeId = null) { WorkflowGraphEditor.removeNode(it, nodeId) }
    }

    fun updateEdge(edge: WorkflowEdge) = mutate { WorkflowGraphEditor.updateEdge(it, edge) }

    fun removeEdge(edgeId: String) = mutate { WorkflowGraphEditor.removeEdge(it, edgeId) }

    fun undoEdit() {
        val history = editHistory ?: return
        if (history.undo()) publishEditor(history, selectedNodeId = _editorState.value?.selectedNodeId)
    }

    fun redoEdit() {
        val history = editHistory ?: return
        if (history.redo()) publishEditor(history, selectedNodeId = _editorState.value?.selectedNodeId)
    }

    fun saveEditor() {
        val history = editHistory ?: return
        _editorState.value = _editorState.value?.copy(isSaving = true, message = null)
        viewModelScope.launch {
            runCatching { repository.upsert(history.current.copy(updatedAt = System.currentTimeMillis())) }
                .onSuccess {
                    history.markSaved()
                    publishEditor(history, selectedNodeId = _editorState.value?.selectedNodeId, message = "已保存")
                }
                .onFailure { error ->
                    _editorState.value = _editorState.value?.copy(isSaving = false, message = error.message ?: "保存失败")
                }
        }
    }

    fun deleteWorkflow(definition: WorkflowDefinition) {
        if (definition.isBuiltin) return
        viewModelScope.launch { repository.deleteCustom(definition.id) }
    }

    private fun mutate(selectedNodeId: String? = _editorState.value?.selectedNodeId, block: (WorkflowDefinition) -> WorkflowDefinition) {
        val history = editHistory ?: return
        runCatching { block(history.current) }
            .onSuccess { next ->
                history.commit(next)
                publishEditor(history, selectedNodeId)
            }
            .onFailure { error ->
                _editorState.value = _editorState.value?.copy(message = error.message ?: "编辑失败")
            }
    }

    private fun publishEditor(history: WorkflowEditHistory, selectedNodeId: String?, message: String? = null) {
        val connectionSourceId = _editorState.value?.connectionSourceId
            ?.takeIf { id -> history.current.nodes.any { it.id == id } }
        _editorState.value = WorkflowEditorUiState(
            definition = history.current,
            selectedNodeId = selectedNodeId?.takeIf { id -> history.current.nodes.any { it.id == id } },
            connectionSourceId = connectionSourceId,
            canUndo = history.canUndo,
            canRedo = history.canRedo,
            isDirty = history.isDirty,
            message = message,
        )
    }

    private companion object {
        val TERMINAL = setOf(WorkflowRunStatus.SUCCESS, WorkflowRunStatus.FAILED, WorkflowRunStatus.CANCELLED)
    }
}

private fun WorkflowNodeType.defaultTitle(): String = when (this) {
    WorkflowNodeType.TRIGGER -> "触发器"
    WorkflowNodeType.BASH_COMMAND -> "执行命令"
    WorkflowNodeType.PROCESS_SERVICE -> "启动服务"
    WorkflowNodeType.AGENT_INFERENCE -> "智能体推理"
    WorkflowNodeType.SUBAGENT_DELEGATE -> "委派子智能体"
    WorkflowNodeType.TAIXU_BUILD -> "太墟构建"
    WorkflowNodeType.CONDITION_BRANCH -> "条件分支"
    WorkflowNodeType.HUMAN_APPROVAL -> "人工审批"
    WorkflowNodeType.HOST_ACTION -> "宿主动作"
    WorkflowNodeType.TERMINAL_OUTPUT -> "输出结果"
}

private fun WorkflowNodeType.defaultConfig(): Map<String, String> = when (this) {
    WorkflowNodeType.AGENT_INFERENCE -> mapOf(
        "prompt" to "请分析以下上游结果并给出明确结论：\n\${previous.output}",
    )
    WorkflowNodeType.SUBAGENT_DELEGATE -> mapOf(
        "taskName" to "工作流子任务",
        "prompt" to "请完成以下任务并返回结果：\n\${previous.output}",
        "role" to "",
        "writePaths" to "",
    )
    else -> emptyMap()
}
