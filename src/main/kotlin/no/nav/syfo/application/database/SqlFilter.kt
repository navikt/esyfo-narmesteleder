package no.nav.syfo.application.database

import java.sql.PreparedStatement
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.util.UUID
import org.w3c.dom.DocumentType

/**
 * Example usage:
 *
 * ```
 * val connection: Connection = // obtain a JDBC connection
 *
 * val preparedStatement = SqlFilter { builder ->
 *      filterParam("id", someId)
 *      filterParam("status", someStatus)
 *      filterParam("created", someCreatedAfter, ComparisonOperator.GREATER_THAN_OR_EQUAL_TO)
 *
 *      limit = 50
 *      orderBy = Page.OrderBy.CREATED
 *      orderDirection = Page.OrderDirection.DESC
 *
 *      connection.prepareStatement("select * from table ${buildFilterString()}")
 * }
 * preparedStatement.use { it.executeQuery() }
 * ```
 * */
class SqlFilter {
    private constructor()

    private val filters = mutableListOf<Filter>()

    var limit: Int? = null
    var orderBy: OrderBy? = null
    var orderDirection: OrderDirection = OrderDirection.DESC
    var offset: Int? = null

    fun filterParam(
        name: String,
        value: Any?,
        comparisonOperator: ComparisonOperator = ComparisonOperator.EQUALS
    ): SqlFilter {
        if (value == null) return this
        if (value is List<*>) {
            require(comparisonOperator == ComparisonOperator.IN) {
                "Only IN operator is supported for list values"
            }
            require(value.isNotEmpty()) { "List for SQL IN filter cannot be empty" }
            filters.add(Filter(name, value, comparisonOperator.symbol))
            return this
        }

        filters.add(Filter(name, value, comparisonOperator.symbol))
        return this
    }

    fun buildFilterString(): String {
        val whereClause = if (filters.isNotEmpty()) {
            "WHERE ${filters.joinToString(" AND ") { "${it.name} ${it.operator} ?" }}"
        } else ""
        val orderClause = orderBy?.let { "ORDER BY ${it.columnName} ${orderDirection.name}" } ?: ""
        val limitClause = limit?.let {
            require(it > 0) { "Limit must be at least 1" }
            "LIMIT $it"
        } ?: ""
        val offsetClause = offset?.let {
            require(it >= 0) { "Offset must be at least 0" }
            "OFFSET $it"
        } ?: ""

        return listOf(whereClause, orderClause, limitClause, offsetClause)
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }

    // Decided to let the user of the class do the final composition of the SQL, to keep the static analysis of the SQL
    private fun buildStatement(preparedStatement: PreparedStatement): PreparedStatement {
        filters.forEachIndexed { idx, filter ->
            val parameterIndex = idx + 1
            when (val value = filter.value) {
                is String -> preparedStatement.setString(parameterIndex, value)
                is Boolean -> preparedStatement.setBoolean(parameterIndex, value)
                is UUID -> preparedStatement.setObject(parameterIndex, value)
                is DocumentType -> preparedStatement.setObject(parameterIndex, value, Types.OTHER)
                is Enum<*> -> preparedStatement.setObject(parameterIndex, value)
                is Timestamp -> preparedStatement.setObject(parameterIndex, value)
                is Instant -> preparedStatement.setTimestamp(parameterIndex, Timestamp.from(value))
                is List<*> -> {
                    val sqlTypeName = inferArraySqlTypeName(value)
                    val sqlArray = preparedStatement.connection.createArrayOf(sqlTypeName, value.toTypedArray())
                    preparedStatement.setArray(parameterIndex, sqlArray)
                    preparedStatement.setArray(parameterIndex, sqlArray)
                }

                else -> throw IllegalArgumentException("Unsupported parameter type: ${value.javaClass.simpleName}")
            }
        }
        return preparedStatement
    }

    private fun inferArraySqlTypeName(values: List<*>): String {
        val firstNonNull = values.firstOrNull { it != null } ?: return "VARCHAR"
        return when (firstNonNull) {
            is UUID -> "UUID"
            is String -> "VARCHAR"
            is Int -> "INTEGER"
            is Long -> "BIGINT"
            is Enum<*> -> "VARCHAR"
            else -> "VARCHAR"
        }
    }

    enum class OrderBy(val columnName: String) {
        CREATED("created"),
        UPDATED("updated"),
    }

    enum class OrderDirection {
        ASC,
        DESC
    }

    private data class Filter(val name: String, val value: Any, val operator: String)
    enum class ComparisonOperator(val symbol: String) {
        EQUALS("="),
        IN("IN"),
        NOT_EQUALS("!="),
        GREATER_THAN(">"),
        LESS_THAN("<"),
        GREATER_THAN_OR_EQUAL_TO(">="),
        LESS_THAN_OR_EQUAL_TO("<=")
    }

    companion object {
        fun build(block: SqlFilter.() -> PreparedStatement): PreparedStatement =
            SqlFilter().run {
                buildStatement(block())
            }

    }

    data class Page<out T>(
        val items: List<T>,
        val page: Int,
    )
}
