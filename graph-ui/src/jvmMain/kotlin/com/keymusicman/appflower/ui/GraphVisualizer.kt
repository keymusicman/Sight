package com.keymusicman.appflower.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import co.touchlab.kermit.Logger
import com.keymusicman.appflower.model.AppGraph
import com.keymusicman.appflower.model.Connection
import com.keymusicman.appflower.model.ConnectionEndpoint
import com.keymusicman.appflower.model.GraphMetadata
import com.keymusicman.appflower.model.LayoutGraph
import com.keymusicman.appflower.model.LayoutNode
import com.keymusicman.appflower.model.Screen
import com.keymusicman.appflower.model.Subgraph
import com.keymusicman.appflower.viewmodel.GraphViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Toolkit
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GraphVisualizer(
    appBasePath: String? = null,
    modifier: Modifier = Modifier,
    viewModel: GraphViewModel,
    onViewSource: ((nodeId: String) -> Unit)? = null,
) {
    val layoutGraph by viewModel.displayLayoutGraphState

    val colors = LocalAppColors.current
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(viewModel.backgroundColorState.value),
        contentAlignment = Alignment.TopStart,
    ) {
        when (val layoutGraph = layoutGraph) {
            null -> {
                BasicText("No graph loaded", style = TextStyle(color = colors.onBackground))
            }

            else -> {
                GraphVisualizerInternal(layoutGraph, viewModel, onViewSource)
            }
        }
    }
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun BoxWithConstraintsScope.GraphVisualizerInternal(
    layoutGraph: LayoutGraph,
    viewModel: GraphViewModel,
    onViewSource: ((nodeId: String) -> Unit)? = null,
) {
    var loadedOnce by rememberSaveable { mutableStateOf(false) }
    val colors = LocalAppColors.current
    if (layoutGraph.nodes.isEmpty()) {
        BasicText("Graph is empty", style = TextStyle(color = colors.onBackground))
        return
    }

    val viewportWidth = constraints.maxWidth.toFloat()
    val viewportHeight = constraints.maxHeight.toFloat()

    // Store viewport size in ViewModel so zoom() can compute pan adjustment
    viewModel.viewportWidth = viewportWidth
    viewModel.viewportHeight = viewportHeight

    // Image paths per node for lazy image loading (from layout nodes)
    val imagePathsById: Map<String, List<String>> = remember(layoutGraph) {
        layoutGraph.nodes.values.associate { it.id to it.imagePaths }
    }

    // deterministic ordered list of layout nodes for composing children
    val nodeList: List<LayoutNode> = remember(layoutGraph) {
        layoutGraph.nodes.values.sortedWith(compareBy({ it.x }, { it.y }, { it.id }))
    }

    // entry node is the leftmost node (smallest x = depth-0 root)

    val zoomState = viewModel.zoomState
    val pan = viewModel.panState
    var hoveredEdgeIndex by remember(layoutGraph) { mutableStateOf<Int?>(null) }

    val cameraIconBitmap = remember {
        loadRequiredClasspathBitmap("img_states_24.png")
    }

    // Reset pan to center the entry node whenever the graph changes
    LaunchedEffect(Unit) {
        if (!loadedOnce) {
            val entryNode = layoutGraph.nodes.values.minByOrNull { it.x }

            pan.value = if (entryNode != null) {
                loadedOnce = true
                Offset(
                    viewportWidth / 2f - entryNode.x * zoomState.value,
                    viewportHeight / 2f - entryNode.y * zoomState.value
                )
            } else {
                Offset.Zero
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Layout(
            modifier = Modifier
                .fillMaxSize()
                .onPointerEvent(PointerEventType.Scroll) { event ->
                    val scroll = event.changes.firstOrNull()?.scrollDelta ?: return@onPointerEvent
                    val cursorPos = event.changes.firstOrNull()?.position ?: return@onPointerEvent

                    val scrollDeltaY = scroll.y
                    val sensitivity = 0.05f
                    val zoomFactor = 1.0f + (scrollDeltaY * sensitivity)

                    val oldZoom = zoomState.value
                    val newZoom = (oldZoom * zoomFactor).coerceIn(
                        GraphViewModel.ZOOM_MIN,
                        GraphViewModel.ZOOM_MAX
                    )
                    val actualZoomFactor = newZoom / oldZoom

                    // Calculate pan to keep cursor position fixed relative to graph content
                    val oldPan = pan.value
                    val newPan = cursorPos - (cursorPos - oldPan) * actualZoomFactor
                    pan.value = newPan
                    zoomState.value = newZoom
                }
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, panChange, zoomChange, _ ->
                        val oldZoom = zoomState.value
                        val newZoom = (oldZoom * zoomChange).coerceIn(
                            GraphViewModel.ZOOM_MIN,
                            GraphViewModel.ZOOM_MAX
                        )
                        val actualFactor = newZoom / oldZoom
                        pan.value = centroid - (centroid - pan.value) * actualFactor + panChange
                        zoomState.value = newZoom
                    }
                },
            content = {
                // background Canvas draws edges and responds to pan/zoom using precomputed layout
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures { viewModel.clearSelection() }
                        }
                        .onPointerEvent(PointerEventType.Move) { event ->
                            hoveredEdgeIndex = findHoveredEdgeIndex(
                                layoutGraph = layoutGraph,
                                pan = pan.value,
                                zoom = zoomState.value,
                                pointer = event.changes.firstOrNull()?.position
                                    ?: return@onPointerEvent
                            )
                        }
                        .onPointerEvent(PointerEventType.Exit) {
                            hoveredEdgeIndex = null
                        }
                ) {
                    drawGraphEdgesLayout(
                        layoutGraph = layoutGraph,
                        pan = pan.value,
                        zoom = zoomState.value,
                        hoveredEdgeIndex = hoveredEdgeIndex,
                        defaultColor = colors.edgeDefault,
                        hoverColor = colors.edgeHover,
                    )
                }

                // Trigger label shown at cubic midpoint when hovering an edge that has a trigger
                val hoveredEdge = hoveredEdgeIndex?.let { layoutGraph.edges.getOrNull(it) }
                val hoveredTrigger = hoveredEdge?.trigger
                if (hoveredTrigger != null && (hoveredEdge?.points?.size ?: 0) >= 4) {
                    val pts = hoveredEdge.points
                    val midX = (pts[0].x + 3 * pts[1].x + 3 * pts[2].x + pts[3].x) / 8f
                    val midY = (pts[0].y + 3 * pts[1].y + 3 * pts[2].y + pts[3].y) / 8f
                    val screenX = midX * zoomState.value + pan.value.x
                    val screenY = midY * zoomState.value + pan.value.y
                    Box(modifier = Modifier.offset { IntOffset(screenX.roundToInt(), screenY.roundToInt()) }) {
                        BasicText(
                            text = hoveredTrigger,
                            style = TextStyle(
                                color = colors.edgeHover,
                                fontSize = 11.sp,
                                background = colors.surface.copy(alpha = 0.9f),
                            ),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }

                var hoveredNodeId by remember(layoutGraph) { mutableStateOf<String?>(null) }
                var hoveredIconNodeId by remember(layoutGraph) { mutableStateOf<String?>(null) }
                // image children for each node — loaded asynchronously via loadImageBitmap
                nodeList.forEach { ln ->
                    val labelFontSize =
                        (14f * sqrt(zoomState.value.toDouble()).toFloat()).coerceIn(6f, 40f)
                    val labelTextStyle = remember(labelFontSize, colors.onBackground) {
                        TextStyle(color = colors.onBackground, fontSize = labelFontSize.sp)
                    }

                    val imagePaths = imagePathsById[ln.id] ?: emptyList()
                    val selectedState = ln.selectedState
                    val selectedPath = imagePaths.getOrNull(selectedState)
                    val labelText = formatNodeLabel(ln.id)
                    val hasMultipleStates = imagePaths.size > 1
                    val showStateIcon = hasMultipleStates && hoveredNodeId == ln.id
                    val isSelected = viewModel.selectedNodeIds.value.contains(ln.id)
                    ContextMenuArea(items = {
                        if (onViewSource != null) listOf(ContextMenuItem("View source") { onViewSource(ln.id) })
                        else emptyList()
                    }) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (isSelected) Modifier.border(
                                    2.dp,
                                    colors.nodeSelected,
                                    RoundedCornerShape(4.dp)
                                )
                                else Modifier
                            )
                            .onPointerEvent(PointerEventType.Enter) {
                                hoveredNodeId = ln.id
                            }
                            .onPointerEvent(PointerEventType.Exit) {
                                if (hoveredNodeId == ln.id) {
                                    hoveredNodeId = null
                                }
                                if (hoveredIconNodeId == ln.id) {
                                    hoveredIconNodeId = null
                                }
                            }
                            .pointerInput(ln.id) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    val isShift = currentEvent.keyboardModifiers.isShiftPressed
                                    val up = waitForUpOrCancellation()
                                    if (up != null && isShift) {
                                        viewModel.toggleNodeSelection(ln.id)
                                    }
                                }
                            }
                    ) {
                        if (selectedPath != null) {
                            AsyncImage(
                                model = File(selectedPath),
                                contentDescription = ln.id,
                                contentScale = ContentScale.FillBounds,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(modifier = Modifier.fillMaxSize()) {
                                BasicText(
                                    "No image",
                                    style = TextStyle(color = Color.Red, fontSize = 12.sp),
                                    modifier = Modifier.align(Alignment.Center)
                                )
                            }
                        }
                        if (showStateIcon) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp),
                            ) {
                                val iconAlpha = if (hoveredIconNodeId == ln.id) 0.9f else 0.5f
                                val iconSize = (24.dp * zoomState.value).coerceIn(24.dp, 32.dp)
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .size(iconSize)
                                        .alpha(iconAlpha)
                                        .clickable { viewModel.openStatePicker(ln.id) }
                                        .onPointerEvent(PointerEventType.Enter) {
                                            hoveredIconNodeId = ln.id
                                        }
                                        .onPointerEvent(PointerEventType.Exit) {
                                            if (hoveredIconNodeId == ln.id) {
                                                hoveredIconNodeId = null
                                            }
                                        }
                                        .padding(2.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                        drawImage(
                                            image = cameraIconBitmap,
                                            dstOffset = IntOffset.Zero,
                                            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                                        )
                                    }
                                }
                            }
                        }
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .offset(y = with(LocalDensity.current) { (-labelTextStyle.fontSize * 1.8).toDp() })
                                .graphicsLayer { clip = false }
                                .background(colors.labelBackground)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            BasicText(
                                text = labelText,
                                style = labelTextStyle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    } // end ContextMenuArea
                }
            }
        ) { measurables, constraints ->
            // measure children: Canvas fills all, images measured at zoomed pixel size
            val placeables = buildList {
                if (measurables.isNotEmpty()) add(measurables[0].measure(constraints))
                for (i in nodeList.indices) {
                    val ln = nodeList[i]
                    val w = (ln.width * zoomState.value).toInt()
                        .coerceAtLeast(1)
                    val h = (ln.height * zoomState.value).toInt()
                        .coerceAtLeast(1)
                    add(measurables[i + 1].measure(Constraints.fixed(w, h)))
                }
            }

            layout(constraints.maxWidth, constraints.maxHeight) {
                if (placeables.isNotEmpty()) placeables[0].place(0, 0)
                // place nodes by immutable coordinates; skip those fully outside the viewport
                for (i in nodeList.indices) {
                    val ln = nodeList[i]
                    val p = placeables[i + 1]
                    val x =
                        (ln.x * zoomState.value + pan.value.x - ln.width / 2f * zoomState.value).toInt()
                    val y =
                        (ln.y * zoomState.value + pan.value.y - ln.height / 2f * zoomState.value).toInt()
                    if (x + p.width > 0 && x < constraints.maxWidth &&
                        y + p.height > 0 && y < constraints.maxHeight
                    ) {
                        p.place(x, y)
                    }
                }
            }
        }

        ToolsPanel(
            zoom = zoomState.value,
            onZoomChange = { viewModel.setZoom(it) },
            onZoomIn = { viewModel.zoom(1.2f) },
            onZoomOut = { viewModel.zoom(1f / 1.2f) },
            selectedColor = viewModel.backgroundColorState.value,
            onColorSelect = { viewModel.backgroundColorState.value = it },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        )

        val statePickerNodeId = viewModel.statePickerNodeId.value
        if (statePickerNodeId != null) {
            val modalNode = layoutGraph.nodes[statePickerNodeId]
            if (modalNode != null) {
                StatePickerDialog(
                    nodeId = statePickerNodeId,
                    imagePaths = modalNode.imagePaths,
                    selectedState = modalNode.selectedState,
                    onSelectState = { state ->
                        viewModel.selectState(
                            nodeId = statePickerNodeId,
                            selectedState = state,
                            statesCount = modalNode.imagePaths.size
                        )
                    },
                    onClose = { viewModel.closeStatePicker() }
                )
            } else {
                viewModel.closeStatePicker()
            }
        }
    }
}

