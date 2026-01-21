package no.nav.syfo.sykmelding.db

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.syfo.application.database.DatabaseInterface
import no.nav.syfo.util.logger
import java.sql.Date
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.LocalDate
import java.util.UUID

interface ISykmeldingDb {
    suspend fun insertSykmelding(sykmeldingEntity: SendtSykmeldingEntity)
    suspend fun revokeSykmelding(sykmeldingId: UUID, revokedDate: LocalDate): Int
    suspend fun findBySykmeldingId(sykmeldingId: UUID): List<SendtSykmeldingEntity>
}

class SykmeldingDbException(message: String, cause: Throwable? = null) : Exception(message, cause)

class SykmeldingDb(
    private val database: DatabaseInterface,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : ISykmeldingDb {
    override suspend fun insertSykmelding(sykmeldingEntity: SendtSykmeldingEntity) = withContext(dispatcher) {
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

    override suspend fun revokeSykmelding(sykmeldingId: UUID, revokedDate: LocalDate): Int = withContext(dispatcher) {
        database.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                UPDATE sendt_sykmelding 
                SET revoked_date = ?
                WHERE sykmelding_id = ? and tom <= ?
                    """.trimIndent()
                ).use { preparedStatement ->
                    try {
                        val revoked = Date.valueOf(revokedDate)
                        preparedStatement.setDate(1, revoked)
                        preparedStatement.setObject(2, sykmeldingId)
                        preparedStatement.setDate(3, revoked)

                        preparedStatement.executeUpdate().also {
                            connection.commit()
                        }
                    } catch (e: Exception) {
                        logger.error(
                            "Failed to delete entries with sykmeldingId: $sykmeldingId. Rolling back.",
                            e
                        )
                        connection.rollback()
                        throw SykmeldingDbException(
                            "Error revoking sendt_sykmelding with sykmeldingId: $sykmeldingId",
                            e
                        )
                    }
                }
        }
    }

    override suspend fun findBySykmeldingId(sykmeldingId: UUID): List<SendtSykmeldingEntity> = withContext(dispatcher) {
        database.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                SELECT * 
                FROM sendt_sykmelding
                WHERE sykmelding_id = ?
                    """.trimIndent()
                ).use { preparedStatement ->
                    preparedStatement.setObject(1, sykmeldingId)
                    val resultSet = preparedStatement.executeQuery()
                    buildList {
                        while (resultSet.next()) {
                            add(resultSet.toSendtSykmeldingEntity())
                        }
                    }
                }
        }
    }

    companion object {
        private val logger = logger()
    }
}

fun ResultSet.toSendtSykmeldingEntity(): SendtSykmeldingEntity = SendtSykmeldingEntity(
    id = getLong("id"),
    sykmeldingId = getObject("sykmelding_id") as UUID?
        ?: throw IllegalArgumentException("Could not get sykmeldingId"),
    fnr = getString("fnr"),
    orgnummer = getString("orgnummer"),
    fom = getDate("fom").toLocalDate(),
    tom = getDate("tom").toLocalDate(),
    revokedDate = getDate("revoked_date")?.toLocalDate(),
    syketilfelleStartDato = getDate("syketilfelle_startdato")?.toLocalDate(),
    created = getTimestamp("created").toInstant(),
    updated = getTimestamp("updated").toInstant(),
)
