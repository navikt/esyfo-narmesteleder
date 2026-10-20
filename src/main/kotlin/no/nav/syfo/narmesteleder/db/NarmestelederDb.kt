package no.nav.syfo.narmesteleder.db

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.syfo.application.database.DatabaseInterface
import no.nav.syfo.logging.applicationEvent
import no.nav.syfo.logging.logEvent
import no.nav.syfo.narmestelederbehov.domain.BehovStatus
import no.nav.syfo.util.logger
import org.slf4j.event.Level
import java.sql.Timestamp
import java.util.UUID

private data class QueryLimitDetails(val requestedLimit: Int, val usedLimit: Int)

private val queryLimitAdjusted = applicationEvent<QueryLimitDetails>(
    name = "query_limit_adjusted",
    level = Level.INFO,
    message = "Requested page size was adjusted to the supported range",
    fields = mapOf(
        "requested_limit" to { it.requestedLimit },
        "used_limit" to { it.usedLimit },
    ),
)

interface NarmestelederDb {
    suspend fun updateNlBehov(nlBehov: NarmestelederBehovEntity)

    /** Returns false when the behov is no longer BEHOV_CREATED, leaving the row untouched. */
    suspend fun markDialogCreated(
        id: UUID,
        dialogId: UUID,
        fornavn: String?,
        mellomnavn: String?,
        etternavn: String?,
    ): Boolean

    suspend fun getNlBehovByStatus(status: BehovStatus, limit: Int = 100) = getNlBehovByStatus(listOf(status), limit)
    suspend fun getNlBehovByStatus(status: List<BehovStatus>, limit: Int = 100): List<NarmestelederBehovEntity>

    suspend fun getNlBehovForExpireInDialogporten(limit: Int = 100, status: List<BehovStatus>): List<NarmestelederBehovEntity>
    suspend fun getNlBehovForExpireInDialogporten(limit: Int = 100, status: BehovStatus) = getNlBehovForExpireInDialogporten(limit, listOf(status))
}

