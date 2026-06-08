package com.keymusicman.graph

@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.FUNCTION)
@Repeatable
annotation class AppFlowScreen(
    val subgraph: String = "",
    val id: String = "",
    val isRoot: Boolean = false,
)
