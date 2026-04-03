package com.platform.database

import com.platform.models.Page
import com.platform.models.capture.CapturedInput
import com.platform.models.capture.InputType
import com.platform.models.capture.TrafficClassification
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.util.UUID

/**
 * Repository for CapturedInput CRUD operations.
 */
object CapturedInputRepository {
    const val DEFAULT_PAGE_SIZE = 20
    const val MAX_PAGE_SIZE = 100

    suspend fun create(input: CapturedInput): CapturedInput =
        newSuspendedTransaction {
            CapturedInputs.insert {
                it[id] = UUID.fromString(input.id)
                it[serviceId] = UUID.fromString(input.serviceId)
                it[inputType] = input.inputType
                it[classification] = input.classification
                it[method] = input.method
                it[url] = input.url
                it[requestHeaders] = input.requestHeaders
                it[requestBody] = input.requestBody
                it[responseStatus] = input.responseStatus
                it[responseHeaders] = input.responseHeaders
                it[responseBody] = input.responseBody
                it[latencyMs] = input.latencyMs
                it[sourceIp] = input.sourceIp
                it[destinationIp] = input.destinationIp
                it[capturedAt] = input.capturedAt
            }
            input
        }

    /**
     * Bulk insert for efficiency when storing many captured inputs at once.
     *
     * @param inputs List of inputs to store
     * @return The same list of inputs (unchanged)
     */
    suspend fun createBatch(inputs: List<CapturedInput>): List<CapturedInput> =
        newSuspendedTransaction {
            CapturedInputs.batchInsert(inputs) { input ->
                this[CapturedInputs.id] = UUID.fromString(input.id)
                this[CapturedInputs.serviceId] = UUID.fromString(input.serviceId)
                this[CapturedInputs.inputType] = input.inputType
                this[CapturedInputs.classification] = input.classification
                this[CapturedInputs.method] = input.method
                this[CapturedInputs.url] = input.url
                this[CapturedInputs.requestHeaders] = input.requestHeaders
                this[CapturedInputs.requestBody] = input.requestBody
                this[CapturedInputs.responseStatus] = input.responseStatus
                this[CapturedInputs.responseHeaders] = input.responseHeaders
                this[CapturedInputs.responseBody] = input.responseBody
                this[CapturedInputs.latencyMs] = input.latencyMs
                this[CapturedInputs.sourceIp] = input.sourceIp
                this[CapturedInputs.destinationIp] = input.destinationIp
                this[CapturedInputs.capturedAt] = input.capturedAt
            }
            inputs
        }

    suspend fun findById(id: String): CapturedInput? =
        newSuspendedTransaction {
            CapturedInputs
                .selectAll()
                .where { CapturedInputs.id eq UUID.fromString(id) }
                .map { it.toCapturedInput() }
                .singleOrNull()
        }

    /**
     * Find captured inputs with optional filters and cursor-based pagination.
     *
     * @param serviceId Filter by service
     * @param inputType Filter by protocol type
     * @param classification Filter by read/write classification
     * @param limit Maximum number of items to return (default 20, max 100)
     * @param cursor Cursor from previous page's nextCursor, null for first page
     * @return Page containing captured inputs and nextCursor for pagination
     */
    suspend fun find(
        serviceId: String? = null,
        inputType: InputType? = null,
        classification: TrafficClassification? = null,
        limit: Int = DEFAULT_PAGE_SIZE,
        cursor: String? = null,
    ): Page<CapturedInput> =
        newSuspendedTransaction {
            val pageLimit = limit.coerceIn(1, MAX_PAGE_SIZE)
            val conditions = mutableListOf<Op<Boolean>>()

            serviceId?.let {
                conditions.add(CapturedInputs.serviceId eq UUID.fromString(it))
            }
            inputType?.let {
                conditions.add(CapturedInputs.inputType eq it)
            }
            classification?.let {
                conditions.add(CapturedInputs.classification eq it)
            }
            cursor?.let {
                conditions.add(CapturedInputs.id greater UUID.fromString(it))
            }

            val query =
                if (conditions.isEmpty()) {
                    CapturedInputs.selectAll()
                } else {
                    CapturedInputs.selectAll().where { conditions.reduce { acc, op -> acc and op } }
                }

            // Fetch one extra to determine if there's a next page
            val results =
                query
                    .orderBy(CapturedInputs.id, SortOrder.ASC)
                    .limit(pageLimit + 1)
                    .map { it.toCapturedInput() }

            val hasMore = results.size > pageLimit
            val items = if (hasMore) results.dropLast(1) else results
            val nextCursor = if (hasMore) items.last().id else null

            Page(items = items, nextCursor = nextCursor)
        }

    suspend fun countByService(serviceId: String): Long =
        newSuspendedTransaction {
            CapturedInputs
                .selectAll()
                .where { CapturedInputs.serviceId eq UUID.fromString(serviceId) }
                .count()
        }

    /**
     * Delete all captured inputs for a service.
     *
     * @param serviceId Service whose captured inputs should be removed
     * @return Number of rows deleted
     */
    suspend fun deleteByService(serviceId: String): Long =
        newSuspendedTransaction {
            CapturedInputs
                .deleteWhere { CapturedInputs.serviceId eq UUID.fromString(serviceId) }
                .toLong()
        }

    private fun ResultRow.toCapturedInput(): CapturedInput =
        CapturedInput(
            id = this[CapturedInputs.id].toString(),
            serviceId = this[CapturedInputs.serviceId].toString(),
            inputType = this[CapturedInputs.inputType],
            classification = this[CapturedInputs.classification],
            method = this[CapturedInputs.method],
            url = this[CapturedInputs.url],
            requestHeaders = this[CapturedInputs.requestHeaders],
            requestBody = this[CapturedInputs.requestBody],
            responseStatus = this[CapturedInputs.responseStatus],
            responseHeaders = this[CapturedInputs.responseHeaders],
            responseBody = this[CapturedInputs.responseBody],
            latencyMs = this[CapturedInputs.latencyMs],
            sourceIp = this[CapturedInputs.sourceIp],
            destinationIp = this[CapturedInputs.destinationIp],
            capturedAt = this[CapturedInputs.capturedAt],
        )
}