class PostgresNarmestelederDb(
    private val database: DatabaseInterface,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : NarmestelederDb {
    override suspend fun updateNlBehov(nlBehov: NarmestelederBehovEntity): Unit = withContext(dispatcher) {
        database.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    UPDATE nl_behov
                    SET orgnummer            = ?,
                        hovedenhet_orgnummer = ?,
                        sykemeldt_fnr        = ?,
                        narmeste_leder_fnr   = ?,
                        behov_status         = ?,
                        dialog_id            = ?,
                        fornavn              = ?,
                        mellomnavn           = ?,
                        etternavn            = ?,
                        dialog_delete_performed = ?,
                        expired_in_dialogporten = ?
                        
                        WHERE id = ?;
                    """.trimIndent()
                ).use { preparedStatement ->
                    with(nlBehov) {
                        preparedStatement.setString(1, orgnummer)
                        preparedStatement.setString(2, hovedenhetOrgnummer)
                        preparedStatement.setString(3, sykmeldtFnr)
                        preparedStatement.setString(4, narmestelederFnr)
                        preparedStatement.setObject(5, behovStatus, java.sql.Types.OTHER)
                        preparedStatement.setObject(6, dialogId)
                        preparedStatement.setString(7, fornavn)
                        preparedStatement.setString(8, mellomnavn)
                        preparedStatement.setString(9, etternavn)
                        dialogDeletePerformed?.let {
                            preparedStatement.setTimestamp(10, Timestamp.from(dialogDeletePerformed))
                        } ?: preparedStatement.setObject(10, null)
                        expiredInDialogporten?.let {
                            preparedStatement.setTimestamp(11, Timestamp.from(expiredInDialogporten))
                        } ?: preparedStatement.setObject(11, null)
                        preparedStatement.setObject(12, id)
                    }
                    preparedStatement.executeUpdate()
                }.also {
                    connection.commit()
                }
        }
    }

    override suspend fun markDialogCreated(
        id: UUID,
        dialogId: UUID,
        fornavn: String?,
        mellomnavn: String?,
        etternavn: String?,
    ): Boolean = withContext(dispatcher) {
        database.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    UPDATE nl_behov
                    SET behov_status = ?,
                        dialog_id    = ?,
                        fornavn      = ?,
                        mellomnavn   = ?,
                        etternavn    = ?
                    WHERE id = ?
                      AND behov_status = ?;
                    """.trimIndent()
                ).use { preparedStatement ->
                    preparedStatement.setObject(1, BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION, java.sql.Types.OTHER)
                    preparedStatement.setObject(2, dialogId)
                    preparedStatement.setString(3, fornavn)
                    preparedStatement.setString(4, mellomnavn)
                    preparedStatement.setString(5, etternavn)
                    preparedStatement.setObject(6, id)
                    preparedStatement.setObject(7, BehovStatus.BEHOV_CREATED, java.sql.Types.OTHER)
                    preparedStatement.executeUpdate() == 1
                }.also {
                    connection.commit()
                }
        }
    }

    override suspend fun getNlBehovByStatus(status: BehovStatus, limit: Int): List<NarmestelederBehovEntity> = withContext(dispatcher) {
        return@withContext database.connection.use { connection ->
            connection
                // Add AND created < NOW() - INTERVAL '1 minute' in where clause if we add something that triggers sending immediately after insert
                .prepareStatement(
                    """
                        SELECT *
                        FROM nl_behov
                        WHERE behov_status = ?
                        AND created < NOW() - INTERVAL '10 second'
                        ORDER BY created
                        LIMIT ?
                    """.trimIndent()
                ).use { preparedStatement ->
                    preparedStatement.setObject(1, status, java.sql.Types.OTHER)
                    preparedStatement.setInt(2, limit)
                    val resultSet = preparedStatement.executeQuery()
                    val nlBehov = mutableListOf<NarmestelederBehovEntity>()
                    while (resultSet.next()) {
                        nlBehov.add(resultSet.toNarmestelederBehovEntity())
                    }
                    nlBehov
                }
        }
    }

    override suspend fun getNlBehovByStatus(
        status: List<BehovStatus>,
        limit: Int
    ): List<NarmestelederBehovEntity> = withContext(dispatcher) {
        if (status.isEmpty()) return@withContext emptyList()

        val placeholders = status.joinToString(", ") { "?" }

        return@withContext database.connection.use { connection ->
            connection
                // Add AND created < NOW() - INTERVAL '1 minute' in where clause if we add something that triggers sending immediately after insert
                .prepareStatement(
                    """
                        SELECT *
                        FROM nl_behov
                        WHERE behov_status IN ($placeholders)
                        AND created < NOW() - INTERVAL '10 second'
                        ORDER BY created
                        LIMIT ?
                    """.trimIndent()
                ).use { preparedStatement ->
                    var idx = 0
                    status.forEach { s ->
                        preparedStatement.setObject(++idx, s, java.sql.Types.OTHER)
                    }
                    preparedStatement.setInt(++idx, limit)
                    val resultSet = preparedStatement.executeQuery()
                    val nlBehov = mutableListOf<NarmestelederBehovEntity>()
                    while (resultSet.next()) {
                        nlBehov.add(resultSet.toNarmestelederBehovEntity())
                    }
                    nlBehov
                }
        }
    }

    override suspend fun getNlBehovForExpireInDialogporten(
        limit: Int,
        status: List<BehovStatus>,
    ): List<NarmestelederBehovEntity> = withContext(dispatcher) {
        if (status.isEmpty()) error("At least one BehovStatus must be set")

        val placeholders = status.joinToString(", ") { "?" }
        return@withContext database.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                        SELECT *
                        FROM nl_behov
                        WHERE expired_in_dialogporten IS NULL AND dialog_id IS NOT NULL 
                        AND behov_status IN ($placeholders)
                        ORDER BY created
                        LIMIT ?
                    """.trimIndent()
                ).use { preparedStatement ->
                    var idx = 0
                    status.forEach { s ->
                        preparedStatement.setObject(++idx, s, java.sql.Types.OTHER)
                    }
                    limit.coerceIn(1, MAX_LIMIT).also {
                        if (it != limit) {
                            logger().logEvent(queryLimitAdjusted, QueryLimitDetails(limit, it))
                        }
                        preparedStatement.setInt(++idx, it)
                    }
                    val resultSet = preparedStatement.executeQuery()
                    buildList {
                        while (resultSet.next()) {
                            add(resultSet.toNarmestelederBehovEntity())
                        }
                    }
                }
        }
    }
    companion object {
        const val MAX_LIMIT = 500
    }
}
