package com.keymusicman.appflower.recents

import kotlinx.serialization.Serializable

@Serializable
data class RecentGraph(
    val path: String,
    val displayName: String,
    val lastOpened: Long
)
