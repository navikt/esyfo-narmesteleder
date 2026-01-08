package no.nav.syfo.application.database

import java.sql.PreparedStatement
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.util.UUID

/**
 * A simple SQL utility class to help tidy up the DAOs.
 * Allows building SQL WHERE clauses with parameters in a safe manner.
 *
 * Column names are restricted to the [Column] enum. Append to it as needed.
 *
 * Example usage:
 *
 * ```
 * val connection: Connection = // obtain a JDBC connection
 *
 * val preparedStatement = SqlBuilder.buildFilter {
 *      filterParam(Column.ID, someId)
 *      filterParam(Column.BEHOV_STATUS, someStatus)
 *      filterParam(Column.CREATED, someCreatedAfter, ComparisonOperator.GREATER_THAN_OR_EQUAL_TO)
 *
 *      limit = 50
 *      orderBy = OrderBy.CREATED
 *      orderDirection = OrderDirection.DESC
 *
 *      connection.prepareStatement("select * from table ${buildWhereClause()}")
 * }.use { ps ->
 *      ps.executeQuery()
 *    }
 * ```
 * */
class SqlBuilder {
    private constructor()

    private val filters = mutableListOf<Filter>()

    var limit: Int? = null
    var orderBy: Column? = null
    var orderDirection: OrderDirection = OrderDirection.DESC
    var offset: Int? = null

    /**
     * Add a filter parameter with a predefined column name.
     *
     * @param column The column to filter on (from the [Column] enum)
     * @param value The value to filter by (null values are ignored)
     * @param comparisonOperator The comparison operator to use
     */
    fun filterParam(
        column: Column,
        value: Any?,
        comparisonOperator: ComparisonOperator = ComparisonOperator.EQUALS
    ): SqlBuilder {
        if (value == null) return this

        if (value is List<*>) {
            require(comparisonOperator == ComparisonOperator.IN) {
                "Only IN operator is supported for list values"
            }
            require(value.isNotEmpty()) { "List for SQL IN filter cannot be empty" }
            filters.add(Filter(column, value, comparisonOperator))
            return this
        }

        filters.add(Filter(column, value, comparisonOperator))
        return this
    }

    /**
     * Builds the SQL WHERE clause with optional ORDER BY, LIMIT, and OFFSET.
     * Append the result to the `prepareStatement` you provide to the builder function.
     */
    fun buildWhereClause(): String {
        val whereClause = if (filters.isNotEmpty()) {
            "WHERE ${filters.joinToString(" AND ") { it.toSqlFragment() }}"
        } else {
            ""
        }

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

    private fun buildStatement(preparedStatement: PreparedStatement): PreparedStatement {
        var parameterIndex = 1

        filters.forEach { filter ->
            when (val value = filter.value) {
                is List<*> ->
                    value
                        .filterNotNull()
                        .forEach { item ->
                            setPreparedStatementParameter(preparedStatement, parameterIndex++, item)
                        }

                else -> setPreparedStatementParameter(preparedStatement, parameterIndex++, value)
            }
        }

        return preparedStatement
    }

    private fun setPreparedStatementParameter(
        preparedStatement: PreparedStatement,
        parameterIndex: Int,
        value: Any
    ) {
        when (value) {
            is String -> preparedStatement.setString(parameterIndex, value)
            is Boolean -> preparedStatement.setBoolean(parameterIndex, value)
            is Int -> preparedStatement.setInt(parameterIndex, value)
            is Long -> preparedStatement.setLong(parameterIndex, value)
            is Instant -> preparedStatement.setTimestamp(parameterIndex, Timestamp.from(value))
            is Enum<*> -> preparedStatement.setObject(parameterIndex, value, Types.OTHER)
            is UUID, is Timestamp -> preparedStatement.setObject(parameterIndex, value)

            else -> throw IllegalArgumentException("Unsupported parameter type: ${value.javaClass.simpleName}")
        }
    }

    enum class Column(val columnName: String) {
        ID("id"),
        CREATED("created"),
        UPDATED("updated"),
        BEHOV_STATUS("behov_status"),
        SYKEMELDT_FNR("sykemeldt_fnr"),
        NARMESTE_LEDER_FNR("narmeste_leder_fnr"),
        ORGNUMMER("orgnummer"),
        HOVEDENHET_ORGNUMMER("hovedenhet_orgnummer"),
        BEHOV_REASON("behov_reason"),
        AVBRUTT_NARMESTELEDER_ID("avbrutt_narmesteleder_id"),
        ;

        override fun toString(): String = columnName
    }

    enum class OrderDirection {
        ASC,
        DESC
    }

    private data class Filter(
        val column: Column,
        val value: Any,
        val operator: ComparisonOperator,
    ) {
        fun toSqlFragment(): String = when (operator) {
            ComparisonOperator.IN -> {
                val list = value as List<*>
                val placeholders = list.joinToString(", ") { "?" }
                "${column.columnName} ${operator.symbol} ($placeholders)"
            }

            else -> "${column.columnName} ${operator.symbol} ?"
        }
    }

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
        /**
         * Builds a PreparedStatement with the provided filter parameters.
         *
         * @param block A lambda with receiver that configures the SqlFilter and returns a PreparedStatement
         * @return The built PreparedStatement with parameters set
         */
        fun filterBuilder(block: SqlBuilder.() -> PreparedStatement): PreparedStatement = SqlBuilder().run {
            // To keep the static analysis of the SQL, we let the user of this builder provide the PreparedStatement
            buildStatement(block())
        }
    }
}
