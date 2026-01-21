package no.nav.syfo.sykmelding.db

import java.sql.Date
import java.sql.Timestamp
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.syfo.application.database.DatabaseInterface
import no.nav.syfo.util.logger

interface ISykmeldingDb {
    suspend fun insertSykmelding(sykmeldingEntity: SykmeldingEntity)
    suspend fun deleteSykmelding(sykmeldingId: UUID): Int
}

class SykmeldingDbException(message: String, cause: Throwable? = null) : Exception(message, cause)

class SykmeldingDb(
    private val database: DatabaseInterface,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : ISykmeldingDb {
    override suspend fun insertSykmelding(sykmeldingEntity: SykmeldingEntity) =
        withContext(dispatcher) {
            database.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                    INSERT INTO sendt_sykmelding(
                                         orgnummer,
                                         sykmelding_id,
                                         fnr,
                                         syketilfelle_startdato,
                                         fom,
                                         tom,
                                        updated
                    )
                    VALUES (?, ?, ?, ?,?, ?, ?)
                    """.trimIndent()
                    ).use { preparedStatement ->
                        var idx = 0
                        preparedStatement.setString(++idx, sykmeldingEntity.orgnummer)
                        preparedStatement.setObject(++idx, sykmeldingEntity.sykmeldingId)
                        preparedStatement.setString(++idx, sykmeldingEntity.fnr)
                        preparedStatement.setDate(
                            ++idx,
                            sykmeldingEntity.syketilfelleStartDato?.let { Date.valueOf(it) }
                        )
                        preparedStatement.setDate(++idx, Date.valueOf(sykmeldingEntity.fom))
                        preparedStatement.setDate(++idx, Date.valueOf(sykmeldingEntity.tom))
                        preparedStatement.setTimestamp(++idx, Timestamp.from(sykmeldingEntity.updated))
                        preparedStatement.execute()

                        connection.commit()
                    }
            }
        }

    override suspend fun deleteSykmelding(sykmeldingId: UUID): Int = withContext(dispatcher) {
        database.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                DELETE FROM sendt_sykmelding 
                WHERE sykmelding_id = ?
                """.trimIndent()
                ).use { preparedStatement ->
                    try {
                        preparedStatement.setObject(1, sykmeldingId)
                        val deletedRows = preparedStatement.executeUpdate()
                        connection.commit()
                        return@use deletedRows
                    } catch (e: Exception) {
                        logger.error("Failed to delete entries with sykmeldingId: $sykmeldingId. Rolling back.", e)
                        connection.rollback()
                        throw SykmeldingDbException(
                            "Error deleting sendt_sykmelding with sykmeldingId: $sykmeldingId",
                            e
                        )
                    }
                }
        }
    }

    companion object {
        private val logger = logger()
    }
}
