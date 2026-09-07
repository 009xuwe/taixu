package top.wkbin.taixu.ui.workflow

import android.graphics.Paint
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import top.wkbin.taixu.core.model.workflow.NodeRunStatus
import top.wkbin.taixu.core.model.workflow.WorkflowDefinition
import top.wkbin.taixu.core.model.workflow.WorkflowNode
import top.wkbin.taixu.core.model.workflow.WorkflowNodeType
import top.wkbin.taixu.core.model.workflow.WorkflowRuntimeState

private val NodeWidth = 208.dp
private val NodeHeight = 104.dp
private val GridSize = 32.dp
private const val MinScale = 0.30f
private const val MaxScale = 2.40f

/**
 * 2D 工作流画布。
 * 使用基于视口的安全投影变换（Viewport Screen-Space Projection）：
 * 不为整张无限世界分配超大 RenderNode（避免超过移动端 GPU GL_MAX_TEXTURE_SIZE 4096px 导致 HWUI 丢弃图层），
 * 节点与连线均在视口坐标系内精确计算与布局，手势捕获范围严格契合卡片，支持流畅缩放平移。
 */
@Composable
fun WorkflowCanvas2D(
    definition: WorkflowDefinition,
    state: WorkflowRuntimeState?,
    modifier: Modifier = Modifier,
    editable: Boolean = false,
    selectedNodeId: String? = null,
    connectionSourceId: String? = null,
    onNodeSelected: (String) -> Unit = {},
    onNodeMoved: (String, Float, Float) -> Unit = { _, _, _ -> },
    onConnectionRequested: (String, String) -> Unit = { _, _ -> },
) {
    val density = LocalDensity.current
    val nodeWidthPx = with(density) { NodeWidth.toPx() }
    val nodeHeightPx = with(density) { NodeHeight.toPx() }
    val gridSizePx = with(density) { GridSize.toPx() }
    val fitPaddingPx = with(density) { 40.dp.toPx() }
    val positions = remember(definition.id, density) {
        mutableStateMapOf<String, Offset>().apply {
            definition.nodes.forEachIndexed { index, node ->
                this[node.id] = nodePosition(node, index, density.density)
            }
        }
    }
    // 同步保证 positions 包含当前 definition 中的所有节点（无需等待异步 LaunchedEffect）
    definition.nodes.forEachIndexed { index, node ->
        if (!positions.containsKey(node.id)) {
            positions[node.id] = nodePosition(node, index, density.density)
        }
    }
    LaunchedEffect(definition.nodes, density) {
        positions.keys.retainAll(definition.nodes.mapTo(mutableSetOf()) { it.id })
    }

    val resolvedPositions = resolveNodePositions(definition.nodes, positions, density.density)
    val bounds = contentBounds(resolvedPositions.values, nodeWidthPx, nodeHeightPx)

    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember(definition.id) { mutableFloatStateOf(1f) }
    var pan by remember(definition.id) { mutableStateOf(Offset.Zero) }

    fun fitToContent() {
        val transform = fitTransform(bounds, viewportSize, fitPaddingPx, MinScale, 1.35f) ?: return
        scale = transform.scale
        pan = transform.pan
    }

    fun zoomBy(factor: Float) {
        val centroid = Offset(viewportSize.width / 2f, viewportSize.height / 2f)
        val transformed = zoomAround(CanvasTransform(scale, pan), centroid, factor, MinScale, MaxScale)
        scale = transformed.scale
        pan = transformed.pan
    }

    LaunchedEffect(definition.id, viewportSize) {
        if (viewportSize != IntSize.Zero) fitToContent()
    }

    // 当添加节点时，重新适配视口，确保新加入的节点完整呈现在可视区域中
    var prevNodeCount by remember(definition.id) { mutableStateOf(definition.nodes.size) }
    LaunchedEffect(definition.nodes.size) {
        val currentCount = definition.nodes.size
        if (currentCount > prevNodeCount && viewportSize != IntSize.Zero) {
            fitToContent()
        }
        prevNodeCount = currentCount
    }

    val surfaceColor = MaterialTheme.colorScheme.surface
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
    val labelBackground = MaterialTheme.colorScheme.surfaceContainerHigh
    val labelTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    val inactiveEdgeColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.46f)

    Box(
        modifier = modifier
            .clipToBounds()
            .background(surfaceColor)
            .onSizeChanged { viewportSize = it }
            .pointerInput(definition.id) {
                detectTransformGestures { centroid, gesturePan, zoom, _ ->
                    val transformed = zoomAround(CanvasTransform(scale, pan), centroid, zoom, MinScale, MaxScale)
                    scale = transformed.scale
                    pan = transformed.pan + gesturePan
                }
            }
            .pointerInput(definition.id) {
                detectTapGestures(onDoubleTap = { fitToContent() })
            },
    ) {
        // 背景点阵
        Canvas(Modifier.fillMaxSize()) {
            val spacing = gridSizePx * scale
            if (spacing >= 8f) {
                var x = positiveModulo(pan.x, spacing)
                while (x <= size.width) {
                    var y = positiveModulo(pan.y, spacing)
                    while (y <= size.height) {
                        drawCircle(gridColor, radius = (1.25f * scale).coerceIn(0.8f, 2.4f), center = Offset(x, y))
                        y += spacing
                    }
                    x += spacing
                }
            }
        }

        // 连线层：在视口屏幕坐标系中渲染，绝无纹理尺寸超限问题
        WorkflowEdges(
            definition = definition,
            state = state,
            positions = resolvedPositions,
            scale = scale,
            pan = pan,
            nodeWidthPx = nodeWidthPx,
            nodeHeightPx = nodeHeightPx,
            labelBackground = labelBackground,
            labelTextColor = labelTextColor,
            inactiveColor = inactiveEdgeColor,
            modifier = Modifier.fillMaxSize(),
        )

        // 节点层：卡片在视口屏幕坐标系中精确定位与缩放
        definition.nodes.forEachIndexed { index, node ->
            key(node.id) {
                val run = state?.nodeStates?.get(node.id)
                WorkflowNodeCard(
                    node = node,
                    status = run?.status ?: NodeRunStatus.IDLE,
                    progress = run?.progressMessage.orEmpty(),
                    selected = node.id == selectedNodeId,
                    connecting = node.id == connectionSourceId,
                    modifier = Modifier
                        .offset {
                            val worldPos = positions[node.id]
                                ?: resolvedPositions[node.id]
                                ?: nodePosition(node, index, density.density)
                            val screenPos = worldPos * scale + pan
                            IntOffset(screenPos.x.roundToInt(), screenPos.y.roundToInt())
                        }
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints)
                            layout(
                                (placeable.width * scale).roundToInt(),
                                (placeable.height * scale).roundToInt(),
                            ) {
                                placeable.placeRelativeWithLayer(0, 0) {
                                    scaleX = scale
                                    scaleY = scale
                                    transformOrigin = TransformOrigin(0f, 0f)
                                }
                            }
                        }
                        .pointerInput(node.id, editable, scale) {
                            if (!editable) return@pointerInput
                            detectDragGestures(
                                onDragStart = {
                                    val current = positions[node.id]
                                        ?: resolvedPositions[node.id]
                                        ?: nodePosition(node, index, density.density)
                                    positions[node.id] = current
                                    onNodeSelected(node.id)
                                },
                                onDragEnd = {
                                    val current = positions[node.id] ?: return@detectDragGestures
                                    val snapped = Offset(
                                        (current.x / gridSizePx).roundToInt() * gridSizePx,
                                        (current.y / gridSizePx).roundToInt() * gridSizePx,
                                    )
                                    positions[node.id] = snapped
                                    onNodeMoved(node.id, snapped.x / density.density, snapped.y / density.density)
                                },
                            ) { change, amount ->
                                change.consume()
                                val current = positions[node.id]
                                    ?: resolvedPositions[node.id]
                                    ?: nodePosition(node, index, density.density)
                                positions[node.id] = current + amount / scale
                            }
                        }
                        .clickable {
                            val source = connectionSourceId
                            if (source != null && source != node.id) onConnectionRequested(source, node.id)
                            else onNodeSelected(node.id)
                        },
                )
            }
        }

        ViewportControls(
            scale = scale,
            onZoomOut = { zoomBy(0.82f) },
            onFit = ::fitToContent,
            onZoomIn = { zoomBy(1.22f) },
            modifier = Modifier.align(Alignment.TopEnd),
        )

        Text(
            text = "双指缩放 · 拖动画布 · 双击适配",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 10.dp),
        )
    }
}

