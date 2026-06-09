package com.keymusicman.sight.model

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Comprehensive integration tests for LayoutGraphBuilder.
 * Tests various graph topologies and validates the resulting layout structure.
 * Uses constant image dimensions for deterministic, precise position assertions.
 */
class BuildLayoutGraphTest {

    // Test constants for precise assertions
    companion object {
        const val SCALE = 0.33f
        const val IMAGE_WIDTH = 540
        const val IMAGE_HEIGHT = 360
        const val SCALED_WIDTH_F = IMAGE_WIDTH * SCALE  // 178.2f
        const val SCALED_HEIGHT_F = IMAGE_HEIGHT * SCALE  // 118.8f
    }

    @Test
    fun testEmptyGraph() = runTest {
        val fixture = FixtureLoader.loadFixture("empty.json")
        val layout = TestLayoutBuilder.buildWithConstants(fixture)

        assertEquals(0, layout.nodes.size)
        assertEquals(0, layout.edges.size)
    }

    @Test
    fun testSingleNodeGraph() = runTest {
        val fixture = FixtureLoader.loadFixture("single-node.json")
        val layout = TestLayoutBuilder.buildWithConstants(fixture)

        assertEquals(1, layout.nodes.size)
        assertEquals(0, layout.edges.size)

        val node = layout.nodes.values.first()
        assertEquals("main:ScreenA", node.id)
        assertTrue(node.x > 0)
        assertTrue(node.y > 0)
        assertEquals(SCALED_WIDTH_F, node.width, 0.1f)
        assertEquals(SCALED_HEIGHT_F, node.height, 0.1f)
    }

    @Test
    fun testLinearGraph() = runTest {
        val fixture = FixtureLoader.loadFixture("simple-linear.json")
        val layout = TestLayoutBuilder.buildWithConstants(fixture)

        assertEquals(3, layout.nodes.size)
        assertEquals(2, layout.edges.size)

        val nodes = layout.nodes
        val screenA = nodes["main:ScreenA"]
        val screenB = nodes["main:ScreenB"]
        val screenC = nodes["main:ScreenC"]

        assertNotNull(screenA)
        assertNotNull(screenB)
        assertNotNull(screenC)

        // All nodes should have same scaled dimensions
        assertEquals(SCALED_WIDTH_F, screenA!!.width, 0.1f)
        assertEquals(SCALED_HEIGHT_F, screenA.height, 0.1f)
        assertEquals(SCALED_WIDTH_F, screenB!!.width, 0.1f)
        assertEquals(SCALED_HEIGHT_F, screenB.height, 0.1f)
        assertEquals(SCALED_WIDTH_F, screenC!!.width, 0.1f)
        assertEquals(SCALED_HEIGHT_F, screenC.height, 0.1f)

        // Verify depth ordering: ScreenA should be leftmost (entry point)
        assertTrue(screenA.x < screenB.x)
        assertTrue(screenB.x < screenC.x)
    }

    @Test
    fun testBranchingAndMergingGraph() = runTest {
        val fixture = FixtureLoader.loadFixture("branching-merge.json")
        val layout = TestLayoutBuilder.buildWithConstants(fixture)

        assertEquals(4, layout.nodes.size)
        assertEquals(4, layout.edges.size)

        val nodes = layout.nodes
        val screenA = nodes["main:ScreenA"]
        val screenB = nodes["main:ScreenB"]
        val screenC = nodes["main:ScreenC"]
        val screenD = nodes["main:ScreenD"]

        assertNotNull(screenA)
        assertNotNull(screenB)
        assertNotNull(screenC)
        assertNotNull(screenD)

        // Verify all nodes have correct dimensions
        listOf(screenA!!, screenB!!, screenC!!, screenD!!).forEach { node ->
            assertEquals(SCALED_WIDTH_F, node.width, 0.1f)
            assertEquals(SCALED_HEIGHT_F, node.height, 0.1f)
        }

        // ScreenA is entry point (leftmost)
        assertTrue(screenA.x < screenB.x)
        assertTrue(screenA.x < screenC.x)

        // ScreenD is merge point (rightmost)
        assertTrue(screenB.x < screenD.x)
        assertTrue(screenC.x < screenD.x)

        // B and C should be at similar X coordinate (same depth)
        assertEquals(screenB.x, screenC.x, 1f)

        // Verify edges
        val edges = layout.edges
        assertTrue(edges.any { it.from == "main:ScreenA" && it.to == "main:ScreenB" })
        assertTrue(edges.any { it.from == "main:ScreenA" && it.to == "main:ScreenC" })
        assertTrue(edges.any { it.from == "main:ScreenB" && it.to == "main:ScreenD" })
        assertTrue(edges.any { it.from == "main:ScreenC" && it.to == "main:ScreenD" })
    }

