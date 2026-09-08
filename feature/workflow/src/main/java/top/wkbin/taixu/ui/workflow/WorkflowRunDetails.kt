package top.wkbin.taixu.ui.workflow

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import top.wkbin.taixu.core.database.AiModelEntity
import top.wkbin.taixu.core.model.workflow.*
import top.wkbin.taixu.ui.components.*

@Composable
internal fun WorkflowNodeDetails(run: WorkflowNodeRunState?) {
    var expanded by remember { mutableStateOf(false) }
    val output = run?.output
    if (run?.status in setOf(NodeRunStatus.RUNNING, NodeRunStatus.STREAMING)) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    output?.let {
        Text("耗时 ${it.durationMs / 1000.0} 秒 · 退出码 ${it.exitCode}", style = MaterialTheme.typography.labelSmall)
        it.error?.let { error -> SelectionContainer { Text(error, color = MaterialTheme.colorScheme.error) } }
        if (it.artifacts.isNotEmpty()) {
            Text("产物路径", style = MaterialTheme.typography.labelLarge)
            SelectionContainer { Text(it.artifacts.joinToString("\n"), fontFamily = FontFamily.Monospace) }
        }
    }
    val log = output?.textOutput?.takeIf { it.isNotBlank() } ?: run?.progressMessage.orEmpty()
    if (log.isNotBlank()) {
        RuntimeOutlinedButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起日志" else "展开日志 / Diff") }
        if (expanded) SelectionContainer {
            Text(log, Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
internal fun WorkflowElapsed(state: WorkflowRuntimeState) {
    var now by remember(state.executionId) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.executionId, state.finishedAt) {
        while (state.finishedAt == null) { now = System.currentTimeMillis(); delay(1000) }
    }
    val elapsed = ((state.finishedAt ?: now) - (state.startedAt ?: now)).coerceAtLeast(0) / 1000
    Text("运行耗时 ${elapsed / 60} 分 ${elapsed % 60} 秒", style = MaterialTheme.typography.labelSmall)
}

@Composable
internal fun WorkflowStartDialog(
    definition: WorkflowDefinition,
    supplied: Map<String, String>,
    models: List<AiModelEntity>,
    onDismiss: () -> Unit,
    onStart: (Map<String, String>) -> Unit,
) {
    val required = remember(definition) {
        definition.nodes.filter { it.type == WorkflowNodeType.TRIGGER }
            .flatMap { it.config["requiredVariables"].orEmpty().split(',') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }
    val editableDefaults = remember(definition) {
        definition.defaultVariables.keys.filterNot { it in required || it.startsWith("WORKFLOW_MODEL") }.sorted()
    }
    val needsModel = remember(definition) {
        definition.nodes.any {
            it.type == WorkflowNodeType.AGENT_INFERENCE ||
                it.type == WorkflowNodeType.SUBAGENT_DELEGATE ||
                (it.type == WorkflowNodeType.HOST_ACTION && it.config["action"] == "gui_pilot")
        }
    }
    val values = remember(definition.id, supplied) {
        mutableStateMapOf<String, String>().apply { putAll(definition.defaultVariables + supplied) }
    }

    val active = models.firstOrNull { it.isActive }
    var selectedModelId by remember(definition.id, models) {
        mutableStateOf(
            values["WORKFLOW_MODEL_ID"]
                ?.takeIf { id -> models.any { it.id == id } }
                ?: active?.id
                ?: models.firstOrNull()?.id.orEmpty(),
        )
    }
    val selectedProfile = models.firstOrNull { it.id == selectedModelId }
    val variants = remember(selectedProfile?.model) {
        selectedProfile?.model.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }
    var selectedVariant by remember(selectedModelId, variants) {
        mutableStateOf(
            values["WORKFLOW_MODEL_VARIANT"]?.takeIf { it in variants }
                ?: variants.firstOrNull().orEmpty(),
        )
    }

    val canStart = required.all { !values[it].isNullOrBlank() } &&
        (!needsModel || (selectedModelId.isNotBlank() && models.any { it.id == selectedModelId }))

    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("运行 ${definition.name}") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(definition.description)
                if (needsModel) {
                    Text("执行模型（点选即可，避免 403 请换可用账号）", style = MaterialTheme.typography.labelLarge)
                    if (models.isEmpty()) {
                        Text(
                            "尚未配置可用模型。请先到设置 → 模型管理添加账号，再回来运行含智能体节点的工作流。",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        models.forEach { model ->
                            val selected = model.id == selectedModelId
                            Surface(
                                onClick = {
                                    selectedModelId = model.id
                                    val nextVariants = model.model.split(',')
                                        .map { it.trim() }
                                        .filter { it.isNotEmpty() }
                                    selectedVariant = nextVariants.firstOrNull().orEmpty()
                                },
                                shape = RoundedCornerShape(10.dp),
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                                border = BorderStroke(
                                    width = 1.dp,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    },
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                    Text(
                                        text = buildString {
                                            append(if (selected) "✓ " else "")
                                            append(model.name)
                                            if (model.isActive) append("（当前默认）")
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    )
                                    Text(
                                        text = "${model.provider} · ${model.model.substringBefore(',').ifBlank { model.model }}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (model.pureChatMode) {
                                        Text(
                                            "纯聊天模式：可能无法调用 host 工具",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }
                        if (variants.size > 1) {
                            Text("模型变体", style = MaterialTheme.typography.labelMedium)
                            variants.forEach { variant ->
                                val selected = variant == selectedVariant
                                Surface(
                                    onClick = { selectedVariant = variant },
                                    shape = RoundedCornerShape(50),
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHighest
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        text = if (selected) "✓ $variant" else variant,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        } else if (variants.size == 1) {
                            Text("变体：${variants.first()}", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            "此处选择只影响本次工作流，不会改全局默认模型。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                required.forEach { key ->
                    OutlinedTextField(
                        value = values[key].orEmpty(),
                        onValueChange = { values[key] = it },
                        label = { Text("$key（必填）") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                editableDefaults.forEach { key ->
                    OutlinedTextField(
                        value = values[key].orEmpty(),
                        onValueChange = { values[key] = it },
                        label = { Text(key) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        dismissButton = { RuntimeOutlinedButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = {
            RuntimeButton(
                onClick = {
                    val payload = values.toMutableMap()
                    if (needsModel && selectedModelId.isNotBlank()) {
                        payload["WORKFLOW_MODEL_ID"] = selectedModelId
                        val variant = selectedVariant.ifBlank { variants.firstOrNull().orEmpty() }
                        if (variant.isNotBlank()) payload["WORKFLOW_MODEL_VARIANT"] = variant
                    }
                    onStart(payload)
                },
                enabled = canStart,
            ) { Text("开始执行") }
        },
    )
}