@Composable
private fun WorkflowEdges(
    definition: WorkflowDefinition,
    state: WorkflowRuntimeState?,
    positions: Map<String, Offset>,
    scale: Float,
    pan: Offset,
    nodeWidthPx: Float,
    nodeHeightPx: Float,
    labelBackground: Color,
    labelTextColor: Color,
    inactiveColor: Color,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val labelPaint = remember(labelTextColor, density, scale) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = labelTextColor.toArgb()
            textSize = with(density) { (11.dp.toPx() * scale).coerceIn(8.dp.toPx(), 14.dp.toPx()) }
            textAlign = Paint.Align.CENTER
        }
    }
    val statusColors = NodeRunStatus.entries.associateWith { statusColor(it) }

    Canvas(modifier) {
        definition.edges.forEach { edge ->
            val sourceWorld = positions[edge.fromNodeId] ?: return@forEach
            val targetWorld = positions[edge.toNodeId] ?: return@forEach
            val sourceCenterWorld = sourceWorld + Offset(nodeWidthPx / 2f, nodeHeightPx / 2f)
            val targetCenterWorld = targetWorld + Offset(nodeWidthPx / 2f, nodeHeightPx / 2f)
            val deltaWorld = targetCenterWorld - sourceCenterWorld
            val distanceWorld = sqrt(deltaWorld.x * deltaWorld.x + deltaWorld.y * deltaWorld.y)
            if (distanceWorld < 1f) return@forEach
            val direction = deltaWorld / distanceWorld
            val startWorld = rectangleEdge(sourceCenterWorld, direction, nodeWidthPx, nodeHeightPx)
            val endWorld = rectangleEdge(targetCenterWorld, -direction, nodeWidthPx, nodeHeightPx)
            val bendWorld = max(abs(endWorld.x - startWorld.x) * 0.42f, 56.dp.toPx())
            val directionSign = if (endWorld.x >= startWorld.x) 1f else -1f
            val control1World = Offset(startWorld.x + bendWorld * directionSign, startWorld.y)
            val control2World = Offset(endWorld.x - bendWorld * directionSign, endWorld.y)

            // 投影至屏幕视口坐标
            val start = startWorld * scale + pan
            val end = endWorld * scale + pan
            val control1 = control1World * scale + pan
            val control2 = control2World * scale + pan

            val path = Path().apply {
                moveTo(start.x, start.y)
                cubicTo(control1.x, control1.y, control2.x, control2.y, end.x, end.y)
            }

            val sourceStatus = state?.nodeStates?.get(edge.fromNodeId)?.status ?: NodeRunStatus.IDLE
            val active = sourceStatus in setOf(NodeRunStatus.RUNNING, NodeRunStatus.STREAMING, NodeRunStatus.WAITING_APPROVAL, NodeRunStatus.SUCCESS)
            val color = if (active) (statusColors[sourceStatus] ?: inactiveColor) else inactiveColor
            val dash = if (active) null else PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx() * scale.coerceIn(0.6f, 1.4f), 7.dp.toPx() * scale.coerceIn(0.6f, 1.4f)))
            val strokeWidth = (2.4.dp.toPx() * scale).coerceIn(1.6f, 3.2f)

            drawPath(path, Color.Black.copy(alpha = 0.12f), style = Stroke(strokeWidth + 2f, cap = StrokeCap.Round, pathEffect = dash))
            drawPath(path, color, style = Stroke(strokeWidth, cap = StrokeCap.Round, pathEffect = dash))
            drawCircle(color, radius = (4.5.dp.toPx() * scale).coerceIn(3f, 6f), center = start)
            drawCircle(labelBackground, radius = (5.5.dp.toPx() * scale).coerceIn(4f, 7.5f), center = end)
            drawCircle(color, radius = (4.2.dp.toPx() * scale).coerceIn(2.8f, 5.5f), center = end)

            val endAngle = atan2(end.y - control2.y, end.x - control2.x)
            val arrowSize = (9.dp.toPx() * scale).coerceIn(6f, 12f)
            val arrow = Path().apply {
                moveTo(end.x, end.y)
                lineTo(end.x - arrowSize * cos(endAngle - 0.48f), end.y - arrowSize * sin(endAngle - 0.48f))
                lineTo(end.x - arrowSize * cos(endAngle + 0.48f), end.y - arrowSize * sin(endAngle + 0.48f))
                close()
            }
            drawPath(arrow, color)

            edgeLabel(edge.fromPort, edge.conditionExpression)?.let { label ->
                val midpoint = cubicPoint(start, control1, control2, end, 0.5f)
                val width = labelPaint.measureText(label) + 14.dp.toPx() * scale.coerceIn(0.7f, 1.2f)
                val height = 22.dp.toPx() * scale.coerceIn(0.7f, 1.2f)
                drawRoundRect(
                    color = labelBackground.copy(alpha = 0.96f),
                    topLeft = Offset(midpoint.x - width / 2f, midpoint.y - height / 2f),
                    size = Size(width, height),
                    cornerRadius = CornerRadius(height / 2f),
                )
                drawContext.canvas.nativeCanvas.drawText(
                    label,
                    midpoint.x,
                    midpoint.y - (labelPaint.ascent() + labelPaint.descent()) / 2f,
                    labelPaint,
                )
            }
        }
    }
}

