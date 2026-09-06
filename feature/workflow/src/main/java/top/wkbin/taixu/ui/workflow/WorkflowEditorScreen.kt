package top.wkbin.taixu.ui.workflow

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.wkbin.taixu.core.model.workflow.FailurePolicy
import top.wkbin.taixu.core.model.workflow.WorkflowEdge
import top.wkbin.taixu.core.model.workflow.WorkflowNode
import top.wkbin.taixu.core.model.workflow.WorkflowNodeType
import top.wkbin.taixu.ui.components.RuntimeButton
import top.wkbin.taixu.ui.components.RuntimeCard
import top.wkbin.taixu.ui.components.RuntimeIcon
import top.wkbin.taixu.ui.components.RuntimeIconName
import top.wkbin.taixu.ui.components.RuntimeOutlinedButton

@Composable
fun WorkflowEditorView(
    state: WorkflowEditorUiState,
    onSelectNode: (String?) -> Unit,
    onMoveNode: (String, Float, Float) -> Unit,
    onBeginConnection: (String) -> Unit,
    onCancelConnection: () -> Unit,
    onConnect: (String) -> Unit,
    onAddNode: (WorkflowNodeType) -> Unit,
    onUpdateNode: (WorkflowNode) -> Unit,
    onUpdateMetadata: (String, String, String) -> Unit,
    onRemoveNode: () -> Unit,
    onUpdateEdge: (WorkflowEdge) -> Unit,
    onRemoveEdge: (String) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        EditorToolbar(state, onAddNode, onUndo, onRedo, onSave)
        state.message?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.labelMedium,
                color = if (message == "已保存" || message == "请选择目标节点") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp),
            )
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val canvas: @Composable (Modifier) -> Unit = { canvasModifier ->
                WorkflowCanvas2D(
                    definition = state.definition,
                    state = null,
                    editable = true,
                    selectedNodeId = state.selectedNodeId,
                    connectionSourceId = state.connectionSourceId,
                    onNodeSelected = onSelectNode,
                    onNodeMoved = onMoveNode,
                    onConnectionRequested = { _, target -> onConnect(target) },
                    modifier = canvasModifier,
                )
            }
            val inspector: @Composable (Modifier) -> Unit = { inspectorModifier ->
                EditorInspector(
                    state = state,
                    onUpdateNode = onUpdateNode,
                    onUpdateMetadata = onUpdateMetadata,
                    onBeginConnection = onBeginConnection,
                    onCancelConnection = onCancelConnection,
                    onRemoveNode = onRemoveNode,
                    onUpdateEdge = onUpdateEdge,
                    onRemoveEdge = onRemoveEdge,
                    modifier = inspectorModifier,
                )
            }
            if (maxWidth >= 820.dp) {
                Row(Modifier.fillMaxSize()) {
                    canvas(Modifier.weight(1f).fillMaxSize())
                    VerticalDivider()
                    inspector(Modifier.width(360.dp).fillMaxSize())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    canvas(Modifier.weight(1f).fillMaxWidth())
                    HorizontalDivider()
                    inspector(Modifier.fillMaxWidth().height(310.dp))
                }
            }
        }
    }
}

@Composable
private fun EditorToolbar(
    state: WorkflowEditorUiState,
    onAddNode: (WorkflowNodeType) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSave: () -> Unit,
) {
    var addExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            RuntimeButton(onClick = { addExpanded = true }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                RuntimeIcon(RuntimeIconName.Plus, Modifier.size(16.dp))
                Text("添加节点", maxLines = 1)
            }
            DropdownMenu(expanded = addExpanded, onDismissRequest = { addExpanded = false }) {
                EditableNodeTypes.forEach { type ->
                    DropdownMenuItem(
                        text = { Text(type.editorLabel()) },
                        onClick = {
                            addExpanded = false
                            onAddNode(type)
                        },
                    )
                }
            }
        }
        RuntimeOutlinedButton(onClick = onUndo, enabled = state.canUndo, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
            Text("撤销", maxLines = 1)
        }
        RuntimeOutlinedButton(onClick = onRedo, enabled = state.canRedo, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
            Text("重做", maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (state.isDirty) "有未保存更改" else "已保存",
            style = MaterialTheme.typography.labelSmall,
            color = if (state.isDirty) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        RuntimeButton(
            onClick = onSave,
            enabled = !state.isSaving && state.isDirty,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        ) {
            RuntimeIcon(RuntimeIconName.Save, Modifier.size(16.dp))
            Text(if (state.isSaving) "保存中" else "保存", maxLines = 1)
        }
    }
}

