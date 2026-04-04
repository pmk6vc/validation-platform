package com.platform.collector.models

import kotlinx.serialization.Serializable

@Serializable
enum class InputType {
    HTTP,
    UNKNOWN,
}