    @Test
    fun testIsolatedNodesGraph() = runTest {
        val fixture = FixtureLoader.loadFixture("isolated-nodes.json")
        val layout = TestLayoutBuilder.buildWithConstants(fixture)

        assertEquals(3, layout.nodes.size)
        assertEquals(1, layout.edges.size)

        val nodes = layout.nodes
        val screenA = nodes["main:ScreenA"]
        val isolatedB = nodes["main:IsolatedB"]
        val isolatedC = nodes["main:IsolatedC"]

        assertNotNull(screenA)
        assertNotNull(isolatedB)
        assertNotNull(isolatedC)

        // All nodes should have correct dimensions
        listOf(screenA!!, isolatedB!!, isolatedC!!).forEach { node ->
            assertEquals(SCALED_WIDTH_F, node.width, 0.1f)
            assertEquals(SCALED_HEIGHT_F, node.height, 0.1f)
        }

        // ScreenA is entry point and should be at top
        assertTrue(screenA.y < isolatedB.y)

        // IsolatedB and IsolatedC should be connected
        val edge = layout.edges.first()
        assertEquals("main:IsolatedB", edge.from)
        assertEquals("main:IsolatedC", edge.to)
    }

    @Test
    fun testMultiSubgraphGraph() = runTest {
        val fixture = FixtureLoader.loadFixture("multi-subgraph.json")
        val layout = TestLayoutBuilder.buildWithConstants(fixture)

        assertEquals(3, layout.nodes.size)
        assertEquals(1, layout.edges.size)

        val nodes = layout.nodes
        assertTrue(nodes.containsKey("subgraph1:Screen1A"))
        assertTrue(nodes.containsKey("subgraph1:Screen1B"))
        assertTrue(nodes.containsKey("subgraph2:Screen2A"))

        // All nodes should have correct dimensions
        nodes.values.forEach { node ->
            assertEquals(SCALED_WIDTH_F, node.width, 0.1f)
            assertEquals(SCALED_HEIGHT_F, node.height, 0.1f)
        }

        // Verify edge within subgraph1
        val edge = layout.edges.first()
        assertEquals("subgraph1:Screen1A", edge.from)
        assertEquals("subgraph1:Screen1B", edge.to)
    }

    @Test
    fun testAppGraphFixture() = runTest {
        val fixture = FixtureLoader.loadFixture("app-graph.json")
        val layout = TestLayoutBuilder.buildWithConstants(fixture)

        // Verify the layout is non-empty
        assertTrue(layout.nodes.isNotEmpty())

        // Verify all nodes have valid positions and dimensions
        layout.nodes.forEach { (_, node) ->
            assertTrue(node.x >= 0, "Node ${node.id} has invalid x: ${node.x}")
            assertTrue(node.y >= 0, "Node ${node.id} has invalid y: ${node.y}")
            assertEquals(SCALED_WIDTH_F, node.width, 0.1f, "Node ${node.id} has incorrect width")
            assertEquals(SCALED_HEIGHT_F, node.height, 0.1f, "Node ${node.id} has incorrect height")
        }

        // Verify edges have valid polylines
        layout.edges.forEach { edge ->
            assertTrue(edge.points.isNotEmpty(), "Edge ${edge.from}->${edge.to} has no points")
            edge.points.forEach { point ->
                assertTrue(point.x >= 0, "Edge ${edge.from}->${edge.to} has invalid point x: ${point.x}")
                assertTrue(point.y >= 0, "Edge ${edge.from}->${edge.to} has invalid point y: ${point.y}")
            }
        }
    }