/**
 * 节点卡片：采用原生 Material 3 Surface 实体呈现，高对比度容器背景与阴影，
 * 彻底消除背景流光折射失效导致的透明不可见问题。
 */
@Composable
private fun WorkflowNodeCard(
    node: WorkflowNode,
    status: NodeRunStatus,
    progress: String,
    selected: Boolean,
    connecting: Boolean,
    modifier: Modifier = Modifier,
) {
    val color = statusColor(status)
    val borderColor = when {
        connecting -> MaterialTheme.colorScheme.tertiary
        selected -> MaterialTheme.colorScheme.primary
        else -> color.copy(alpha = if (status == NodeRunStatus.IDLE) 0.38f else 0.85f)
    }
    val borderWidth = if (selected || connecting) 2.dp else 1.dp
    val shape = RoundedCornerShape(16.dp)

    Surface(
        modifier = modifier.size(NodeWidth, NodeHeight),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(borderWidth, borderColor),
        tonalElevation = if (selected) 6.dp else 2.dp,
        shadowElevation = if (selected) 4.dp else 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    color = color.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(50),
                ) {
                    Text(
                        text = node.type.displayName(),
                        style = MaterialTheme.typography.labelSmall,
                        color = color,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        maxLines = 1,
                    )
                }
                Text(
                    text = statusLabel(status),
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    maxLines = 1,
                )
            }
            Text(
                text = node.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = progress.ifBlank { node.description.ifBlank { node.id } },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ViewportControls(
    scale: Float,
    onZoomOut: () -> Unit,
    onFit: () -> Unit,
    onZoomIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.padding(12.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
        tonalElevation = 4.dp,
        shadowElevation = 3.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onZoomOut) { Text("−", style = MaterialTheme.typography.titleLarge) }
            Text("${(scale * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
            IconButton(onClick = onFit) { Text("适配", style = MaterialTheme.typography.labelMedium) }
            IconButton(onClick = onZoomIn) { Text("+", style = MaterialTheme.typography.titleLarge) }
        }
    }
}

