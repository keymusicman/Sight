package com.keymusicman.sight

@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Repeatable
annotation class SightTransition(
    val toScreen: String = "",
    val toSubgraph: String = "",
    val fromScreen: String = "",
    val fromSubgraph: String = "",
    val trigger: String = "",
)