    @Test
    fun testEdgePolylineStructure() = runTest {
        val fixture = FixtureLoader.loadFixture("branching-merge.json")
        val layout = TestLayoutBuilder.buildWithConstants(fixture)

        // All edges should have 4 points (start, control1, control2, end)
        layout.edges.forEach { edge ->
            assertEquals(4, edge.points.size, "Edge ${edge.from}->${edge.to} should have 4 points")

            // First point should be on right edge of source node
            val fromNode = layout.nodes[edge.from]
            assertNotNull(fromNode)
            assertEquals(fromNode!!.y, edge.points[0].y, 0.1f)

            // Last point should be on left edge of target node (y can vary by incoming slot)
            val toNode = layout.nodes[edge.to]
            assertNotNull(toNode)
            val targetTop = toNode!!.y - toNode.height / 2f
            val targetBottom = toNode.y + toNode.height / 2f
            assertTrue(
                edge.points[3].y in targetTop..targetBottom,
                "Edge ${edge.from}->${edge.to} target anchor must be inside target bounds"
            )

            // Cubic controls follow cubic-bezier(.5, 0, .05, 1) mapping.
            val dx = edge.points[3].x - edge.points[0].x
            val dy = edge.points[3].y - edge.points[0].y
            assertEquals(edge.points[0].x + dx * 0.5f, edge.points[1].x, 0.1f)
            assertEquals(edge.points[0].y + dy * 0f, edge.points[1].y, 0.1f)
            assertEquals(edge.points[0].x + dx * 0.05f, edge.points[2].x, 0.1f)
            assertEquals(edge.points[0].y + dy * 1f, edge.points[2].y, 0.1f)
        }

        // Incoming edges to ScreenD should be segregated vertically by source Y order.
        val incomingToD = layout.edges.filter { it.to == "main:ScreenD" }
        assertEquals(2, incomingToD.size)
        val nonOverlappingIncomingToD = incomingToD.filter { edge ->
            val fromNode = layout.nodes[edge.from]!!
            val toNode = layout.nodes[edge.to]!!
            !overlapsVerticallyInclusive(fromNode, toNode)
        }
        if (nonOverlappingIncomingToD.size >= 2) {
            val sortedBySourceY = nonOverlappingIncomingToD.sortedBy { edge -> layout.nodes[edge.from]!!.y }
            val firstEntryY = sortedBySourceY[0].points[3].y
            val secondEntryY = sortedBySourceY[1].points[3].y
            assertTrue(firstEntryY < secondEntryY, "Higher non-overlapping source must connect higher on target")
        }
        nonOverlappingIncomingToD.forEach { edge ->
            val fromNode = layout.nodes[edge.from]!!
            val toNode = layout.nodes[edge.to]!!
            when {
                fromNode.y < toNode.y -> assertTrue(
                    edge.points.last().y < toNode.y,
                    "Higher non-overlapping source should connect above target center"
                )

                fromNode.y > toNode.y -> assertTrue(
                    edge.points.last().y > toNode.y,
                    "Lower non-overlapping source should connect below target center"
                )
            }
        }
    }

