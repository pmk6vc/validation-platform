package com.platform.models

import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

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

fun encodeCursor(
    timestamp: Instant,
    id: String,
): String = "${timestamp.epochSecond}.${timestamp.nano}|$id"

fun decodeCursor(cursor: String): Pair<Instant, UUID> {
    val parts = cursor.split("|", limit = 2)
    val timeParts = parts[0].split(".", limit = 2)
    val timestamp = Instant.ofEpochSecond(timeParts[0].toLong(), timeParts[1].toLong())
    val id = UUID.fromString(parts[1])
    return timestamp to id
}