internal data class CanvasTransform(val scale: Float, val pan: Offset)

internal data class CanvasContentBounds(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float)

internal fun zoomAround(
    current: CanvasTransform,
    centroid: Offset,
    zoomFactor: Float,
    minScale: Float,
    maxScale: Float,
): CanvasTransform {
    val newScale = (current.scale * zoomFactor).coerceIn(minScale, maxScale)
    val scaleChange = newScale / current.scale
    return CanvasTransform(newScale, (current.pan - centroid) * scaleChange + centroid)
}

internal fun fitTransform(
    bounds: CanvasContentBounds,
    viewport: IntSize,
    padding: Float,
    minScale: Float,
    maxScale: Float,
): CanvasTransform? {
    if (viewport == IntSize.Zero) return null
    val contentWidth = (bounds.maxX - bounds.minX).coerceAtLeast(1f)
    val contentHeight = (bounds.maxY - bounds.minY).coerceAtLeast(1f)
    val availableWidth = (viewport.width - padding * 2f).coerceAtLeast(1f)
    val availableHeight = (viewport.height - padding * 2f).coerceAtLeast(1f)
    val scale = minOf(availableWidth / contentWidth, availableHeight / contentHeight).coerceIn(minScale, maxScale)
    return CanvasTransform(
        scale = scale,
        pan = Offset(
            x = (viewport.width - contentWidth * scale) / 2f - bounds.minX * scale,
            y = (viewport.height - contentHeight * scale) / 2f - bounds.minY * scale,
        ),
    )
}