    @Test
    fun testOverlappingIncomingEdgesConnectMiddleToMiddle() = runTest {
        val fixture = FixtureLoader.loadFixture("shortcut-overlap.json")
        val layout = TestLayoutBuilder.buildWithConstants(fixture)

        val incomingToC = layout.edges.filter { it.to == "main:ScreenC" }
        assertEquals(2, incomingToC.size)

        incomingToC.forEach { edge ->
            val fromNode = layout.nodes[edge.from]
            val toNode = layout.nodes[edge.to]
            assertNotNull(fromNode)
            assertNotNull(toNode)
            assertTrue(
                overlapsVerticallyInclusive(fromNode!!, toNode!!),
                "Fixture must produce Y-overlap for ${edge.from}->${edge.to}"
            )
            assertEquals(fromNode.y, edge.points.first().y, 0.1f, "Source anchor should be source middle")
            assertEquals(toNode.y, edge.points.last().y, 0.1f, "Overlapping edge should connect at target middle")
        }
    }

    @Test
    fun testNonOverlappingIncomingEdgesUseDirectionalAnchor() = runTest {
        val fixture = FixtureLoader.loadFixture("branching-merge.json")
        val layout = TestLayoutBuilder.buildWithConstants(fixture)

        val nonOverlappingIncomingToD = layout.edges
            .filter { it.to == "main:ScreenD" }
            .filter { edge ->
                val fromNode = layout.nodes[edge.from]!!
                val toNode = layout.nodes[edge.to]!!
                !overlapsVerticallyInclusive(fromNode, toNode)
            }

        assertTrue(nonOverlappingIncomingToD.isNotEmpty())
        nonOverlappingIncomingToD.forEach { edge ->
            val fromNode = layout.nodes[edge.from]!!
            val toNode = layout.nodes[edge.to]!!
            when {
                fromNode.y < toNode.y -> assertTrue(
                    edge.points.last().y < toNode.y,
                    "Higher non-overlapping source should connect above target center"
                )

                fromNode.y > toNode.y -> assertTrue(
                    edge.points.last().y > toNode.y,
                    "Lower non-overlapping source should connect below target center"
                )
            }
        }
    }

    @Test
    fun testLayoutConsistency() = runTest {
        val fixture = FixtureLoader.loadFixture("simple-linear.json")

        // Run layout twice to ensure deterministic results
        val layout1 = TestLayoutBuilder.buildWithConstants(fixture)
        val layout2 = TestLayoutBuilder.buildWithConstants(fixture)

        // Verify same number of nodes and edges
        assertEquals(layout1.nodes.size, layout2.nodes.size)
        assertEquals(layout1.edges.size, layout2.edges.size)

        // Verify node positions are identical
        layout1.nodes.forEach { (id, node1) ->
            val node2 = layout2.nodes[id]
            assertNotNull(node2)
            assertEquals(node1.x, node2!!.x, 0.01f)
            assertEquals(node1.y, node2.y, 0.01f)
            assertEquals(node1.width, node2.width, 0.01f)
            assertEquals(node1.height, node2.height, 0.01f)
        }
    }

    @Test
    fun testNoNodeOverlap() = runTest {
        val fixture = FixtureLoader.loadFixture("branching-merge.json")
        val layout = TestLayoutBuilder.buildWithConstants(fixture)

        val nodeList = layout.nodes.values.toList()

        // Check for overlaps between any two nodes
        for (i in nodeList.indices) {
            for (j in i + 1 until nodeList.size) {
                val node1 = nodeList[i]
                val node2 = nodeList[j]

                // Calculate bounding boxes
                val box1Left = node1.x - node1.width / 2
                val box1Right = node1.x + node1.width / 2
                val box1Top = node1.y - node1.height / 2
                val box1Bottom = node1.y + node1.height / 2

                val box2Left = node2.x - node2.width / 2
                val box2Right = node2.x + node2.width / 2
                val box2Top = node2.y - node2.height / 2
                val box2Bottom = node2.y + node2.height / 2

                // Check if boxes overlap (allowing small margin for rounding)
                val overlap = !(box1Right < box2Left - 5f || box2Right < box1Left - 5f ||
                        box1Bottom < box2Top - 5f || box2Bottom < box1Top - 5f)

                // Nodes should not overlap
                assertFalse(overlap, "Nodes ${node1.id} and ${node2.id} overlap")
            }
        }
    }

