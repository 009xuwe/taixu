package top.wkbin.taixu.ui.workflow

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.wkbin.taixu.core.model.workflow.FailurePolicy
import top.wkbin.taixu.core.model.workflow.WorkflowEdge
import top.wkbin.taixu.core.model.workflow.WorkflowNode
import top.wkbin.taixu.core.model.workflow.WorkflowNodeType
import top.wkbin.taixu.core.model.workflow.WorkflowRunStatus
import top.wkbin.taixu.core.model.workflow.WorkflowRuntimeState
import top.wkbin.taixu.ui.components.RuntimeAlertDialog
import top.wkbin.taixu.ui.components.RuntimeButton
import top.wkbin.taixu.ui.components.RuntimeCard
import top.wkbin.taixu.ui.components.RuntimeIcon
import top.wkbin.taixu.ui.components.RuntimeIconName
import top.wkbin.taixu.ui.components.RuntimeOutlinedButton

@Composable
fun WorkflowEditorView(
    state: WorkflowEditorUiState,
    activeRunState: WorkflowRuntimeState? = null,
    onRun: () -> Unit = {},
    onCancelRun: () -> Unit = {},
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
    onAutoLayout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isRunning = activeRunState?.status in setOf(WorkflowRunStatus.RUNNING, WorkflowRunStatus.WAITING_APPROVAL)

    Column(modifier) {
        EditorToolbar(
            state = state,
            activeRunState = activeRunState,
            onAddNode = onAddNode,
            onUndo = onUndo,
            onRedo = onRedo,
            onSave = onSave,
            onAutoLayout = onAutoLayout,
            onRun = onRun,
            onCancelRun = onCancelRun,
        )
        state.message?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.labelMedium,
                color = if (message == "已保存" || message == "请选择目标节点") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val canvas: @Composable (Modifier) -> Unit = { canvasModifier ->
                WorkflowCanvas2D(
                    definition = state.definition,
                    state = activeRunState,
                    editable = !isRunning,
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
                    activeRunState = activeRunState,
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
                // Wide screen: side-by-side layout
                Row(Modifier.fillMaxSize()) {
                    canvas(Modifier.weight(1f).fillMaxSize())
                    VerticalDivider()
                    inspector(Modifier.width(380.dp).fillMaxSize())
                }
            } else {
                // Narrow screen: canvas fills all space, inspector slides up from bottom
                EditorBottomSheetLayout(
                    maxHeight = maxHeight,
                    selectedNodeTitle = state.definition.nodes
                        .firstOrNull { it.id == state.selectedNodeId }?.title,
                    isRunning = isRunning,
                    canvas = canvas,
                    inspector = inspector,
                )
            }
        }
    }
}

/** Peek height when inspector sheet is collapsed */
private val SheetPeekHeight = 56.dp

