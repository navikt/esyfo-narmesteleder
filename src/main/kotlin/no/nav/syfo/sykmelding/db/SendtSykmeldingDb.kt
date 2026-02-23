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

interface ISykmeldingDb {
    suspend fun findBySykmeldingId(sykmeldingId: UUID): SendtSykmeldingEntity?
    suspend fun transaction(block: suspend ISykmeldingTransaction.() -> Unit)
}

interface ISykmeldingTransaction {
    fun insertSykmeldingBatch(entities: List<SendtSykmeldingEntity>): Int
    fun revokeSykmeldingBatch(sykmeldingIds: List<UUID>, revokedDate: LocalDate): Int
    fun deleteAllByFnrAndOrgnr(toDelete: List<Pair<String, String>>): Int
}

class SykmeldingDbException(message: String, cause: Throwable? = null) : Exception(message, cause)

class SykmeldingDb(
    private val database: DatabaseInterface,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : ISykmeldingDb {

    private class TransactionImpl(private val connection: Connection) : ISykmeldingTransaction {
        override fun insertSykmeldingBatch(entities: List<SendtSykmeldingEntity>): Int {
            if (entities.isEmpty()) return 0

            return connection
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
                    preparedStatement.executeBatch().sum()
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
                    totalUpdated
                }
        }

        override fun deleteAllByFnrAndOrgnr(toDelete: List<Pair<String, String>>): Int {
            if (toDelete.isEmpty()) return 0

            return connection
                .prepareStatement(
                    """
                    DELETE FROM sendt_sykmelding 
                    WHERE fnr = ? and orgnummer = ?
                    """.trimIndent()
                ).use { preparedStatement ->
                    toDelete.forEach { (fnr, orgnr) ->
                        preparedStatement.setObject(1, fnr)
                        preparedStatement.setObject(2, orgnr)
                        preparedStatement.addBatch()
                    }
                    val results = preparedStatement.executeBatch()
                    results.sum()
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