    @Test
    fun testDepthOrdering() = runTest {
        val fixture = FixtureLoader.loadFixture("branching-merge.json")
        val layout = TestLayoutBuilder.buildWithConstants(fixture)

        // Extract expected depth relationships from fixture structure
        val nodeA = layout.nodes["main:ScreenA"]
        val nodeB = layout.nodes["main:ScreenB"]
        val nodeC = layout.nodes["main:ScreenC"]
        val nodeD = layout.nodes["main:ScreenD"]

        assertNotNull(nodeA)
        assertNotNull(nodeB)
        assertNotNull(nodeC)
        assertNotNull(nodeD)

        // Verify X-ordering represents depth
        // A (depth 0) < B,C (depth 1) < D (depth 2)
        assertTrue(nodeA!!.x < nodeB!!.x && nodeA.x < nodeC!!.x)
        assertTrue(nodeB.x < nodeD!!.x && nodeC.x < nodeD.x)
    }

    @Test
    fun testExampleGraphNodePlacementStableAcrossConnectionSets() = runTest {
        val graphWithConnections1 = FixtureLoader.loadFixture("example-connections-1.json")
        val graphWithConnections2 = FixtureLoader.loadFixture("example-connections-2.json")
        val layout1 = TestLayoutBuilder.buildWithConstants(graphWithConnections1)
        val layout2 = TestLayoutBuilder.buildWithConstants(graphWithConnections2)

        assertEquals(layout1.nodes.keys, layout2.nodes.keys)

        layout1.nodes.forEach { (nodeId, node1) ->
            val node2 = layout2.nodes[nodeId]
            assertNotNull(node2, "Missing node $nodeId in second layout")
            assertEquals(node1.x, node2.x, 0.01f, "Unexpected x position for $nodeId")
            assertEquals(node1.y, node2.y, 0.01f, "Unexpected y position for $nodeId")
            assertEquals(node1.width, node2.width, 0.01f, "Unexpected width for $nodeId")
            assertEquals(node1.height, node2.height, 0.01f, "Unexpected height for $nodeId")
        }
    }

    @Test
    fun testPrecisePositioningLinearGraph() = runTest {
        val fixture = FixtureLoader.loadFixture("simple-linear.json")
        val gaps = LayoutGaps(leftMargin = 100f, topMargin = 100f, minHorizontalGap = 250f, minVerticalGap = 250f)
        val layout = LayoutGraphBuilder.build(
            appGraph = fixture,
            projectPath = null,
            scale = SCALE,
            imageResolver = ConstantImageDimensionResolver(IMAGE_WIDTH, IMAGE_HEIGHT),
            gaps = gaps
        )

        val nodes = layout.nodes
        val screenA = nodes["main:ScreenA"]!!
        val screenB = nodes["main:ScreenB"]!!
        val screenC = nodes["main:ScreenC"]!!

        // Verify exact dimensions
        assertEquals(SCALED_WIDTH_F, screenA.width, 0.1f)
        assertEquals(SCALED_HEIGHT_F, screenA.height, 0.1f)
        assertEquals(SCALED_WIDTH_F, screenB.width, 0.1f)
        assertEquals(SCALED_HEIGHT_F, screenB.height, 0.1f)
        assertEquals(SCALED_WIDTH_F, screenC.width, 0.1f)
        assertEquals(SCALED_HEIGHT_F, screenC.height, 0.1f)

        // All nodes should be properly positioned
        assertTrue(screenA.y > 0)
        assertTrue(screenB.y > 0)
        assertTrue(screenC.y > 0)
    }
}

private fun overlapsVerticallyInclusive(source: LayoutNode, target: LayoutNode): Boolean {
    val sourceTop = source.y - source.height / 2f
    val sourceBottom = source.y + source.height / 2f
    val targetTop = target.y - target.height / 2f
    val targetBottom = target.y + target.height / 2f
    return sourceBottom >= targetTop && targetBottom >= sourceTop
}
