package com.keymusicman.appflower.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keymusicman.appflower.model.AppGraph
import com.keymusicman.appflower.model.GraphEdge
import com.keymusicman.appflower.model.GraphNode
import com.keymusicman.appflower.model.LayoutGraph
import com.keymusicman.appflower.model.LayoutNode
import com.keymusicman.appflower.model.Transition
import com.keymusicman.appflower.model.buildLayoutGraph
import com.keymusicman.appflower.viewmodel.GraphViewModel
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.imageio.ImageIO
import javax.imageio.stream.FileImageInputStream
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val ColorBackground = Color(0xFFF5F5F5)
private val ColorOnBackground = Color(0xFF212121)
private val ColorSurface = Color(0xFFFFFFFF)
private val ColorPrimary = Color(0xFF2196F3)

@Composable
fun GraphVisualizer(
    appBasePath: String? = null,
    modifier: Modifier = Modifier,
    viewModel: GraphViewModel,
) {
    val graph = viewModel.graphState.value
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(ColorBackground),
        contentAlignment = Alignment.TopStart,
    ) {
        if (graph == null) {
            BasicText("No graph loaded", style = TextStyle(color = ColorOnBackground))
            return@BoxWithConstraints
        }

        if (graph.nodes.isEmpty()) {
            BasicText("Graph is empty", style = TextStyle(color = ColorOnBackground))
            return@BoxWithConstraints
        }

        val viewportWidth = constraints.maxWidth.toFloat()
        val viewportHeight = constraints.maxHeight.toFloat()

        // Store viewport size in ViewModel so zoom() can compute pan adjustment
        viewModel.viewportWidth = viewportWidth
        viewModel.viewportHeight = viewportHeight

        // Build immutable domain and layout once per graph
        val domainNodes = graph.nodes.map { n -> GraphNode(n.id, n.imagePaths, n.selectedState) }
        val domainEdges = graph.edges.map { e -> GraphEdge(e.from, e.to, e.trigger) }

        // Read only image dimensions (no pixel data) for layout sizing
        val imageDimensions: Map<String, Pair<Int, Int>> = remember(graph, appBasePath) {
            domainNodes.associate { node ->
                val path = node.imagePaths.firstOrNull()
                val dim = path?.let { getImageDimension(it) }
                node.id to (dim ?: (540 to 360))
            }
        }

        // File paths per node for Coil to load lazily
        val pathById: Map<String, String?> = remember(graph) {
            domainNodes.associate { it.id to it.imagePaths.firstOrNull() }
        }

        // build layout graph once and reuse across recompositions
        val layoutGraph: LayoutGraph = remember(graph, appBasePath) {
            buildLayoutGraph(domainNodes, domainEdges, imageDimensions)
        }

        // deterministic ordered list of layout nodes for composing children
        val nodeList: List<LayoutNode> = remember(layoutGraph) {
            layoutGraph.nodes.values.sortedWith(compareBy({ it.x }, { it.y }, { it.id }))
        }

        // entry node is the leftmost node (smallest x = depth-0 root)
        val entryNode: LayoutNode? = remember(layoutGraph) {
            layoutGraph.nodes.values.minByOrNull { it.x }
        }

        val zoomState = viewModel.zoomState
        val pan = viewModel.panState

        // Reset pan to center the entry node whenever the graph changes
        LaunchedEffect(graph) {
            pan.value = if (entryNode != null) {
                Offset(
                    viewportWidth / 2f - entryNode.x * zoomState.value,
                    viewportHeight / 2f - entryNode.y * zoomState.value
                )
            } else {
                Offset.Zero
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            Layout(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { centroid, panDelta, zoomFactor, _ ->
                            val oldZoom = zoomState.value
                            val newZoom = (oldZoom * zoomFactor).coerceIn(
                                GraphViewModel.ZOOM_MIN,
                                GraphViewModel.ZOOM_MAX
                            )
                            pan.value =
                                centroid - (centroid - pan.value) * (newZoom / oldZoom) + panDelta
                            zoomState.value = newZoom
                        }
                    },
                content = {
                    // background Canvas draws edges and responds to pan/zoom using precomputed layout
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawGraphEdgesLayout(layoutGraph, pan.value, zoomState.value)
                    }

                    // image children for each node — loaded asynchronously via loadImageBitmap
                    nodeList.forEach { ln ->
                        val path = pathById[ln.id]
                        if (path != null) {
                            AsyncImage(
                                load = {
                                    File(path).inputStream()
                                        .buffered()
                                        .use(::loadImageBitmap)
                                },
                                painterFor = { remember { BitmapPainter(it) } },
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

            ZoomControls(
                zoom = zoomState.value,
                onZoomChange = { viewModel.setZoom(it) },
                onZoomIn = { viewModel.zoom(1.2f) },
                onZoomOut = { viewModel.zoom(1f / 1.2f) },
                modifier = Modifier.align(Alignment.BottomEnd)
                    .padding(16.dp)
            )
        }
    }
}

/**
 * Zoom control overlay: `- [slider] + 75%`, semi-transparent until hovered.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ZoomControls(
    zoom: Float,
    onZoomChange: (Float) -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var hovered by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(targetValue = if (hovered) 0.8f else 0.4f)
    val percent = (zoom * 100).roundToInt()

    Box(
        modifier = modifier
            .alpha(alpha)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .clip(CircleShape)
            .background(ColorSurface),
    ) {
        Row(
            modifier = Modifier,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .clickable { onZoomOut() }
                    .padding(12.dp),
                contentAlignment = Alignment.Center,
            ) { BasicText("-") }
            ZoomSlider(
                value = zoom,
                onValueChange = onZoomChange,
                valueRange = GraphViewModel.ZOOM_MIN..GraphViewModel.ZOOM_MAX,
                modifier = Modifier.width(140.dp)
                    .height(24.dp),
            )
            Box(
                modifier = Modifier
                    .clickable { onZoomIn() }
                    .padding(12.dp),
                contentAlignment = Alignment.Center,
            ) { BasicText("+") }
            BasicText(
                text = "$percent%",
                style = TextStyle(fontSize = 12.sp),
                modifier = Modifier.padding(start = 4.dp, end = 16.dp)
                    .width(40.dp),
            )
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
                ColorPrimary,
                Offset(trackStart, trackY),
                Offset(thumbX, trackY),
                strokeWidth = trackH
            )
            drawCircle(ColorPrimary, radius = thumbR, center = Offset(thumbX, trackY))
        }
    }
}


/**
 * Generic async image loader from the JetBrains Compose Multiplatform tutorial.
 * Loads [T] on [kotlinx.coroutines.Dispatchers.IO] and renders via [painterFor].
 */
@Composable
private fun <T> AsyncImage(
    load: suspend () -> T,
    painterFor: @Composable (T) -> Painter,
    contentDescription: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    var image by remember { mutableStateOf<T?>(null) }
    var error by remember { mutableStateOf<Throwable?>(null) }

    LaunchedEffect(contentDescription) {
        try {
            image = withContext(kotlinx.coroutines.Dispatchers.IO) { load() }
        } catch (e: Throwable) {
            System.err.println("[AppFlower] AsyncImage: failed to load '$contentDescription': ${e::class.simpleName}: ${e.message}")
            error = e
        }
    }

    if (image != null) {
        Image(
            painter = painterFor(image!!),
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = modifier,
        )
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
 * Gets image dimensions for a given file by reading only the image header — no pixel data loaded.
 * @return width to height in pixels, or null if the file is not a known image
 */
private fun getImageDimension(path: String): Pair<Int, Int>? {
    val file = File(path)
    if (!file.exists()) return null
    val pos = file.name.lastIndexOf('.')
    if (pos == -1) return null
    val suffix = file.name.substring(pos + 1)
    val iter = ImageIO.getImageReadersBySuffix(suffix)
    while (iter.hasNext()) {
        val reader = iter.next()
        var stream: FileImageInputStream? = null
        try {
            stream = FileImageInputStream(file)
            reader.setInput(stream)
            val width = reader.getWidth(reader.minIndex)
            val height = reader.getHeight(reader.minIndex)
            return width to height
        } catch (_: Exception) {
            // try next reader
        } finally {
            stream?.close()
            reader.dispose()
        }
    }
    return null
}

private fun DrawScope.drawGraphEdgesLayout(layoutGraph: LayoutGraph, pan: Offset, zoom: Float) {
    layoutGraph.edges.forEach { edge ->
        val points = edge.points.map { Offset(it.x * zoom + pan.x, it.y * zoom + pan.y) }
        if (points.size >= 2) {
            for (i in 0 until points.size - 1) {
                drawLine(Color.Gray, points[i], points[i + 1], strokeWidth = 2f)
            }
            // arrow at end
            val last = points.last()
            val prev = points[points.size - 2]
            val angle = atan2(last.y - prev.y, last.x - prev.x)
            val arrowSize = 12f * zoom
            val arrowEnd1 = Offset(
                last.x - arrowSize * cos(angle - Math.PI / 6).toFloat(),
                last.y - arrowSize * sin(angle - Math.PI / 6).toFloat()
            )
            val arrowEnd2 = Offset(
                last.x - arrowSize * cos(angle + Math.PI / 6).toFloat(),
                last.y - arrowSize * sin(angle + Math.PI / 6).toFloat()
            )
            drawLine(Color.Gray, last, arrowEnd1, strokeWidth = 2f)
            drawLine(Color.Gray, last, arrowEnd2, strokeWidth = 2f)
        }
    }
}

@Preview
@Composable
private fun PreviewGraphVisualizer() {
    val transitions = listOf(
        Transition("Screen1", "Screen2", "onClick"),
        Transition("Screen2", "Screen3", "onClick"),
    )
    val appGraph = AppGraph(transitions)
    val viewModel = GraphViewModel().apply { buildFromAppGraph(appGraph) }
    GraphVisualizer(viewModel = viewModel)
}
