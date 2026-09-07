package top.wkbin.taixu.ui.workflow

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
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
import top.wkbin.taixu.ui.components.RuntimeCard

private val NodeWidth = 208.dp
private val NodeHeight = 104.dp
private val GridSize = 32.dp
private const val MinScale = 0.30f
private const val MaxScale = 2.40f

/** Nodes and edges share one world-space layer, guaranteeing identical pan/zoom transforms. */
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
    LaunchedEffect(definition.nodes, density) {
        positions.keys.retainAll(definition.nodes.mapTo(mutableSetOf()) { it.id })
        definition.nodes.forEachIndexed { index, node ->
            positions[node.id] = nodePosition(node, index, density.density)
        }
    }
    // definition changes synchronously, while LaunchedEffect synchronizes the mutable drag map
    // on the following frame. Resolve a complete render snapshot now so newly added nodes can
    // never be looked up from an incomplete map.
    val resolvedPositions = resolveNodePositions(definition.nodes, positions, density.density)
    val bounds = contentBounds(resolvedPositions.values, nodeWidthPx, nodeHeightPx)
    val worldWidth = max(2400f, bounds.maxX / density.density + 360f).dp
    val worldHeight = max(1600f, bounds.maxY / density.density + 280f).dp

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

    // When nodes are added, refit the viewport so all nodes (including the new one) remain comfortably visible.
    // We track the previous count via a remembered int so that delete does NOT trigger an automatic refit.
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

        // The complete graph is transformed exactly once around the world origin.
        Box(
            modifier = Modifier
                .requiredSize(worldWidth, worldHeight)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = scale
                    scaleY = scale
                    translationX = pan.x
                    translationY = pan.y
                },
        ) {
            WorkflowEdges(
                definition = definition,
                state = state,
                positions = resolvedPositions,
                nodeWidthPx = nodeWidthPx,
                nodeHeightPx = nodeHeightPx,
                labelBackground = labelBackground,
                labelTextColor = labelTextColor,
                inactiveColor = inactiveEdgeColor,
                modifier = Modifier.fillMaxSize(),
            )

            definition.nodes.forEachIndexed { index, node ->
                key(node.id) {
                    val position = positions[node.id]
                        ?: resolvedPositions[node.id]
                        ?: nodePosition(node, index, density.density)
                    val run = state?.nodeStates?.get(node.id)
                    WorkflowNodeCard(
                        node = node,
                        status = run?.status ?: NodeRunStatus.IDLE,
                        progress = run?.progressMessage.orEmpty(),
                        selected = node.id == selectedNodeId,
                        connecting = node.id == connectionSourceId,
                        modifier = Modifier
                            .offset { IntOffset(position.x.roundToInt(), position.y.roundToInt()) }
                            .size(NodeWidth, NodeHeight)
                            .pointerInput(node.id, editable, scale) {
                                if (!editable) return@pointerInput
                                detectDragGestures(
                                    onDragStart = {
                                        positions[node.id] = position
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
                                    positions[node.id] = (positions[node.id] ?: position) + amount / scale
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
    nodeWidthPx: Float,
    nodeHeightPx: Float,
    labelBackground: Color,
    labelTextColor: Color,
    inactiveColor: Color,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val labelPaint = remember(labelTextColor, density) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = labelTextColor.toArgb()
            textSize = with(density) { 12.dp.toPx() }
            textAlign = Paint.Align.CENTER
        }
    }
    val statusColors = NodeRunStatus.entries.associateWith { statusColor(it) }
    Canvas(modifier) {
        definition.edges.forEach { edge ->
            val source = positions[edge.fromNodeId] ?: return@forEach
            val target = positions[edge.toNodeId] ?: return@forEach
            val sourceCenter = source + Offset(nodeWidthPx / 2f, nodeHeightPx / 2f)
            val targetCenter = target + Offset(nodeWidthPx / 2f, nodeHeightPx / 2f)
            val delta = targetCenter - sourceCenter
            val distance = sqrt(delta.x * delta.x + delta.y * delta.y)
            if (distance < 1f) return@forEach
            val direction = delta / distance
            val start = rectangleEdge(sourceCenter, direction, nodeWidthPx, nodeHeightPx)
            val end = rectangleEdge(targetCenter, -direction, nodeWidthPx, nodeHeightPx)
            val bend = max(abs(end.x - start.x) * 0.42f, 56.dp.toPx())
            val directionSign = if (end.x >= start.x) 1f else -1f
            val control1 = Offset(start.x + bend * directionSign, start.y)
            val control2 = Offset(end.x - bend * directionSign, end.y)
            val path = Path().apply {
                moveTo(start.x, start.y)
                cubicTo(control1.x, control1.y, control2.x, control2.y, end.x, end.y)
            }

            val sourceStatus = state?.nodeStates?.get(edge.fromNodeId)?.status ?: NodeRunStatus.IDLE
            val active = sourceStatus in setOf(NodeRunStatus.RUNNING, NodeRunStatus.STREAMING, NodeRunStatus.WAITING_APPROVAL, NodeRunStatus.SUCCESS)
            val color = if (active) (statusColors[sourceStatus] ?: inactiveColor) else inactiveColor
            val dash = if (active) null else PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 7.dp.toPx()))

            drawPath(path, Color.Black.copy(alpha = 0.12f), style = Stroke(4.5.dp.toPx(), cap = StrokeCap.Round, pathEffect = dash))
            drawPath(path, color, style = Stroke(2.4.dp.toPx(), cap = StrokeCap.Round, pathEffect = dash))
            drawCircle(color, radius = 4.5.dp.toPx(), center = start)
            drawCircle(labelBackground, radius = 5.5.dp.toPx(), center = end)
            drawCircle(color, radius = 4.2.dp.toPx(), center = end)

            val endAngle = atan2(end.y - control2.y, end.x - control2.x)
            val arrowSize = 9.dp.toPx()
            val arrow = Path().apply {
                moveTo(end.x, end.y)
                lineTo(end.x - arrowSize * cos(endAngle - 0.48f), end.y - arrowSize * sin(endAngle - 0.48f))
                lineTo(end.x - arrowSize * cos(endAngle + 0.48f), end.y - arrowSize * sin(endAngle + 0.48f))
                close()
            }
            drawPath(arrow, color)

            edgeLabel(edge.fromPort, edge.conditionExpression)?.let { label ->
                val midpoint = cubicPoint(start, control1, control2, end, 0.5f)
                val width = labelPaint.measureText(label) + 16.dp.toPx()
                val height = 24.dp.toPx()
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

@Composable
private fun WorkflowNodeCard(
    node: WorkflowNode,
    status: NodeRunStatus,
    progress: String,
    selected: Boolean,
    connecting: Boolean,
    modifier: Modifier,
) {
    val color = statusColor(status)
    RuntimeCard(
        modifier = modifier,
        borderColor = when {
            connecting -> MaterialTheme.colorScheme.tertiary
            selected -> MaterialTheme.colorScheme.primary
            else -> color.copy(alpha = if (status == NodeRunStatus.IDLE) 0.34f else 0.82f)
        },
        contentPadding = PaddingValues(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(50)) {
                    Text(
                        text = node.type.displayName(),
                        style = MaterialTheme.typography.labelSmall,
                        color = color,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        maxLines = 1,
                    )
                }
                Text(statusLabel(status), style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
            }
            Text(node.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

private fun WorkflowNodeType.displayName(): String = name.lowercase().split('_').joinToString(" ") {
    it.replaceFirstChar(Char::uppercase)
}