@Composable
private fun StatePickerDialog(
    nodeId: String,
    imagePaths: List<String>,
    selectedState: Int,
    onSelectState: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val screenWidth =
        with(LocalDensity.current) { Toolkit.getDefaultToolkit().screenSize.width.toDp() }
    val screenHeight =
        with(LocalDensity.current) { Toolkit.getDefaultToolkit().screenSize.height.toDp() }
    var lastClickTime by remember { mutableStateOf(0L) }

    DialogWindow(
        onCloseRequest = onClose,
        onPreviewKeyEvent = {
            if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) {
                onClose()
                true
            } else {
                false
            }
        },
        title = "States",
        state = rememberDialogState(size = DpSize(screenWidth, screenHeight)),
    ) {

        val colors = LocalAppColors.current
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.surface, RoundedCornerShape(12.dp))
                .padding(16.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                BasicText(
                    text = "Select state for ${formatNodeLabel(nodeId)}",
                    style = TextStyle(color = colors.onBackground, fontSize = 16.sp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 220.dp),
                    modifier = Modifier.fillMaxWidth()
                        .heightIn(max = 520.dp)
                ) {
                    itemsIndexed(imagePaths) { index, path ->
                        val isSelected = selectedState == index
                        Box(
                            modifier = Modifier
                                .padding(8.dp)
                                .border(
                                    width = 2.dp,
                                    color = if (isSelected) colors.stateSelected else Color.Transparent,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .padding(6.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {
                                    val epochMilliseconds = System.currentTimeMillis()
                                    if (epochMilliseconds - lastClickTime < 300) {
                                        onSelectState(index)
                                        onClose()
                                    } else {
                                        onSelectState(index)
                                        lastClickTime = epochMilliseconds
                                    }
                                }
                        ) {
                            Column {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(220.dp)
                                        .background(
                                            colors.imagePlaceholder,
                                            RoundedCornerShape(6.dp)
                                        )
                                        .padding(6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    AsyncImage(
                                        load = {
                                            File(path).inputStream()
                                                .buffered()
                                                .use(::loadImageBitmap)
                                        },
                                        contentDescription = "$nodeId-state-$index",
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                BasicText("State $index", style = TextStyle(fontSize = 12.sp))
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .align(Alignment.End)
                        .background(colors.primary, RoundedCornerShape(8.dp))
                        .clickable { onClose() }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    BasicText("Save", style = TextStyle(color = Color.White, fontSize = 13.sp))
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ToolsPanel(
    zoom: Float,
    onZoomChange: (Float) -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    selectedColor: Color,
    onColorSelect: (Color) -> Unit,
    modifier: Modifier = Modifier,
) {
    var hovered by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(targetValue = if (hovered) 0.8f else 0.4f)
    val percent = (zoom * 100).roundToInt()
    val colors = LocalAppColors.current

    Box(
        modifier = modifier
            .alpha(alpha)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Color swatches
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 6.dp)
            ) {
                GraphViewModel.BACKGROUND_PRESETS.forEach { preset ->
                    val isSelected = preset == selectedColor
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .size(20.dp)
                            .border(
                                width = 2.dp,
                                color = if (isSelected) colors.primary else Color.Transparent,
                                shape = CircleShape
                            )
                            .clip(CircleShape)
                            .background(preset)
                            .clickable { onColorSelect(preset) }
                    )
                }
            }
            // Zoom row
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.clickable { onZoomOut() }.padding(8.dp),
                    contentAlignment = Alignment.Center,
                ) { BasicText("-") }
                ZoomSlider(
                    value = zoom,
                    onValueChange = onZoomChange,
                    valueRange = GraphViewModel.ZOOM_MIN..GraphViewModel.ZOOM_MAX,
                    modifier = Modifier.width(120.dp).height(24.dp),
                )
                Box(
                    modifier = Modifier.clickable { onZoomIn() }.padding(8.dp),
                    contentAlignment = Alignment.Center,
                ) { BasicText("+") }
                BasicText(
                    text = "$percent%",
                    style = TextStyle(fontSize = 12.sp),
                    modifier = Modifier
                        .padding(start = 4.dp, end = 8.dp)
                        .width(36.dp),
                )
            }
        }
    }
}

/** Simple Foundation-only horizontal slider using Canvas + pointer input. */
@Composable
private fun ZoomSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
) {
    val fraction = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start))
        .coerceIn(0f, 1f)
    val primary = LocalAppColors.current.primary
    Box(
        modifier = modifier
            .pointerInput(valueRange) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    val newFraction = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                    onValueChange(valueRange.start + newFraction * (valueRange.endInclusive - valueRange.start))
                }
            }
            .pointerInput(valueRange) {
                detectTapGestures { offset ->
                    val newFraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                    onValueChange(valueRange.start + newFraction * (valueRange.endInclusive - valueRange.start))
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val trackH = 2.dp.toPx()
            val thumbR = 6.dp.toPx()
            val trackY = size.height / 2f
            val trackStart = thumbR
            val trackEnd = size.width - thumbR
            val thumbX = trackStart + (trackEnd - trackStart) * fraction
            drawLine(
                Color.LightGray,
                Offset(trackStart, trackY),
                Offset(trackEnd, trackY),
                strokeWidth = trackH
            )
            drawLine(
                primary,
                Offset(trackStart, trackY),
                Offset(thumbX, trackY),
                strokeWidth = trackH
            )
            drawCircle(primary, radius = thumbR, center = Offset(thumbX, trackY))
        }
    }
}