@Composable
private fun EditorBottomSheetLayout(
    maxHeight: Dp,
    selectedNodeTitle: String?,
    isRunning: Boolean,
    canvas: @Composable (Modifier) -> Unit,
    inspector: @Composable (Modifier) -> Unit,
) {
    var sheetExpanded by remember { mutableStateOf(false) }
    val expandedHeight = maxHeight * 0.65f
    val sheetHeight by animateDpAsState(
        targetValue = if (sheetExpanded) expandedHeight else SheetPeekHeight,
        animationSpec = tween(durationMillis = 280),
        label = "sheetHeight",
    )

    Box(Modifier.fillMaxSize()) {
        // Canvas fills the area with bottom padding for the sheet peek height, so nodes/hints are never obscured
        canvas(Modifier.fillMaxSize().padding(bottom = SheetPeekHeight))

        // Bottom sheet panel
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(sheetHeight)
                .align(Alignment.BottomCenter)
                .shadow(elevation = if (sheetExpanded) 8.dp else 3.dp, shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 2.dp,
        ) {
            Column(Modifier.fillMaxSize()) {
                // Drag handle pill
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = 8.dp, bottom = 4.dp)
                            .size(width = 36.dp, height = 4.dp)
                            .background(
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                                RoundedCornerShape(2.dp),
                            ),
                    )
                }
                // Peek header row — tap to toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { sheetExpanded = !sheetExpanded }
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RuntimeIcon(
                        name = if (sheetExpanded) RuntimeIconName.ChevronDown else RuntimeIconName.ChevronUp,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = when {
                            isRunning && selectedNodeTitle != null -> "● 正在运行 · 节点：$selectedNodeTitle"
                            selectedNodeTitle != null -> "节点参数配置：$selectedNodeTitle"
                            else -> "属性检查面板"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = if (sheetExpanded) 0.12f else 0.22f),
                    ) {
                        Text(
                            text = if (sheetExpanded) "收起面板" else (if (selectedNodeTitle != null) "展开调参" else "展开"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
                HorizontalDivider()
                // Full inspector content — only rendered (and scrollable) when expanded
                if (sheetExpanded) {
                    inspector(Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun EditorToolbar(
    state: WorkflowEditorUiState,
    activeRunState: WorkflowRuntimeState?,
    onAddNode: (WorkflowNodeType) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSave: () -> Unit,
    onAutoLayout: () -> Unit,
    onRun: () -> Unit,
    onCancelRun: () -> Unit,
) {
    var addDialogVisible by remember { mutableStateOf(false) }
    val isRunning = activeRunState?.status in setOf(WorkflowRunStatus.RUNNING, WorkflowRunStatus.WAITING_APPROVAL)

    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 添加节点按钮（弹窗展示各节点详细功能与作用）
        RuntimeButton(
            onClick = { addDialogVisible = true },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            RuntimeIcon(RuntimeIconName.Plus, Modifier.size(16.dp))
            Text("添加节点", maxLines = 1)
        }

        // 运行 / 调试 按钮
        if (isRunning) {
            RuntimeOutlinedButton(
                onClick = onCancelRun,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                RuntimeIcon(RuntimeIconName.Stop, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                Text("停止调试", color = MaterialTheme.colorScheme.error, maxLines = 1)
            }
        } else {
            RuntimeButton(
                onClick = onRun,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            ) {
                RuntimeIcon(RuntimeIconName.Play, Modifier.size(16.dp))
                Text("运行调试", maxLines = 1)
            }
        }

        RuntimeOutlinedButton(onClick = onAutoLayout, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
            Text("自动排版", maxLines = 1)
        }
        RuntimeOutlinedButton(onClick = onUndo, enabled = state.canUndo && !isRunning, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
            Text("撤销", maxLines = 1)
        }
        RuntimeOutlinedButton(onClick = onRedo, enabled = state.canRedo && !isRunning, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
            Text("重做", maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (isRunning) "● 正在运行…" else if (state.isDirty) "未保存" else "已保存",
            style = MaterialTheme.typography.labelSmall,
            color = if (isRunning) MaterialTheme.colorScheme.primary else if (state.isDirty) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        RuntimeOutlinedButton(
            onClick = onSave,
            enabled = !state.isSaving && state.isDirty && !isRunning,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        ) {
            RuntimeIcon(RuntimeIconName.Save, Modifier.size(16.dp))
            Text(if (state.isSaving) "保存中" else "保存", maxLines = 1)
        }
    }

    if (addDialogVisible) {
        NodePickerModal(
            onDismiss = { addDialogVisible = false },
            onSelect = { type ->
                addDialogVisible = false
                onAddNode(type)
            },
        )
    }
}

/**
 * 节点类型选择弹窗：清晰展示各节点名称、语义图标与作用描述
 */
@Composable
private fun NodePickerModal(
    onDismiss: () -> Unit,
    onSelect: (WorkflowNodeType) -> Unit,
) {
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("添加工作流节点", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "点击即可将节点加入画布，选中后可在检查面板配置专属参数：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                WorkflowNodeType.entries.forEach { type ->
                    val meta = type.metadata()
                    Surface(
                        onClick = { onSelect(type) },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.size(36.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    RuntimeIcon(meta.icon, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Column(Modifier.weight(1f)) {
                                Text(meta.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(
                                    meta.summary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            RuntimeOutlinedButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun EditorInspector(
    state: WorkflowEditorUiState,
    activeRunState: WorkflowRuntimeState?,
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
                Text("点击画布中的节点以配置参数或查看调试日志", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            // 如果选中节点有实时/历史执行数据，优先展示调试结果与输出
            val nodeRun = activeRunState?.nodeStates?.get(selected.id)
            if (nodeRun != null) {
                RuntimeCard(
                    modifier = Modifier.fillMaxWidth(),
                    borderColor = statusColor(nodeRun.status).copy(alpha = 0.5f),
                    contentPadding = PaddingValues(14.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("调试运行状态", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Surface(
                                color = statusColor(nodeRun.status).copy(alpha = 0.16f),
                                shape = RoundedCornerShape(50),
                            ) {
                                Text(
                                    text = statusLabel(nodeRun.status),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = statusColor(nodeRun.status),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                        }
                        WorkflowNodeDetails(nodeRun)
                    }
                }
            }

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
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun WorkflowMetadataCard(state: WorkflowEditorUiState, onApply: (String, String, String) -> Unit) {
    var name by remember(state.definition.id, state.definition.name) { mutableStateOf(state.definition.name) }
    var description by remember(state.definition.id, state.definition.description) { mutableStateOf(state.definition.description) }
    var category by remember(state.definition.id, state.definition.category) { mutableStateOf(state.definition.category) }
    RuntimeCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("工作流信息", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            OutlinedTextField(name, { name = it }, label = { Text("工作流名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(category, { category = it }, label = { Text("分类") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(description, { description = it }, label = { Text("说明") }, minLines = 2, maxLines = 3, modifier = Modifier.fillMaxWidth())
            RuntimeOutlinedButton(onClick = { onApply(name, description, category) }, modifier = Modifier.align(Alignment.End)) { Text("应用信息") }
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
    val meta = node.type.metadata()
    var title by remember(node.id, node.title) { mutableStateOf(node.title) }
    var description by remember(node.id, node.description) { mutableStateOf(node.description) }
    var timeout by remember(node.id, node.timeoutSeconds) { mutableStateOf(node.timeoutSeconds.toString()) }
    var policy by remember(node.id, node.failurePolicy) { mutableStateOf(node.failurePolicy) }
    var policyExpanded by remember { mutableStateOf(false) }

    // 结构化配置映射状态（避免手写 raw JSON）
    val configMap = remember(node.id, node.config) {
        mutableStateMapOf<String, String>().apply { putAll(node.config) }
    }
    var showAdvancedJson by remember { mutableStateOf(false) }
    var rawJsonText by remember(node.id, node.config) {
        mutableStateOf(Json { prettyPrint = true }.encodeToString(node.config))
    }
    val parsedConfig = remember(rawJsonText) {
        runCatching { Json.decodeFromString<Map<String, String>>(rawJsonText) }.getOrNull()
    }

    RuntimeCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // 节点标题与类型 Badge
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(50),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        RuntimeIcon(meta.icon, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(meta.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Text(
                    text = node.id,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            // 💡 节点作用与数据流说明卡片（解决不知道节点是干嘛的问题）
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "💡 节点作用：${meta.summary}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = meta.guide,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 基础属性
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("节点标题") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("节点说明 / 备注") },
                minLines = 1,
                maxLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )

            // ⚙️ 专属可视化表单参数区（解决没有调参数的地方的问题）
            Text("参数配置", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

            when (node.type) {
                WorkflowNodeType.BASH_COMMAND -> {
                    OutlinedTextField(
                        value = configMap["command"].orEmpty(),
                        onValueChange = { configMap["command"] = it },
                        label = { Text("Shell 命令行（必填）") },
                        placeholder = { Text("例如：git status --short && git diff") },
                        supportingText = { Text("可使用 \${WORKSPACE_PATH}、\${previous.output} 等变量") },
                        minLines = 2,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = configMap["workingDirectory"].orEmpty(),
                        onValueChange = { configMap["workingDirectory"] = it },
                        label = { Text("工作目录（可选）") },
                        placeholder = { Text("留空默认为工程根目录") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                WorkflowNodeType.PROCESS_SERVICE -> {
                    OutlinedTextField(
                        value = configMap["command"].orEmpty(),
                        onValueChange = { configMap["command"] = it },
                        label = { Text("后台服务启动命令（必填）") },
                        placeholder = { Text("例如：python3 -m http.server 8080") },
                        supportingText = { Text("将在沙箱后台常驻运行，进程 ID 保存于 \${PROCESS_ID}") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = configMap["workingDirectory"].orEmpty(),
                        onValueChange = { configMap["workingDirectory"] = it },
                        label = { Text("工作目录（可选）") },
                        placeholder = { Text("留空默认为工程根目录") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                WorkflowNodeType.AGENT_INFERENCE -> {
                    OutlinedTextField(
                        value = configMap["prompt"].orEmpty(),
                        onValueChange = { configMap["prompt"] = it },
                        label = { Text("任务需求 / 提示词（必填）") },
                        placeholder = { Text("例如：审阅上一节点的差异并提出优化建议") },
                        supportingText = { Text("支持使用 \${previous.output} 引用上游节点输出") },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = configMap["role"].orEmpty(),
                        onValueChange = { configMap["role"] = it },
                        label = { Text("智能体角色（可选）") },
                        placeholder = { Text("例如：Android 资深架构师") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = configMap["modelId"].orEmpty(),
                        onValueChange = { configMap["modelId"] = it },
                        label = { Text("指定模型 ID（可选）") },
                        placeholder = { Text("留空使用默认模型") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                WorkflowNodeType.SUBAGENT_DELEGATE -> {
                    OutlinedTextField(
                        value = configMap["prompt"].orEmpty(),
                        onValueChange = { configMap["prompt"] = it },
                        label = { Text("子任务需求（必填）") },
                        placeholder = { Text("例如：执行单元测试并尝试修复失败用例") },
                        minLines = 3,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = configMap["role"].orEmpty(),
                        onValueChange = { configMap["role"] = it },
                        label = { Text("子智能体角色（可选）") },
                        placeholder = { Text("例如：测试修复助手") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = configMap["taskName"].orEmpty(),
                        onValueChange = { configMap["taskName"] = it },
                        label = { Text("任务标识（可选）") },
                        placeholder = { Text("例如：unit-test-fixer") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                WorkflowNodeType.TAIXU_BUILD -> {
                    var modeExpanded by remember { mutableStateOf(false) }
                    var typeExpanded by remember { mutableStateOf(false) }
                    val currentMode = configMap["mode"] ?: "build"
                    val currentType = configMap["projectType"] ?: "android"

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            RuntimeOutlinedButton(onClick = { modeExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                Text("模式：$currentMode", maxLines = 1)
                            }
                            DropdownMenu(expanded = modeExpanded, onDismissRequest = { modeExpanded = false }) {
                                listOf("build" to "标准构建", "doctor" to "环境体检", "analyze" to "静态分析").forEach { (m, l) ->
                                    DropdownMenuItem(text = { Text("$l ($m)") }, onClick = { configMap["mode"] = m; modeExpanded = false })
                                }
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            RuntimeOutlinedButton(onClick = { typeExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                Text("类型：$currentType", maxLines = 1)
                            }
                            DropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                                listOf("android", "cmake", "cargo", "gradle").forEach { t ->
                                    DropdownMenuItem(text = { Text(t) }, onClick = { configMap["projectType"] = t; typeExpanded = false })
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = configMap["task"] ?: "assembleDebug",
                        onValueChange = { configMap["task"] = it },
                        label = { Text("构建 Task 任务") },
                        placeholder = { Text("例如：assembleDebug 或 build") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                WorkflowNodeType.HOST_ACTION -> {
                    var actionExpanded by remember { mutableStateOf(false) }
                    val currentAction = configMap["action"] ?: "install-apk"

                    Column {
                        RuntimeOutlinedButton(onClick = { actionExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("宿主动作：${if (currentAction == "install-apk") "安装 APK" else currentAction}", maxLines = 1)
                        }
                        DropdownMenu(expanded = actionExpanded, onDismissRequest = { actionExpanded = false }) {
                            listOf("install-apk" to "安装构建生成的 APK", "health" to "宿主环境诊断").forEach { (a, l) ->
                                DropdownMenuItem(text = { Text(l) }, onClick = { configMap["action"] = a; actionExpanded = false })
                            }
                        }
                    }
                    if (currentAction == "install-apk") {
                        OutlinedTextField(
                            value = configMap["artifactFrom"].orEmpty(),
                            onValueChange = { configMap["artifactFrom"] = it },
                            label = { Text("APK 来源节点 ID（可选）") },
                            placeholder = { Text("留空自动从上游节点中查找 APK") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                WorkflowNodeType.HUMAN_APPROVAL -> {
                    OutlinedTextField(
                        value = configMap["prompt"].orEmpty(),
                        onValueChange = { configMap["prompt"] = it },
                        label = { Text("审批确认说明（必填）") },
                        placeholder = { Text("例如：是否确认将修复推送到远程仓库？") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = configMap["requestedVariables"].orEmpty(),
                        onValueChange = { configMap["requestedVariables"] = it },
                        label = { Text("需用户填写的变量（可选）") },
                        placeholder = { Text("多个以逗号分隔，如：TARGET_BRANCH, TOKEN") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                WorkflowNodeType.CONDITION_BRANCH -> {
                    Text(
                        text = "本节点将上游数据向下透传。具体分支路由请点击画布上的各条连线，在连线上设置条件表达式（如 exitCode == 0）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                WorkflowNodeType.TRIGGER -> {
                    Text(
                        text = "工作流的启动入口节点，支持手动触发或外部事件触发。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                WorkflowNodeType.TERMINAL_OUTPUT -> {
                    Text(
                        text = "工作流的终点节点，将自动收集并归档上游所有步骤的控制台输出、错误日志与产物文件。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 高级配置 (JSON) 折叠项
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showAdvancedJson = !showAdvancedJson }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (showAdvancedJson) "收起高级配置 (JSON)" else "展开高级配置 (JSON)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                RuntimeIcon(
                    name = if (showAdvancedJson) RuntimeIconName.ChevronUp else RuntimeIconName.ChevronDown,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            if (showAdvancedJson) {
                OutlinedTextField(
                    value = rawJsonText,
                    onValueChange = {
                        rawJsonText = it
                        parsedConfig?.let { parsed ->
                            configMap.clear()
                            configMap.putAll(parsed)
                        }
                    },
                    label = { Text("原始 JSON 配置") },
                    isError = parsedConfig == null,
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // 运行时策略与超时
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = timeout,
                    onValueChange = { timeout = it.filter(Char::isDigit).take(4) },
                    label = { Text("超时（秒）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                Column(Modifier.weight(1.2f)) {
                    RuntimeOutlinedButton(onClick = { policyExpanded = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Text("失败策略：${policy.editorLabel()}", maxLines = 1)
                    }
                    DropdownMenu(expanded = policyExpanded, onDismissRequest = { policyExpanded = false }) {
                        FailurePolicy.entries.forEach { item ->
                            DropdownMenuItem(text = { Text(item.editorLabel()) }, onClick = { policy = item; policyExpanded = false })
                        }
                    }
                }
            }

            // 操作按钮
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RuntimeOutlinedButton(
                    onClick = if (isConnecting) onCancelConnection else onBeginConnection,
                    modifier = Modifier.weight(1f),
                ) { Text(if (isConnecting) "取消连线" else "连接到…", maxLines = 1) }
                RuntimeButton(
                    onClick = {
                        val finalConfig = if (showAdvancedJson && parsedConfig != null) parsedConfig else configMap.toMap()
                        onApply(
                            node.copy(
                                title = title.trim().ifBlank { node.title },
                                description = description.trim(),
                                timeoutSeconds = timeout.toIntOrNull()?.coerceIn(1, 7200) ?: node.timeoutSeconds,
                                config = finalConfig,
                                failurePolicy = policy,
                            ),
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("应用参数更改", maxLines = 1) }
            }
            RuntimeOutlinedButton(onClick = onRemove, modifier = Modifier.fillMaxWidth()) {
                RuntimeIcon(RuntimeIconName.Trash, Modifier.size(16.dp))
                Text("删除此节点", maxLines = 1)
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
            Text("节点连线 (${edges.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (edges.isEmpty()) Text("暂无连接到其他节点的连线", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            fontWeight = FontWeight.SemiBold,
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
            label = { Text("分支条件表达式（可选）") },
            supportingText = { Text("例如：exitCode == 0、output contains 'OK'，或正则") },
            minLines = 1,
            maxLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RuntimeOutlinedButton(
                onClick = { onRemove(edge.id) },
                modifier = Modifier.weight(1f),
            ) {
                RuntimeIcon(RuntimeIconName.Close, Modifier.size(15.dp))
                Text("删除连线")
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
            ) { Text("应用条件") }
        }
    }
}

data class NodeTypeMeta(
    val type: WorkflowNodeType,
    val label: String,
    val icon: RuntimeIconName,
    val summary: String,
    val guide: String,
)

fun WorkflowNodeType.metadata(): NodeTypeMeta = when (this) {
    WorkflowNodeType.TRIGGER -> NodeTypeMeta(
        type = this,
        label = "触发器",
        icon = RuntimeIconName.Play,
        summary = "流程启动入口，支持手动点击或外部事件触发",
        guide = "作为 DAG 图的起始节点。当您在顶部点击“运行调试”或接收到触发信号时，工作流从这里开始向下执行。",
    )
    WorkflowNodeType.BASH_COMMAND -> NodeTypeMeta(
        type = this,
        label = "Shell 命令行",
        icon = RuntimeIconName.Terminal,
        summary = "在 Linux PRoot 沙箱中执行一条 Shell 命令或脚本",
        guide = "可执行任何沙箱内已安装的命令（如 git, make, python, curl 等）。支持通过 \${WORKSPACE_PATH} 引用工程根目录，或使用 \${previous.output} 获取上游输出。",
    )
    WorkflowNodeType.PROCESS_SERVICE -> NodeTypeMeta(
        type = this,
        label = "后台常驻服务",
        icon = RuntimeIconName.Settings,
        summary = "启动沙箱后台常驻守护进程，不阻塞后续流程继续执行",
        guide = "适用于启动 http-server、Web 服务或监听器进程。进程 ID 会自动保存在变量 \${PROCESS_ID} 中供后续流程引用。",
    )
    WorkflowNodeType.AGENT_INFERENCE -> NodeTypeMeta(
        type = this,
        label = "AI 智能体推理",
        icon = RuntimeIconName.Sparkles,
        summary = "调度 AI 智能体结合任务指令与上游输出分析并生成结果",
        guide = "将任务指令（Prompt）与上游产出交给 AI 智能体处理。支持结合 \${previous.output} 进行代码审查、故障排查或生成修复补丁。",
    )
    WorkflowNodeType.SUBAGENT_DELEGATE -> NodeTypeMeta(
        type = this,
        label = "子智能体委派",
        icon = RuntimeIconName.Hub,
        summary = "委派独立专属子智能体执行更细粒度的任务",
        guide = "委派特定角色的子智能体（如单元测试生成器、构建错误分析员）处理专属任务，隔离主智能体上下文。",
    )
    WorkflowNodeType.TAIXU_BUILD -> NodeTypeMeta(
        type = this,
        label = "太墟离线构建",
        icon = RuntimeIconName.Package,
        summary = "调用沙箱内离线构建引擎编译打包 Android/C++/Rust",
        guide = "调用沙箱内置的 taixu-build 编译引擎。支持 android、cmake、cargo 等类型，可直接产出 APK 安装包或可执行二进制文件。",
    )
    WorkflowNodeType.CONDITION_BRANCH -> NodeTypeMeta(
        type = this,
        label = "条件分支路由",
        icon = RuntimeIconName.Link,
        summary = "透传上游输出，结合引出连线上的条件表达式进行分支跳转",
        guide = "条件分支节点将上游输出原样透传。具体的条件跳转逻辑由从本节点引出的各条连线定义（请在连线上配置 exitCode 或 output 条件）。",
    )
    WorkflowNodeType.HUMAN_APPROVAL -> NodeTypeMeta(
        type = this,
        label = "人工审批把关",
        icon = RuntimeIconName.Alert,
        summary = "暂停流程并弹出确认对话框，等待人工确认或输入参数后继续",
        guide = "重要步骤（如发布、破坏性清理或上线）前的安全把关点。流程执行至此时将自动暂停并弹出弹窗，等待您手动确认或填写必要参数。",
    )
    WorkflowNodeType.HOST_ACTION -> NodeTypeMeta(
        type = this,
        label = "宿主系统动作",
        icon = RuntimeIconName.Android,
        summary = "调用 Android 宿主能力（如直接安装构建产出的 APK 安装包）",
        guide = "穿透沙箱边界调用 Android 本地能力，例如直接唤起系统安装器安装编译完成的 APK，或进行系统健康体检。",
    )
    WorkflowNodeType.TERMINAL_OUTPUT -> NodeTypeMeta(
        type = this,
        label = "结果归档输出",
        icon = RuntimeIconName.Check,
        summary = "工作流终点，自动汇总所有输出日志、耗时与产出文件",
        guide = "DAG 流程的最终收尾节点。将自动收集并展示上游所有阶段的终端标准输出、退出码与生成的产物文件列表。",
    )
}

private fun WorkflowNodeType.editorLabel() = metadata().label

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