private fun contentBounds(positions: Collection<Offset>, nodeWidth: Float, nodeHeight: Float): CanvasContentBounds {
    if (positions.isEmpty()) return CanvasContentBounds(0f, 0f, nodeWidth, nodeHeight)
    return CanvasContentBounds(
        minX = positions.minOf { it.x },
        minY = positions.minOf { it.y },
        maxX = positions.maxOf { it.x + nodeWidth },
        maxY = positions.maxOf { it.y + nodeHeight },
    )
}

private fun rectangleEdge(center: Offset, direction: Offset, width: Float, height: Float): Offset {
    val xDistance = if (abs(direction.x) < 0.0001f) Float.POSITIVE_INFINITY else width / 2f / abs(direction.x)
    val yDistance = if (abs(direction.y) < 0.0001f) Float.POSITIVE_INFINITY else height / 2f / abs(direction.y)
    return center + direction * minOf(xDistance, yDistance)
}

private fun cubicPoint(start: Offset, c1: Offset, c2: Offset, end: Offset, t: Float): Offset {
    val u = 1f - t
    return start * (u * u * u) + c1 * (3f * u * u * t) + c2 * (3f * u * t * t) + end * (t * t * t)
}

private fun edgeLabel(port: String, condition: String?): String? = condition?.takeIf(String::isNotBlank)
    ?: port.takeUnless { it.equals("output", true) || it.equals("success", true) }

private fun positiveModulo(value: Float, modulus: Float): Float = ((value % modulus) + modulus) % modulus

private fun nodePosition(node: WorkflowNode, index: Int, density: Float): Offset = Offset(
    x = (node.canvasX.takeUnless { it == 0f } ?: (48f + index * 248f)) * density,
    y = (node.canvasY.takeUnless { it == 0f } ?: (72f + (index % 2) * 136f)) * density,
)

internal fun resolveNodePositions(
    nodes: List<WorkflowNode>,
    current: Map<String, Offset>,
    density: Float,
): Map<String, Offset> = nodes.mapIndexed { index, node ->
    node.id to (current[node.id] ?: nodePosition(node, index, density))
}.toMap()

private fun WorkflowNodeType.displayName(): String = when (this) {
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
