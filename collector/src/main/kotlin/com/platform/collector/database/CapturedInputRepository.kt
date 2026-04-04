package com.platform.collector.database

import com.platform.collector.models.CapturedInput
import com.platform.collector.models.InputType
import com.platform.models.Page
import com.platform.models.decodeCursor
import com.platform.models.encodeCursor
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.util.UUID

object CapturedInputRepository {
    const val DEFAULT_PAGE_SIZE = 20
    const val MAX_PAGE_SIZE = 100

    suspend fun create(input: CapturedInput): CapturedInput =
        newSuspendedTransaction {
            CapturedInputs.insert {
                it[id] = UUID.fromString(input.id)
                it[serviceId] = UUID.fromString(input.serviceId)
                it[inputType] = input.inputType
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

    suspend fun createBatch(inputs: List<CapturedInput>): List<CapturedInput> =
        newSuspendedTransaction {
            CapturedInputs.batchInsert(inputs) { input ->
                this[CapturedInputs.id] = UUID.fromString(input.id)
                this[CapturedInputs.serviceId] = UUID.fromString(input.serviceId)
                this[CapturedInputs.inputType] = input.inputType
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

    suspend fun find(
        serviceId: String? = null,
        inputType: InputType? = null,
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
            cursor?.let {
                val (cursorTimestamp, cursorId) = decodeCursor(it)
                conditions.add(
                    (CapturedInputs.capturedAt greater cursorTimestamp) or
                        ((CapturedInputs.capturedAt eq cursorTimestamp) and (CapturedInputs.id greater cursorId)),
                )
            }

            val query =
                if (conditions.isEmpty()) {
                    CapturedInputs.selectAll()
                } else {
                    CapturedInputs.selectAll().where { conditions.reduce { acc, op -> acc and op } }
                }

            val results =
                query
                    .orderBy(CapturedInputs.capturedAt to SortOrder.ASC, CapturedInputs.id to SortOrder.ASC)
                    .limit(pageLimit + 1)
                    .map { it.toCapturedInput() }

            val hasMore = results.size > pageLimit
            val items = if (hasMore) results.dropLast(1) else results
            val nextCursor =
                if (hasMore) {
                    encodeCursor(items.last().capturedAt, items.last().id)
                } else {
                    null
                }

            Page(items = items, nextCursor = nextCursor)
        }

    suspend fun countByService(serviceId: String): Long =
        newSuspendedTransaction {
            CapturedInputs
                .selectAll()
                .where { CapturedInputs.serviceId eq UUID.fromString(serviceId) }
                .count()
        }

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
