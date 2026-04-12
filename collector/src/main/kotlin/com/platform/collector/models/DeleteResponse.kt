package com.platform.collector.models

import kotlinx.serialization.Serializable

@Serializable
data class DeleteResponse(
    val deleted: Long,
)
