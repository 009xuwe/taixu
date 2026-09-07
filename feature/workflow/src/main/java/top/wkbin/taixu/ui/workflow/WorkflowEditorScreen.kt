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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.wkbin.taixu.core.model.workflow.FailurePolicy
import top.wkbin.taixu.core.model.workflow.NodeRunStatus
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
    onRemoveNodeById: (String) -> Unit = {},
    onConnectNodes: (String, String, String, String?) -> Unit = { _, _, _, _ -> },
    onDisconnectNodes: (String, String) -> Unit = { _, _ -> },
    onDisconnectAllForNode: (String) -> Unit = {},
    onUpdateEdge: (WorkflowEdge) -> Unit,
    onRemoveEdge: (String) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSave: () -> Unit,
    onAutoLayout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isRunning = activeRunState?.status in setOf(WorkflowRunStatus.RUNNING, WorkflowRunStatus.WAITING_APPROVAL)
    var sheetExpanded by remember { mutableStateOf(false) }
    var showRunConsole by remember { mutableStateOf(false) }
    var nodePendingDelete by remember { mutableStateOf<WorkflowNode?>(null) }

    // 选中节点时自动展开调参面板
    LaunchedEffect(state.selectedNodeId) {
        if (state.selectedNodeId != null) {
            sheetExpanded = true
        }
    }

    Column(modifier) {
        EditorToolbar(
            state = state,
            activeRunState = activeRunState,
            onAddNode = onAddNode,
            onDeleteSelectedNode = {
                val selected = state.definition.nodes.firstOrNull { it.id == state.selectedNodeId }
                if (selected != null) nodePendingDelete = selected
            },
            onToggleConsole = { showRunConsole = true },
            onUndo = onUndo,
            onRedo = onRedo,
            onSave = onSave,
            onAutoLayout = onAutoLayout,
            onRun = {
                onRun()
                showRunConsole = true
            },
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
                    onBeginConnection = onBeginConnection,
                    onRemoveNode = { nodeId ->
                        val target = state.definition.nodes.firstOrNull { it.id == nodeId }
                        if (target != null) nodePendingDelete = target
                    },
                    onConfigureNode = { nodeId ->
                        onSelectNode(nodeId)
                        sheetExpanded = true
                    },
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
                    onRemoveNode = {
                        val selected = state.definition.nodes.firstOrNull { it.id == state.selectedNodeId }
                        if (selected != null) nodePendingDelete = selected
                    },
                    onConnectNodes = onConnectNodes,
                    onDisconnectAllForNode = onDisconnectAllForNode,
                    onUpdateEdge = onUpdateEdge,
                    onRemoveEdge = onRemoveEdge,
                    onOpenConsole = { showRunConsole = true },
                    modifier = inspectorModifier,
                )
            }

            if (maxWidth >= 820.dp) {
                Row(Modifier.fillMaxSize()) {
                    canvas(Modifier.weight(1f).fillMaxSize())
                    VerticalDivider()
                    inspector(Modifier.width(400.dp).fillMaxSize())
                }
            } else {
                EditorBottomSheetLayout(
                    maxHeight = maxHeight,
                    selectedNodeTitle = state.definition.nodes.firstOrNull { it.id == state.selectedNodeId }?.title,
                    sheetExpanded = sheetExpanded,
                    onSheetExpandedChange = { sheetExpanded = it },
                    isRunning = isRunning,
                    canvas = canvas,
                    inspector = inspector,
                )
            }
        }
    }

    // 节点删除确认弹窗
    nodePendingDelete?.let { target ->
        RuntimeAlertDialog(
            onDismissRequest = { nodePendingDelete = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RuntimeIcon(RuntimeIconName.Trash, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                    Text("确认删除节点", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Text(
                    text = "是否确定删除「${target.title}」？\n与该节点相连的所有输入和输出连线也将被一并断开移除。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                RuntimeButton(
                    onClick = {
                        val id = target.id
                        nodePendingDelete = null
                        if (state.selectedNodeId == id) onRemoveNode() else onRemoveNodeById(id)
                    },
                ) {
                    Text("确认删除")
                }
            },
            dismissButton = {
                RuntimeOutlinedButton(onClick = { nodePendingDelete = null }) {
                    Text("取消")
                }
            },
        )
    }

    // 运行日志与控制台弹窗
    if (showRunConsole && activeRunState != null) {
        RunConsoleModal(
            activeRunState = activeRunState,
            onDismiss = { showRunConsole = false },
            onCancelRun = onCancelRun,
            onRerun = onRun,
        )
    }
}

private val SheetPeekHeight = 56.dp

@Composable
private fun EditorBottomSheetLayout(
    maxHeight: Dp,
    selectedNodeTitle: String?,
    sheetExpanded: Boolean,
    onSheetExpandedChange: (Boolean) -> Unit,
    isRunning: Boolean,
    canvas: @Composable (Modifier) -> Unit,
    inspector: @Composable (Modifier) -> Unit,
) {
    val expandedHeight = maxHeight * 0.65f
    val sheetHeight by animateDpAsState(
        targetValue = if (sheetExpanded) expandedHeight else SheetPeekHeight,
        animationSpec = tween(durationMillis = 280),
        label = "sheetHeight",
    )

    Box(Modifier.fillMaxSize()) {
        canvas(Modifier.fillMaxSize().padding(bottom = SheetPeekHeight))

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

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSheetExpandedChange(!sheetExpanded) }
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
                            selectedNodeTitle != null -> "节点参数与连线：$selectedNodeTitle"
                            else -> "工作流属性检查面板"
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
    onDeleteSelectedNode: () -> Unit,
    onToggleConsole: () -> Unit,
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
        RuntimeButton(
            onClick = { addDialogVisible = true },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            RuntimeIcon(RuntimeIconName.Plus, Modifier.size(16.dp))
            Text("添加节点", maxLines = 1)
        }

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

        if (activeRunState != null) {
            RuntimeOutlinedButton(
                onClick = onToggleConsole,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (isRunning) MaterialTheme.colorScheme.primary else runStatusColor(activeRunState.status),
                    modifier = Modifier.size(8.dp),
                ) {}
                Spacer(Modifier.width(6.dp))
                RuntimeIcon(RuntimeIconName.Terminal, Modifier.size(15.dp))
                Text(if (isRunning) "控制台 (运行中)" else "执行日志", maxLines = 1)
            }
        }

        if (state.selectedNodeId != null && !isRunning) {
            RuntimeOutlinedButton(
                onClick = onDeleteSelectedNode,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            ) {
                RuntimeIcon(RuntimeIconName.Trash, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.error)
                Text("删除节点", color = MaterialTheme.colorScheme.error, maxLines = 1)
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
        Spacer(Modifier.width(6.dp))
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

@Composable
private fun RunConsoleModal(
    activeRunState: WorkflowRuntimeState,
    onDismiss: () -> Unit,
    onCancelRun: () -> Unit,
    onRerun: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val isRunning = activeRunState.status in setOf(WorkflowRunStatus.RUNNING, WorkflowRunStatus.WAITING_APPROVAL)
    val startedAt = activeRunState.startedAt ?: 0L
    val finishedAt = activeRunState.finishedAt ?: 0L
    val durationMs = if (finishedAt > 0L && startedAt > 0L) {
        finishedAt - startedAt
    } else if (startedAt > 0L) {
        (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
    } else {
        0L
    }

    val consoleOutput = remember(activeRunState) {
        buildString {
            appendLine("=== 太墟工作流「${activeRunState.definition.name}」控制台输出 ===")
            appendLine("全局状态: ${runStatusLabel(activeRunState.status)}  |  执行耗时: ${durationMs}ms")
            activeRunState.error?.let {
                appendLine("❌ 异常信息: $it")
            }
            appendLine()
            activeRunState.definition.nodes.forEach { node ->
                val run = activeRunState.nodeStates[node.id]
                val status = run?.status ?: NodeRunStatus.PENDING
                appendLine("--------------------------------------------------")
                appendLine("[${statusLabel(status)}] 节点: ${node.title} (${node.id})")
                val progress = run?.progressMessage
                val textOutput = run?.output?.textOutput
                val errorOutput = run?.output?.error
                if (!progress.isNullOrBlank()) {
                    appendLine("进度: $progress")
                }
                if (!textOutput.isNullOrBlank()) {
                    appendLine(textOutput)
                }
                if (!errorOutput.isNullOrBlank()) {
                    appendLine("stderr: $errorOutput")
                }
            }
            appendLine("--------------------------------------------------")
            appendLine("=== 执行输出结束 ===")
        }
    }

    val scrollState = rememberScrollState()
    LaunchedEffect(consoleOutput) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RuntimeIcon(RuntimeIconName.Terminal, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Text("运行调试控制台", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Surface(
                    color = runStatusColor(activeRunState.status).copy(alpha = 0.16f),
                    shape = RoundedCornerShape(50),
                ) {
                    Text(
                        text = runStatusLabel(activeRunState.status),
                        style = MaterialTheme.typography.labelSmall,
                        color = runStatusColor(activeRunState.status),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    activeRunState.definition.nodes.forEach { node ->
                        val nodeRun = activeRunState.nodeStates[node.id]
                        val st = nodeRun?.status ?: NodeRunStatus.PENDING
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = statusColor(st).copy(alpha = 0.14f),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = statusIconText(st),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = statusColor(st),
                                )
                                Text(
                                    text = node.title,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = statusColor(st),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }

                Surface(
                    color = Color(0xFF0F172A),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp),
                ) {
                    Box(Modifier.fillMaxSize().padding(10.dp)) {
                        Text(
                            text = consoleOutput,
                            color = Color(0xFFE2E8F0),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            modifier = Modifier.verticalScroll(scrollState),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RuntimeOutlinedButton(onClick = {
                    clipboard.setText(AnnotatedString(consoleOutput))
                }) {
                    Text("复制日志")
                }
                if (isRunning) {
                    RuntimeOutlinedButton(onClick = onCancelRun) {
                        Text("停止运行", color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    RuntimeButton(onClick = onRerun) {
                        Text("重新运行")
                    }
                }
            }
        },
        dismissButton = {
            RuntimeOutlinedButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

private fun statusIconText(status: NodeRunStatus) = when (status) {
    NodeRunStatus.SUCCESS -> "✔"
    NodeRunStatus.FAILED -> "✘"
    NodeRunStatus.RUNNING, NodeRunStatus.STREAMING -> "●"
    NodeRunStatus.SKIPPED -> "↷"
    NodeRunStatus.CANCELLED -> "⊘"
    NodeRunStatus.WAITING_APPROVAL -> "🛡"
    NodeRunStatus.IDLE, NodeRunStatus.PENDING -> "⌛"
}

@Composable
private fun runStatusColor(status: WorkflowRunStatus): Color = when (status) {
    WorkflowRunStatus.SUCCESS -> Color(0xFF2E7D32)
    WorkflowRunStatus.FAILED, WorkflowRunStatus.CANCELLED -> MaterialTheme.colorScheme.error
    WorkflowRunStatus.RUNNING, WorkflowRunStatus.WAITING_APPROVAL -> MaterialTheme.colorScheme.primary
    WorkflowRunStatus.IDLE -> MaterialTheme.colorScheme.outline
}

private fun runStatusLabel(status: WorkflowRunStatus): String = when (status) {
    WorkflowRunStatus.IDLE -> "未运行"
    WorkflowRunStatus.RUNNING -> "运行中"
    WorkflowRunStatus.WAITING_APPROVAL -> "等待审批"
    WorkflowRunStatus.SUCCESS -> "执行成功"
    WorkflowRunStatus.FAILED -> "执行失败"
    WorkflowRunStatus.CANCELLED -> "已取消"
}


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
                    text = "选择要加入画布的节点类型，创建后可在检查面板配置参数：",
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
    onConnectNodes: (String, String, String, String?) -> Unit,
    onDisconnectAllForNode: (String) -> Unit,
    onUpdateEdge: (WorkflowEdge) -> Unit,
    onRemoveEdge: (String) -> Unit,
    onOpenConsole: () -> Unit,
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
                Text("请点击画布中的节点以配置参数、管理连线或查看执行日志", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            val nodeRun = activeRunState?.nodeStates?.get(selected.id)
            if (nodeRun != null) {
                RuntimeCard(
                    modifier = Modifier.fillMaxWidth(),
                    borderColor = statusColor(nodeRun.status).copy(alpha = 0.5f),
                    contentPadding = PaddingValues(14.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("调试运行状态", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
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
                        RuntimeOutlinedButton(onClick = onOpenConsole, modifier = Modifier.fillMaxWidth()) {
                            RuntimeIcon(RuntimeIconName.Terminal, Modifier.size(16.dp))
                            Text("打开完整终端控制台日志", maxLines = 1)
                        }
                    }
                }
            }

            NodeInspectorCard(
                state = state,
                node = selected,
                isConnecting = state.connectionSourceId == selected.id,
                onApply = onUpdateNode,
                onBeginConnection = { onBeginConnection(selected.id) },
                onCancelConnection = onCancelConnection,
                onRemove = onRemoveNode,
                onConnectNodes = onConnectNodes,
                onRemoveEdge = onRemoveEdge,
            )

            NodeConnectionsCard(
                state = state,
                node = selected,
                onConnectNodes = onConnectNodes,
                onDisconnectAll = onDisconnectAllForNode,
                onUpdateEdge = onUpdateEdge,
                onRemoveEdge = onRemoveEdge,
            )
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun WorkflowMetadataCard(state: WorkflowEditorUiState, onApply: (String, String, String) -> Unit) {
    var name by remember(state.definition.id, state.definition.name) { mutableStateOf(state.definition.name) }
    var description by remember(state.definition.id, state.definition.description) { mutableStateOf(state.definition.description) }
    var category by remember(state.definition.id, state.definition.category) { mutableStateOf(state.definition.category) }
    var expanded by remember { mutableStateOf(false) }

    RuntimeCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("工作流信息", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    text = if (expanded) "收起" else "修改",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (expanded) {
                OutlinedTextField(name, { name = it }, label = { Text("工作流名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(category, { category = it }, label = { Text("分类") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(description, { description = it }, label = { Text("说明") }, minLines = 2, maxLines = 3, modifier = Modifier.fillMaxWidth())
                RuntimeOutlinedButton(onClick = { onApply(name, description, category) }, modifier = Modifier.align(Alignment.End)) { Text("应用信息") }
            } else {
                Text(
                    text = "${name} · [${category}] - ${description.ifBlank { "暂无说明" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun NodeInspectorCard(
    state: WorkflowEditorUiState,
    node: WorkflowNode,
    isConnecting: Boolean,
    onApply: (WorkflowNode) -> Unit,
    onBeginConnection: () -> Unit,
    onCancelConnection: () -> Unit,
    onRemove: () -> Unit,
    onConnectNodes: (String, String, String, String?) -> Unit,
    onRemoveEdge: (String) -> Unit,
) {
    val meta = node.type.metadata()
    var title by remember(node.id, node.title) { mutableStateOf(node.title) }
    var description by remember(node.id, node.description) { mutableStateOf(node.description) }
    var timeout by remember(node.id, node.timeoutSeconds) { mutableStateOf(node.timeoutSeconds.toString()) }
    var policy by remember(node.id, node.failurePolicy) { mutableStateOf(node.failurePolicy) }
    var policyExpanded by remember { mutableStateOf(false) }

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
                        Text(meta.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
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
                RuntimeOutlinedButton(
                    onClick = onRemove,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    RuntimeIcon(RuntimeIconName.Trash, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                    Text("删除", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
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
                label = { Text("节点备注 / 说明") },
                minLines = 1,
                maxLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("核心参数配置", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

            when (node.type) {
                WorkflowNodeType.BASH_COMMAND -> {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFF06B6D4).copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                RuntimeIcon(RuntimeIconName.Terminal, Modifier.size(16.dp), tint = Color(0xFF06B6D4))
                                Text("终端 Shell 命令行（在沙箱环境中执行）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color(0xFF06B6D4))
                            }
                            OutlinedTextField(
                                value = configMap["command"].orEmpty(),
                                onValueChange = { configMap["command"] = it },
                                label = { Text("Bash 命令（在此输入执行的命令）") },
                                placeholder = { Text("例如：git status --short && git diff") },
                                supportingText = { Text("支持引用上游输出：\${previous.output}、工作区：\${WORKSPACE_PATH}") },
                                minLines = 3,
                                maxLines = 6,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text("常用命令预设（点击直接填入）：", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(
                                    "系统内核" to "echo '=== 系统内核 ===' && uname -a && cat /etc/os-release | head -n 8",
                                    "内存磁盘" to "echo '=== 资源使用 ===' && free -h && df -h /",
                                    "Git 状态" to "git status --short && git diff --stat",
                                    "测试网络" to "curl -I -s -m 5 https://www.baidu.com | head -n 4",
                                    "工具排查" to "for cmd in git python3 curl make; do which \$cmd && echo \"✔ \$cmd\" || echo \"✘ \$cmd\"; done",
                                ).forEach { (name, cmd) ->
                                    Surface(
                                        onClick = { configMap["command"] = cmd },
                                        shape = RoundedCornerShape(50),
                                        color = Color(0xFF06B6D4).copy(alpha = 0.14f),
                                    ) {
                                        Text(
                                            text = "+ $name",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF06B6D4),
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        )
                                    }
                                }
                            }
                            OutlinedTextField(
                                value = configMap["workingDirectory"].orEmpty(),
                                onValueChange = { configMap["workingDirectory"] = it },
                                label = { Text("工作目录（可选）") },
                                placeholder = { Text("留空默认为当前工程根目录") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                WorkflowNodeType.CONDITION_BRANCH -> {
                    BranchRouterCard(
                        state = state,
                        node = node,
                        onConnectNodes = onConnectNodes,
                        onRemoveEdge = onRemoveEdge,
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
                }

                WorkflowNodeType.TRIGGER -> {
                    Text(
                        text = "工作流启动入口。在顶部点击“运行调试”即可从此处触发整个 DAG 流程。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                WorkflowNodeType.TERMINAL_OUTPUT -> {
                    Text(
                        text = "工作流终点节点。将自动收集并归档上游所有步骤的标准输出、错误日志与产物文件。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

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
                        Text("策略：${policy.editorLabel()}", maxLines = 1)
                    }
                    DropdownMenu(expanded = policyExpanded, onDismissRequest = { policyExpanded = false }) {
                        FailurePolicy.entries.forEach { item ->
                            DropdownMenuItem(text = { Text(item.editorLabel()) }, onClick = { policy = item; policyExpanded = false })
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RuntimeOutlinedButton(
                    onClick = if (isConnecting) onCancelConnection else onBeginConnection,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (isConnecting) "取消连线" else "在画布连线…", maxLines = 1)
                }
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
                ) {
                    Text("应用参数更改", maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun BranchRouterCard(
    state: WorkflowEditorUiState,
    node: WorkflowNode,
    onConnectNodes: (String, String, String, String?) -> Unit,
    onRemoveEdge: (String) -> Unit,
) {
    val branchEdges = state.definition.edges.filter { it.fromNodeId == node.id }
    val otherNodes = state.definition.nodes.filter { it.id != node.id }

    val successEdge = branchEdges.firstOrNull { it.conditionExpression?.contains("exitCode == 0") == true || it.fromPort == "success" }
    val failureEdge = branchEdges.firstOrNull { it.conditionExpression?.contains("exitCode !=") == true || it.fromPort == "failure" }

    var successTargetId by remember(otherNodes) { mutableStateOf(otherNodes.firstOrNull()?.id.orEmpty()) }
    var failureTargetId by remember(otherNodes) { mutableStateOf(otherNodes.getOrNull(1)?.id ?: otherNodes.firstOrNull()?.id.orEmpty()) }
    var successExpanded by remember { mutableStateOf(false) }
    var failureExpanded by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color(0xFFEAB308).copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RuntimeIcon(RuntimeIconName.Link, Modifier.size(16.dp), tint = Color(0xFFEAB308))
                Text("可视化条件分支路由器", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color(0xFFEAB308))
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "💡 路由规则：上游节点执行完毕后，根据退出码分流：\n  • exitCode == 0 走向【成功分支】\n  • exitCode != 0 走向【失败分支】",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Surface(shape = RoundedCornerShape(50), color = Color(0xFF10B981).copy(alpha = 0.18f)) {
                    Text("✔ 成功分支 (exitCode == 0)", style = MaterialTheme.typography.labelSmall, color = Color(0xFF10B981), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
                if (successEdge != null) {
                    val target = state.definition.nodes.firstOrNull { it.id == successEdge.toNodeId }
                    Row(
                        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp)).padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("已连接至: ${target?.title ?: successEdge.toNodeId}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        RuntimeOutlinedButton(
                            onClick = { onRemoveEdge(successEdge.id) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text("断开", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                } else if (otherNodes.isNotEmpty()) {
                    val currentSuccessNode = otherNodes.firstOrNull { it.id == successTargetId } ?: otherNodes.first()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.weight(1f)) {
                            RuntimeOutlinedButton(onClick = { successExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                Text("目标: ${currentSuccessNode.title}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            DropdownMenu(expanded = successExpanded, onDismissRequest = { successExpanded = false }) {
                                otherNodes.forEach { target ->
                                    DropdownMenuItem(
                                        text = { Text(target.title) },
                                        onClick = { successTargetId = target.id; successExpanded = false },
                                    )
                                }
                            }
                        }
                        RuntimeButton(
                            onClick = { onConnectNodes(node.id, currentSuccessNode.id, "output", "exitCode == 0") },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text("连接", maxLines = 1)
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Surface(shape = RoundedCornerShape(50), color = Color(0xFFF43F5E).copy(alpha = 0.18f)) {
                    Text("✘ 失败分支 (exitCode != 0)", style = MaterialTheme.typography.labelSmall, color = Color(0xFFF43F5E), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
                if (failureEdge != null) {
                    val target = state.definition.nodes.firstOrNull { it.id == failureEdge.toNodeId }
                    Row(
                        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp)).padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("已连接至: ${target?.title ?: failureEdge.toNodeId}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        RuntimeOutlinedButton(
                            onClick = { onRemoveEdge(failureEdge.id) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text("断开", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                } else if (otherNodes.isNotEmpty()) {
                    val currentFailureNode = otherNodes.firstOrNull { it.id == failureTargetId } ?: otherNodes.first()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.weight(1f)) {
                            RuntimeOutlinedButton(onClick = { failureExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                Text("目标: ${currentFailureNode.title}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            DropdownMenu(expanded = failureExpanded, onDismissRequest = { failureExpanded = false }) {
                                otherNodes.forEach { target ->
                                    DropdownMenuItem(
                                        text = { Text(target.title) },
                                        onClick = { failureTargetId = target.id; failureExpanded = false },
                                    )
                                }
                            }
                        }
                        RuntimeButton(
                            onClick = { onConnectNodes(node.id, currentFailureNode.id, "output", "exitCode != 0") },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text("连接", maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NodeConnectionsCard(
    state: WorkflowEditorUiState,
    node: WorkflowNode,
    onConnectNodes: (String, String, String, String?) -> Unit,
    onDisconnectAll: (String) -> Unit,
    onUpdateEdge: (WorkflowEdge) -> Unit,
    onRemoveEdge: (String) -> Unit,
) {
    val outgoingEdges = state.definition.edges.filter { it.fromNodeId == node.id }
    val incomingEdges = state.definition.edges.filter { it.toNodeId == node.id }
    val allConnected = outgoingEdges + incomingEdges

    val otherNodes = remember(state.definition.nodes, node.id) {
        state.definition.nodes.filter { it.id != node.id }
    }

    var selectedTargetId by remember(otherNodes) { mutableStateOf(otherNodes.firstOrNull()?.id.orEmpty()) }
    var selectedPort by remember { mutableStateOf("output") }
    var conditionText by remember { mutableStateOf("") }
    var targetExpanded by remember { mutableStateOf(false) }
    var portExpanded by remember { mutableStateOf(false) }

    RuntimeCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RuntimeIcon(RuntimeIconName.Link, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text("节点连线与拓扑 (${allConnected.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (allConnected.isNotEmpty()) {
                    Surface(
                        onClick = { onDisconnectAll(node.id) },
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                    ) {
                        Text(
                            text = "一键断开全部",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }

            Text("传出连线（连向后续节点）：", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            if (outgoingEdges.isEmpty()) {
                Text("暂无传出连线", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                outgoingEdges.forEach { edge ->
                    val targetNode = state.definition.nodes.firstOrNull { it.id == edge.toNodeId }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(50),
                                    ) {
                                        Text(
                                            text = edge.fromPort.portLabel(),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                    Text("──►", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        text = targetNode?.title ?: edge.toNodeId,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (!edge.conditionExpression.isNullOrBlank()) {
                                    Text(
                                        text = "条件: ${edge.conditionExpression}",
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.tertiary,
                                        maxLines = 1,
                                    )
                                }
                            }
                            RuntimeOutlinedButton(
                                onClick = { onRemoveEdge(edge.id) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                RuntimeIcon(RuntimeIconName.Close, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.error)
                                Text("断开", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            Text("传入连线（来自前序节点）：", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            if (incomingEdges.isEmpty()) {
                Text("暂无传入连线", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                incomingEdges.forEach { edge ->
                    val sourceNode = state.definition.nodes.firstOrNull { it.id == edge.fromNodeId }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = sourceNode?.title ?: edge.fromNodeId,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text("──►", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("本节点", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                                if (!edge.conditionExpression.isNullOrBlank()) {
                                    Text(
                                        text = "条件: ${edge.conditionExpression}",
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.tertiary,
                                        maxLines = 1,
                                    )
                                }
                            }
                            RuntimeOutlinedButton(
                                onClick = { onRemoveEdge(edge.id) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                RuntimeIcon(RuntimeIconName.Close, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.error)
                                Text("断开", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            HorizontalDivider()

            Text("➕ 添加连线到其他节点：", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            if (otherNodes.isEmpty()) {
                Text("画布中尚无其他可用节点", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val currentTargetNode = otherNodes.firstOrNull { it.id == selectedTargetId } ?: otherNodes.first()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box {
                        RuntimeOutlinedButton(
                            onClick = { targetExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("目标: ${currentTargetNode.title}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        DropdownMenu(expanded = targetExpanded, onDismissRequest = { targetExpanded = false }) {
                            otherNodes.forEach { target ->
                                DropdownMenuItem(
                                    text = { Text("${target.title} (${target.type.editorLabel()})") },
                                    onClick = {
                                        selectedTargetId = target.id
                                        targetExpanded = false
                                    },
                                )
                            }
                        }
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) {
                            RuntimeOutlinedButton(
                                onClick = { portExpanded = true },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("端口: ${selectedPort.portLabel()}", maxLines = 1)
                            }
                            DropdownMenu(expanded = portExpanded, onDismissRequest = { portExpanded = false }) {
                                EdgePorts.forEach { port ->
                                    DropdownMenuItem(
                                        text = { Text(port.portLabel()) },
                                        onClick = {
                                            selectedPort = port
                                            portExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                        OutlinedTextField(
                            value = conditionText,
                            onValueChange = { conditionText = it },
                            label = { Text("条件（可选）") },
                            placeholder = { Text("如 exitCode == 0") },
                            singleLine = true,
                            modifier = Modifier.weight(1.5f),
                        )
                    }

                    RuntimeButton(
                        onClick = {
                            onConnectNodes(node.id, currentTargetNode.id, selectedPort, conditionText.trim().ifBlank { null })
                            conditionText = ""
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        RuntimeIcon(RuntimeIconName.Plus, Modifier.size(16.dp))
                        Text("立即建立连线", maxLines = 1)
                    }
                }
            }
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
        guide = "条件分支节点根据上游执行的退出码 (exitCode) 或输出内容进行分流跳转，支持可视化配置成功与失败分支。",
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
    "success" -> "成功 (success)"
    "failure" -> "失败 (failure)"
    else -> "任意输出 (output)"
}

private val EdgePorts = listOf("output", "success", "failure")
