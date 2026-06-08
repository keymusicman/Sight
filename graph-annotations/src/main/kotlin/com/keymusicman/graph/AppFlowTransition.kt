package com.keymusicman.graph

@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Repeatable
annotation class AppFlowTransition(
    val toScreen: String = "",
    val toSubgraph: String = "",
    val fromScreen: String = "",
    val fromSubgraph: String = "",
    val trigger: String = "",
)