/**
 * Generic async image loader from the JetBrains Compose Multiplatform tutorial.
 * Loads [ImageBitmap] on [kotlinx.coroutines.Dispatchers.IO] and renders directly on Canvas.
 */
@Composable
private fun AsyncImage(
    load: suspend () -> ImageBitmap,
    contentDescription: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var error by remember { mutableStateOf<Throwable?>(null) }

    LaunchedEffect(contentDescription) {
        try {
            image = withContext(Dispatchers.IO) { load() }
        } catch (e: Throwable) {
            Logger.w { "[AppFlower] AsyncImage: failed to load '$contentDescription': ${e::class.simpleName}: ${e.message}" }
            error = e
        }
    }

    val bitmap = image
    if (bitmap != null) {
        Canvas(modifier = modifier) {
            val srcSize = Size(bitmap.width.toFloat(), bitmap.height.toFloat())
            val dstSize = size
            val scaleFactor = when (contentScale) {
                ContentScale.Fit -> minOf(dstSize.width / srcSize.width, dstSize.height / srcSize.height)
                ContentScale.FillWidth -> dstSize.width / srcSize.width
                ContentScale.FillHeight -> dstSize.height / srcSize.height
                ContentScale.Crop -> maxOf(dstSize.width / srcSize.width, dstSize.height / srcSize.height)
                else -> minOf(dstSize.width / srcSize.width, dstSize.height / srcSize.height)
            }
            val scaledWidth = srcSize.width * scaleFactor
            val scaledHeight = srcSize.height * scaleFactor
            val offsetX = (dstSize.width - scaledWidth) / 2f
            val offsetY = (dstSize.height - scaledHeight) / 2f

            drawImage(
                image = bitmap,
                dstOffset = IntOffset(offsetX.toInt(), offsetY.toInt()),
                dstSize = IntSize(scaledWidth.toInt(), scaledHeight.toInt()),
            )
        }
    } else {
        Box(modifier = modifier) {
            if (error != null) {
                BasicText(
                    "Error: ${error!!.message}",
                    style = TextStyle(color = Color.Red, fontSize = 12.sp),
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                BasicText("Loading...", style = TextStyle(color = Color.Gray, fontSize = 12.sp))
            }
        }
    }
}

/**
 * Generic async image loader from the JetBrains Compose Multiplatform tutorial.
 * Loads [T] on [kotlinx.coroutines.Dispatchers.IO] and renders via [painterFor].
 */
@Composable
private fun AsyncImage(
    model: Any?,
    contentDescription: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val load = when (model) {
        null -> {
            error("no model")
        }

        is File -> {
            {
                model.inputStream()
                    .buffered()
                    .use(::loadImageBitmap)
            }
        }

        else -> error("Unsupported AsyncImage model type: ${model::class}")
    }
    var image by remember(model) { mutableStateOf<ImageBitmap?>(null) }
    var error by remember(model) { mutableStateOf<Throwable?>(null) }

    LaunchedEffect(model) {
        try {
            image = withContext(Dispatchers.IO) { load() }
        } catch (e: Throwable) {
            Logger.w { "[AppFlower] AsyncImage: failed to load '$contentDescription': ${e::class.simpleName}: ${e.message}" }
            error = e
        }
    }

    val bitmap = image
    if (bitmap != null) {
        // Draw directly on Canvas to avoid passing Painter types across composable
        // boundaries, which causes $stable field errors in IntelliJ plugin runtime.
        Canvas(modifier = modifier) {
            val srcSize = Size(bitmap.width.toFloat(), bitmap.height.toFloat())
            val dstSize = size
            val scaleFactor = when (contentScale) {
                ContentScale.Fit -> minOf(dstSize.width / srcSize.width, dstSize.height / srcSize.height)
                ContentScale.FillWidth -> dstSize.width / srcSize.width
                ContentScale.FillHeight -> dstSize.height / srcSize.height
                ContentScale.Crop -> maxOf(dstSize.width / srcSize.width, dstSize.height / srcSize.height)
                else -> minOf(dstSize.width / srcSize.width, dstSize.height / srcSize.height)
            }
            val scaledWidth = srcSize.width * scaleFactor
            val scaledHeight = srcSize.height * scaleFactor
            val offsetX = (dstSize.width - scaledWidth) / 2f
            val offsetY = (dstSize.height - scaledHeight) / 2f

            drawImage(
                image = bitmap,
                dstOffset = IntOffset(offsetX.toInt(), offsetY.toInt()),
                dstSize = IntSize(scaledWidth.toInt(), scaledHeight.toInt()),
            )
        }
    } else {
        Box(modifier = modifier) {
            if (error != null) {
                BasicText(
                    "Error: ${error!!.message}",
                    style = TextStyle(color = Color.Red, fontSize = 12.sp),
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                BasicText("Loading...", style = TextStyle(color = Color.Gray, fontSize = 12.sp))
            }
        }
    }
}


private fun DrawScope.drawGraphEdgesLayout(
    layoutGraph: LayoutGraph,
    pan: Offset,
    zoom: Float,
    hoveredEdgeIndex: Int?,
    defaultColor: Color,
    hoverColor: Color,
) {
    layoutGraph.edges.forEachIndexed { edgeIndex, edge ->
        val points = edge.points.map { Offset(it.x * zoom + pan.x, it.y * zoom + pan.y) }
        if (points.size >= 4) {
            val hovered = hoveredEdgeIndex == edgeIndex
            val edgeColor = if (hovered) hoverColor else defaultColor
            val strokeWidth = if (hovered) 3.5f else 1.6f
            val path = Path().apply {
                moveTo(points[0].x, points[0].y)
                cubicTo(
                    points[1].x, points[1].y,
                    points[2].x, points[2].y,
                    points[3].x, points[3].y
                )
            }
            drawPath(
                path = path,
                color = edgeColor,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // Arrow at end, aligned with cubic tangent at end (end - control2).
            val last = points.last()
            val prev = points[points.size - 2]
            val angle = atan2(last.y - prev.y, last.x - prev.x)
            val zoomFactor = zoom.coerceIn(0.7f, 2.2f)
            val arrowSize = (if (hovered) 12f else 9f) * zoomFactor
            val arrowEnd1 = Offset(
                last.x - arrowSize * cos(angle - Math.PI / 6).toFloat(),
                last.y - arrowSize * sin(angle - Math.PI / 6).toFloat()
            )
            val arrowEnd2 = Offset(
                last.x - arrowSize * cos(angle + Math.PI / 6).toFloat(),
                last.y - arrowSize * sin(angle + Math.PI / 6).toFloat()
            )
            drawLine(edgeColor, last, arrowEnd1, strokeWidth = strokeWidth)
            drawLine(edgeColor, last, arrowEnd2, strokeWidth = strokeWidth)
        } else if (points.size >= 2) {
            for (i in 0 until points.size - 1) {
                drawLine(defaultColor, points[i], points[i + 1], strokeWidth = 1.6f)
            }
        }
    }
}

private fun findHoveredEdgeIndex(
    layoutGraph: LayoutGraph,
    pan: Offset,
    zoom: Float,
    pointer: Offset
): Int? {
    var bestIndex: Int? = null
    var bestDistance = Float.MAX_VALUE
    layoutGraph.edges.forEachIndexed { index, edge ->
        val points = edge.points.map { Offset(it.x * zoom + pan.x, it.y * zoom + pan.y) }
        val distance = when {
            points.size >= 4 -> distanceToCubicBezier(
                p0 = points[0],
                p1 = points[1],
                p2 = points[2],
                p3 = points[3],
                p = pointer
            )

            points.size >= 2 -> points
                .zipWithNext()
                .minOf { (a, b) -> distanceToSegment(pointer, a, b) }

            else -> Float.MAX_VALUE
        }
        if (distance < bestDistance) {
            bestDistance = distance
            bestIndex = index
        }
    }
    val tolerancePx = 9f
    return if (bestDistance <= tolerancePx) bestIndex else null
}

private fun distanceToCubicBezier(
    p0: Offset,
    p1: Offset,
    p2: Offset,
    p3: Offset,
    p: Offset
): Float {
    val samples = 32
    var best = Float.MAX_VALUE
    var prev = cubicPoint(p0, p1, p2, p3, 0f)
    for (i in 1..samples) {
        val t = i / samples.toFloat()
        val current = cubicPoint(p0, p1, p2, p3, t)
        best = minOf(best, distanceToSegment(p, prev, current))
        prev = current
    }
    return best
}

private fun cubicPoint(
    p0: Offset,
    p1: Offset,
    p2: Offset,
    p3: Offset,
    t: Float
): Offset {
    val u = 1f - t
    val tt = t * t
    val uu = u * u
    val uuu = uu * u
    val ttt = tt * t
    return Offset(
        x = uuu * p0.x + 3f * uu * t * p1.x + 3f * u * tt * p2.x + ttt * p3.x,
        y = uuu * p0.y + 3f * uu * t * p1.y + 3f * u * tt * p2.y + ttt * p3.y
    )
}

private fun distanceToSegment(p: Offset, a: Offset, b: Offset): Float {
    val ab = b - a
    val ap = p - a
    val lengthSq = ab.x * ab.x + ab.y * ab.y
    if (lengthSq == 0f) return kotlin.math.hypot((p.x - a.x).toDouble(), (p.y - a.y).toDouble())
        .toFloat()
    val t = ((ap.x * ab.x + ap.y * ab.y) / lengthSq).coerceIn(0f, 1f)
    val projection = Offset(a.x + ab.x * t, a.y + ab.y * t)
    return kotlin.math.hypot((p.x - projection.x).toDouble(), (p.y - projection.y).toDouble())
        .toFloat()
}

internal fun formatNodeLabel(nodeId: String): String {
    val leaf = (nodeId.substringAfterLast(':', nodeId))
        .replace(
            Regex("(Screen|Route|Fragment|Activity|Destination)$", RegexOption.IGNORE_CASE),
            ""
        )
        .replace(Regex("[_-]+"), " ")
        .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
        .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1 $2")
        .trim()
    return if (leaf.isNotEmpty()) leaf else nodeId
}

private fun loadRequiredClasspathBitmap(path: String) =
    GraphViewModel::class.java.classLoader
        ?.getResourceAsStream(path)
        ?.use(::loadImageBitmap)
        ?: throw IllegalArgumentException("Resource $path not found")

@Preview
@Composable
private fun PreviewGraphVisualizer() {
    val appGraph = AppGraph(
        GraphMetadata(version = "", generated_at = ""),
        subgraphs = mapOf(
            "root" to Subgraph(
                key = "root",
                root_screen = "Screen1",
                screens = listOf(
                    Screen(id = "Screen1"),
                    Screen(id = "Screen2"),
                    Screen(id = "Screen3"),
                ),
                connections = listOf(
                    Connection(
                        ConnectionEndpoint(type = "screen", screen_id = "Screen1", subgraph = "root"),
                        ConnectionEndpoint(type = "screen", screen_id = "Screen2", subgraph = "root"),
                    ),
                )
            )
        )
    )
    val viewModel = GraphViewModel().apply { buildFromAppGraphV2(appGraph) }
    AppTheme(isDark = true) { GraphVisualizer(viewModel = viewModel) }
}
