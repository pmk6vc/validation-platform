package com.platform.models

import kotlinx.serialization.Serializable

/**
 * A paginated response using cursor-based pagination.
 *
 * @param items The items in this page
 * @param nextCursor Cursor to fetch the next page, null if no more items
 */
@Serializable
data class Page<T>(
    val items: List<T>,
    val nextCursor: String?,
)