@Composable
private fun EditorInspector(
    state: WorkflowEditorUiState,
    onUpdateNode: (WorkflowNode) -> Unit,
    onUpdateMetadata: (String, String, String) -> Unit,
    onBeginConnection: (String) -> Unit,
    onCancelConnection: () -> Unit,
    onRemoveNode: () -> Unit,
    onUpdateEdge: (WorkflowEdge) -> Unit,
    onRemoveEdge: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = state.definition.nodes.firstOrNull { it.id == state.selectedNodeId }
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        WorkflowMetadataCard(state, onUpdateMetadata)
        if (selected == null) {
            RuntimeCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
                Text("选择一个节点以编辑属性", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            NodeInspectorCard(
                node = selected,
                isConnecting = state.connectionSourceId == selected.id,
                onApply = onUpdateNode,
                onBeginConnection = { onBeginConnection(selected.id) },
                onCancelConnection = onCancelConnection,
                onRemove = onRemoveNode,
            )
            ConnectionList(state, selected.id, onUpdateEdge, onRemoveEdge)
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun WorkflowMetadataCard(state: WorkflowEditorUiState, onApply: (String, String, String) -> Unit) {
    var name by remember(state.definition.id, state.definition.name) { mutableStateOf(state.definition.name) }
    var description by remember(state.definition.id, state.definition.description) { mutableStateOf(state.definition.description) }
    var category by remember(state.definition.id, state.definition.category) { mutableStateOf(state.definition.category) }
    RuntimeCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("工作流", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(category, { category = it }, label = { Text("分类") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(description, { description = it }, label = { Text("说明") }, minLines = 2, maxLines = 3, modifier = Modifier.fillMaxWidth())
            RuntimeOutlinedButton(onClick = { onApply(name, description, category) }, modifier = Modifier.align(Alignment.End)) { Text("应用") }
        }
    }
}

@Composable
private fun NodeInspectorCard(
    node: WorkflowNode,
    isConnecting: Boolean,
    onApply: (WorkflowNode) -> Unit,
    onBeginConnection: () -> Unit,
    onCancelConnection: () -> Unit,
    onRemove: () -> Unit,
) {
    var title by remember(node.id, node.title) { mutableStateOf(node.title) }
    var description by remember(node.id, node.description) { mutableStateOf(node.description) }
    var timeout by remember(node.id, node.timeoutSeconds) { mutableStateOf(node.timeoutSeconds.toString()) }
    var configText by remember(node.id, node.config) { mutableStateOf(node.config.entries.joinToString("\n") { "${it.key}=${it.value}" }) }
    var policy by remember(node.id, node.failurePolicy) { mutableStateOf(node.failurePolicy) }
    var policyExpanded by remember { mutableStateOf(false) }
    RuntimeCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(node.type.editorLabel(), style = MaterialTheme.typography.titleSmall)
            Text(node.id, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            OutlinedTextField(title, { title = it }, label = { Text("标题") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(description, { description = it }, label = { Text("说明") }, minLines = 2, maxLines = 3, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                timeout,
                { timeout = it.filter(Char::isDigit).take(4) },
                label = { Text("超时（秒）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                configText,
                { configText = it },
                label = { Text("配置（每行 key=value）") },
                minLines = 3,
                maxLines = 7,
                modifier = Modifier.fillMaxWidth(),
            )
            Column {
                RuntimeOutlinedButton(onClick = { policyExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("失败策略：${policy.editorLabel()}", maxLines = 1)
                }
                DropdownMenu(expanded = policyExpanded, onDismissRequest = { policyExpanded = false }) {
                    FailurePolicy.entries.forEach { item ->
                        DropdownMenuItem(text = { Text(item.editorLabel()) }, onClick = { policy = item; policyExpanded = false })
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RuntimeOutlinedButton(
                    onClick = if (isConnecting) onCancelConnection else onBeginConnection,
                    modifier = Modifier.weight(1f),
                ) { Text(if (isConnecting) "取消连线" else "连接到…", maxLines = 1) }
                RuntimeButton(
                    onClick = {
                        onApply(
                            node.copy(
                                title = title.trim().ifBlank { node.title },
                                description = description.trim(),
                                timeoutSeconds = timeout.toIntOrNull()?.coerceIn(1, 3600) ?: node.timeoutSeconds,
                                config = parseConfig(configText),
                                failurePolicy = policy,
                            ),
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("应用", maxLines = 1) }
            }
            RuntimeOutlinedButton(onClick = onRemove, modifier = Modifier.fillMaxWidth()) {
                RuntimeIcon(RuntimeIconName.Trash, Modifier.size(16.dp))
                Text("删除节点", maxLines = 1)
            }
        }
    }
}

@Composable
private fun ConnectionList(
    state: WorkflowEditorUiState,
    nodeId: String,
    onUpdate: (WorkflowEdge) -> Unit,
    onRemove: (String) -> Unit,
) {
    val edges = state.definition.edges.filter { it.fromNodeId == nodeId || it.toNodeId == nodeId }
    RuntimeCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("连接", style = MaterialTheme.typography.titleSmall)
            if (edges.isEmpty()) Text("暂无连接", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            edges.forEachIndexed { index, edge ->
                if (index > 0) HorizontalDivider()
                ConnectionEditor(edge, onUpdate, onRemove)
            }
        }
    }
}

@Composable
private fun ConnectionEditor(
    edge: WorkflowEdge,
    onUpdate: (WorkflowEdge) -> Unit,
    onRemove: (String) -> Unit,
) {
    var port by remember(edge.id, edge.fromPort) { mutableStateOf(edge.fromPort.lowercase()) }
    var condition by remember(edge.id, edge.conditionExpression) { mutableStateOf(edge.conditionExpression.orEmpty()) }
    var portExpanded by remember(edge.id) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "${edge.fromNodeId} → ${edge.toNodeId}",
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Column {
            RuntimeOutlinedButton(onClick = { portExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text("输出端口：${port.portLabel()}", maxLines = 1)
            }
            DropdownMenu(expanded = portExpanded, onDismissRequest = { portExpanded = false }) {
                EdgePorts.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(item.portLabel()) },
                        onClick = { port = item; portExpanded = false },
                    )
                }
            }
        }
        OutlinedTextField(
            value = condition,
            onValueChange = { condition = it },
            label = { Text("条件表达式（可选）") },
            supportingText = { Text("exitCode == 0、output contains 文本，或正则表达式") },
            minLines = 1,
            maxLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RuntimeOutlinedButton(
                onClick = { onRemove(edge.id) },
                modifier = Modifier.weight(1f),
            ) {
                RuntimeIcon(RuntimeIconName.Close, Modifier.size(15.dp))
                Text("删除")
            }
            RuntimeButton(
                onClick = {
                    onUpdate(
                        edge.copy(
                            fromPort = port,
                            conditionExpression = condition.trim().ifBlank { null },
                        ),
                    )
                },
                modifier = Modifier.weight(1f),
            ) { Text("应用") }
        }
    }
}

private fun parseConfig(raw: String): Map<String, String> = raw.lineSequence().mapNotNull { line ->
    val trimmed = line.trim()
    if (trimmed.isBlank() || trimmed.startsWith('#') || '=' !in trimmed) null
    else trimmed.substringBefore('=').trim().takeIf(String::isNotBlank)?.let { it to trimmed.substringAfter('=').trim() }
}.toMap()

private fun WorkflowNodeType.editorLabel() = when (this) {
    WorkflowNodeType.TRIGGER -> "触发器"
    WorkflowNodeType.BASH_COMMAND -> "命令"
    WorkflowNodeType.PROCESS_SERVICE -> "后台服务"
    WorkflowNodeType.AGENT_INFERENCE -> "智能体推理"
    WorkflowNodeType.SUBAGENT_DELEGATE -> "子智能体"
    WorkflowNodeType.TAIXU_BUILD -> "太墟构建"
    WorkflowNodeType.CONDITION_BRANCH -> "条件分支"
    WorkflowNodeType.HUMAN_APPROVAL -> "人工审批"
    WorkflowNodeType.HOST_ACTION -> "宿主动作"
    WorkflowNodeType.TERMINAL_OUTPUT -> "输出"
}

private fun FailurePolicy.editorLabel() = when (this) {
    FailurePolicy.ABORT -> "终止流程"
    FailurePolicy.CONTINUE -> "继续执行"
    FailurePolicy.RETRY_ONCE -> "重试一次"
    FailurePolicy.ASK_USER -> "询问用户"
}

private fun String.portLabel() = when (lowercase()) {
    "success" -> "成功"
    "failure" -> "失败"
    else -> "任意输出"
}

private val EdgePorts = listOf("output", "success", "failure")

private val EditableNodeTypes = WorkflowNodeType.entries
