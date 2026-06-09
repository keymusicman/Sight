package com.keymusicman.sight.viewmodel

import kotlinx.serialization.Serializable

@Serializable
data class GraphView(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val nodeIds: Set<String>
)
