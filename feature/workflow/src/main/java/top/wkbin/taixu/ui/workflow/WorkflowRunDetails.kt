package top.wkbin.taixu.ui.workflow

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
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
internal fun WorkflowStartDialog(definition: WorkflowDefinition, supplied: Map<String, String>, onDismiss: () -> Unit, onStart: (Map<String, String>) -> Unit) {
    val required = remember(definition) { definition.nodes.filter { it.type == WorkflowNodeType.TRIGGER }
        .flatMap { it.config["requiredVariables"].orEmpty().split(',') }.map { it.trim() }.filter { it.isNotEmpty() }.distinct() }
    val values = remember(definition.id) { mutableStateMapOf<String, String>().apply { putAll(definition.defaultVariables + supplied) } }
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("运行 ${definition.name}") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(definition.description)
            required.forEach { key -> OutlinedTextField(values[key].orEmpty(), { values[key] = it }, label = { Text(key) }, modifier = Modifier.fillMaxWidth()) }
        } },
        dismissButton = { RuntimeOutlinedButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = { RuntimeButton(onClick = { onStart(values.toMap()) }, enabled = required.all { !values[it].isNullOrBlank() }) { Text("开始执行") } },
    )
}
