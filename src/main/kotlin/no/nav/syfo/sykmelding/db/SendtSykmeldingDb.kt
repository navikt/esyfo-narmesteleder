package no.nav.syfo.sykmelding.db

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.syfo.application.database.DatabaseInterface
import no.nav.syfo.util.logger
import java.sql.Date
import java.sql.ResultSet
import java.time.LocalDate
import java.util.UUID

interface ISykmeldingDb {
    suspend fun insertOrUpdateSykmeldingBatch(entities: List<SendtSykmeldingEntity>)
    suspend fun revokeSykmeldingBatch(sykmeldingIds: List<UUID>, revokedDate: LocalDate): Int
    suspend fun findBySykmeldingId(sykmeldingId: UUID): SendtSykmeldingEntity?
}

class SykmeldingDbException(message: String, cause: Throwable? = null) : Exception(message, cause)

class SykmeldingDb(
    private val database: DatabaseInterface,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : ISykmeldingDb {
    override suspend fun insertOrUpdateSykmeldingBatch(entities: List<SendtSykmeldingEntity>) = withContext(dispatcher) {
        if (entities.isEmpty()) return@withContext

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
                                         tom
                    )
                    VALUES (?, ?, ?, ?,?, ?) 
                    ON CONFLICT (sykmelding_id) DO UPDATE SET
                        fnr = EXCLUDED.fnr,
                        fom = EXCLUDED.fom,
                        tom = EXCLUDED.tom
                    """.trimIndent()
                ).use { preparedStatement ->
                    try {
                        entities.forEach { sykmeldingEntity ->
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
                            preparedStatement.addBatch()
                        }
                        preparedStatement.executeBatch()
                        connection.commit()
                        logger.info("Batch inserted/updated ${entities.size} sykmeldinger")
                    } catch (e: Exception) {
                        logger.error(
                            "Failed to batch insert ${entities.size} sykmeldinger. Rolling back.",
                            e
                        )
                        connection.rollback()
                        throw SykmeldingDbException(
                            "Error batch inserting ${entities.size} sykmeldinger",
                            e
                        )
                    }
                }
        }
    }

    override suspend fun revokeSykmeldingBatch(sykmeldingIds: List<UUID>, revokedDate: LocalDate): Int = withContext(dispatcher) {
        if (sykmeldingIds.isEmpty()) return@withContext 0

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
                        sykmeldingIds.forEach { sykmeldingId ->
                            preparedStatement.setDate(1, revoked)
                            preparedStatement.setObject(2, sykmeldingId)
                            preparedStatement.setDate(3, revoked)
                            preparedStatement.addBatch()
                        }

                        val results = preparedStatement.executeBatch()
                        connection.commit()
                        val totalUpdated = results.sum()
                        logger.info("Batch revoked $totalUpdated of ${sykmeldingIds.size} sykmeldinger")
                        totalUpdated
                    } catch (e: Exception) {
                        logger.error(
                            "Failed to batch revoke ${sykmeldingIds.size} sykmeldinger. Rolling back.",
                            e
                        )
                        connection.rollback()
                        throw SykmeldingDbException(
                            "Error batch revoking ${sykmeldingIds.size} sykmeldinger",
                            e
                        )
                    }
                }
        }
    }

    override suspend fun findBySykmeldingId(sykmeldingId: UUID): SendtSykmeldingEntity? = withContext(dispatcher) {
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
                    if (resultSet.next()) {
                        resultSet.toSendtSykmeldingEntity()
                    } else {
                        null
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
