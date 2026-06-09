package io.github.keymusicman.sight

@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.FUNCTION)
@Repeatable
annotation class SightScreen(
    val subgraph: String = "",
    val id: String = "",
    val isRoot: Boolean = false,
)
