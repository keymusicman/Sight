package io.github.keymusicman.sight.viewmodel

import io.github.keymusicman.sight.model.LayoutEdge

object GraphPathFinder {
    fun findPathNodes(fromId: String, toId: String, edges: List<LayoutEdge>): Set<String> {
        val forward = mutableMapOf<String, MutableList<String>>()
        val backward = mutableMapOf<String, MutableList<String>>()
        for (edge in edges) {
            forward.getOrPut(edge.from) { mutableListOf() }.add(edge.to)
            backward.getOrPut(edge.to) { mutableListOf() }.add(edge.from)
        }

        fun bfs(start: String, adj: Map<String, List<String>>): Set<String> {
            val visited = mutableSetOf(start)
            val queue = ArrayDeque<String>()
            queue.add(start)
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                for (next in adj[node] ?: emptyList()) {
                    if (visited.add(next)) queue.add(next)
                }
            }
            return visited
        }

        val forwardReachable = bfs(fromId, forward)
        val backwardReachable = bfs(toId, backward)
        val intersection = forwardReachable.intersect(backwardReachable)
        return intersection.ifEmpty { setOf(fromId, toId) }
    }
}
