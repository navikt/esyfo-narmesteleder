package no.nav.syfo.sykmelding.db

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.syfo.application.database.DatabaseInterface
import no.nav.syfo.util.logger
import java.sql.Connection
import java.sql.Date
import java.sql.ResultSet
import java.time.LocalDate
import java.util.UUID
import kotlin.collections.forEach
import kotlin.use

interface ISykmeldingDb {
    suspend fun findBySykmeldingId(sykmeldingId: UUID): SendtSykmeldingEntity?
    suspend fun transaction(block: suspend ISykmeldingTransaction.() -> Unit)
    suspend fun findSykmeldingIdsByFnrAndOrgnr(map: List<Pair<String, String>>): List<UUID>
}

interface ISykmeldingTransaction {
    fun insertOrUpdateSykmeldingBatch(entities: List<SendtSykmeldingEntity>)
    fun revokeSykmeldingBatch(sykmeldingIds: List<UUID>, revokedDate: LocalDate): Int
    fun deleteAll(ids: List<UUID>)
}

class SykmeldingDbException(message: String, cause: Throwable? = null) : Exception(message, cause)

class SykmeldingDb(
    private val database: DatabaseInterface,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : ISykmeldingDb {

    private class TransactionImpl(private val connection: Connection) : ISykmeldingTransaction {
        override fun insertOrUpdateSykmeldingBatch(entities: List<SendtSykmeldingEntity>) {
            if (entities.isEmpty()) return

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
                    VALUES (?, ?, ?, ?, ?, ?) 
                    ON CONFLICT (sykmelding_id) DO UPDATE SET
                        fnr = EXCLUDED.fnr,
                        fom = EXCLUDED.fom,
                        tom = EXCLUDED.tom
                    """.trimIndent()
                ).use { preparedStatement ->
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
                    logger.info("Batch inserted/updated ${entities.size} sykmeldinger")
                }
        }

        override fun revokeSykmeldingBatch(sykmeldingIds: List<UUID>, revokedDate: LocalDate): Int {
            if (sykmeldingIds.isEmpty()) return 0

            return connection
                .prepareStatement(
                    """
                    UPDATE sendt_sykmelding 
                    SET revoked_date = ?
                    WHERE sykmelding_id = ? 
                    """.trimIndent()
                ).use { preparedStatement ->
                    val revoked = Date.valueOf(revokedDate)
                    sykmeldingIds.forEach { sykmeldingId ->
                        preparedStatement.setDate(1, revoked)
                        preparedStatement.setObject(2, sykmeldingId)
                        preparedStatement.addBatch()
                    }

                    val results = preparedStatement.executeBatch()
                    val totalUpdated = results.sum()
                    logger.info("Batch revoked $totalUpdated of ${sykmeldingIds.size} sykmeldinger")
                    totalUpdated
                }
        }

        override fun deleteAll(ids: List<UUID>) {
            if (ids.isEmpty()) return

            connection
                .prepareStatement(
                    """
                    DELETE FROM sendt_sykmelding 
                    WHERE sykmelding_id = ?
                    """.trimIndent()
                ).use { preparedStatement ->
                    ids.forEach { id ->
                        preparedStatement.setObject(1, id)
                        preparedStatement.addBatch()
                    }
                    val results = preparedStatement.executeBatch()
                    val totalDeleted = results.sum()
                    logger.info("Batch deleted $totalDeleted of ${ids.size} sykmeldinger")
                }
        }
    }

    override suspend fun transaction(block: suspend ISykmeldingTransaction.() -> Unit) = withContext(dispatcher) {
        database.connection.use { connection ->
            try {
                connection.autoCommit = false
                TransactionImpl(connection).block()
                connection.commit()
            } catch (e: Exception) {
                logger.info("Transaction failed. Rolling back", e)
                connection.rollback()
                throw SykmeldingDbException("Transaction failed", e)
            }
        }
    }

    override suspend fun findSykmeldingIdsByFnrAndOrgnr(map: List<Pair<String, String>>): List<UUID> {
        if (map.isEmpty()) return emptyList()

        database.connection.use { connection ->
            try {
                connection.prepareStatement(
                    """
                    SELECT sykmelding_id FROM sendt_sykmelding
                    WHERE fnr = ? AND orgnummer = ? 
                    """.trimIndent()
                ).use { preparedStatement ->
                    map.forEach { (fnr, orgnummer) ->
                        logger.info("Finding sykmeldingId for fnr: ${fnr.substring(0,4)} and orgnummer: $orgnummer")
                        preparedStatement.setString(1, fnr)
                        preparedStatement.setString(2, orgnummer)
                        preparedStatement.addBatch()
                    }
                    val resultSet = preparedStatement.executeQuery()
                    return buildList {
                        while (resultSet.next()) {
                            add(resultSet.getObject("sykmelding_id") as UUID)
                        }
                    }
                }
            } catch (_: Exception) {
                logger.error("Error preparing statement for findSykmeldingIdsByFnrAndOrgnr")
                throw SykmeldingDbException("Error preparing statement for findSykmeldingIdsByFnrAndOrgnr")
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
