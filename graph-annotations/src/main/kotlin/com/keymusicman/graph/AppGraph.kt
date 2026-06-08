package com.keymusicman.graph

/**
 * Marks an object as the graph declaration entry point for this module.
 * The KSP processor only runs for modules that contain this annotation.
 * Place it on an object alongside @AppFlowTransition declarations for cross-module wiring.
 *
 * Example:
 *   @AppFlowGraph
 *   @AppFlowTransition(fromScreen = "login", toSubgraph = "registration", trigger = "register_tap")
 *   object Graph
 */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS)
annotation class AppFlowGraph(
    val name: String = "",
    val entrySubgraph: String = "",
    val dropUnconnected: Boolean = true,
)
