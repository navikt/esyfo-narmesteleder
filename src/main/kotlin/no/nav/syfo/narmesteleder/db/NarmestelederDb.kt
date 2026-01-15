package no.nav.syfo.narmesteleder.db

import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.syfo.application.database.DatabaseInterface
import no.nav.syfo.narmesteleder.domain.BehovStatus

class NarmestelederGeneratedIDException(message: String) : RuntimeException(message)
interface INarmestelederDb {
    suspend fun insertNlBehov(nlBehov: NarmestelederBehovEntity): NarmestelederBehovEntity
    suspend fun updateNlBehov(nlBehov: NarmestelederBehovEntity)
    suspend fun findBehovById(id: UUID): NarmestelederBehovEntity?
    suspend fun findBehovByParameters(sykmeldtFnr: String, orgnummer: String, behovStatus: List<BehovStatus>): List<NarmestelederBehovEntity>
    suspend fun getNlBehovByStatus(status: BehovStatus): List<NarmestelederBehovEntity>
    suspend fun findBehovByParameters(
        orgNumber: String,
        createdAfter: Instant,
        status: List<BehovStatus>,
        limit: Int
    ): List<NarmestelederBehovEntity>
     suspend fun getNlBehovForDelete(limit: Int): List<NarmestelederBehovEntity>
}

class NarmestelederDb(
    private val database: DatabaseInterface,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) :
    INarmestelederDb {
    override suspend fun insertNlBehov(nlBehov: NarmestelederBehovEntity): NarmestelederBehovEntity =
        withContext(dispatcher) {
            return@withContext database.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                    INSERT INTO nl_behov(orgnummer,
                                         hovedenhet_orgnummer,
                                         sykemeldt_fnr,
                                         narmeste_leder_fnr,
                                         behov_reason,
                                         behov_status,
                                         avbrutt_narmesteleder_id,
                                         fornavn,
                                         mellomnavn,
                                         etternavn)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    RETURNING *;
                    """.trimIndent()
                    ).use { preparedStatement ->
                        preparedStatement.setString(1, nlBehov.orgnummer)
                        preparedStatement.setString(2, nlBehov.hovedenhetOrgnummer)
                        preparedStatement.setString(3, nlBehov.sykmeldtFnr)
                        preparedStatement.setString(4, nlBehov.narmestelederFnr)
                        preparedStatement.setString(5, nlBehov.behovReason.name)
                        preparedStatement.setObject(6, nlBehov.behovStatus, java.sql.Types.OTHER)
                        preparedStatement.setObject(7, nlBehov.avbruttNarmesteLederId)
                        preparedStatement.setString(8, nlBehov.fornavn)
                        preparedStatement.setString(9, nlBehov.mellomnavn)
                        preparedStatement.setString(10, nlBehov.etternavn)
                        preparedStatement.execute()

                        runCatching {
                            if (preparedStatement.resultSet.next()) {
                                preparedStatement.resultSet.toNarmestelederBehovEntity()
                            } else throw NarmestelederBehovEntityInsertException("Could not get the inserted document.")
                        }.getOrElse {
                            connection.rollback()
                            throw it
                        }
                    }.also {
                        connection.commit()
                    }
            }
        }

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
                        dialog_delete_performed = ?
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
                        }
                        preparedStatement.setObject(11, id)
                    }
                    preparedStatement.executeUpdate()
                }.also {
                    connection.commit()
                }
        }
    }

    override suspend fun findBehovById(id: UUID): NarmestelederBehovEntity? = withContext(dispatcher) {
        return@withContext database.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                       SELECT * FROM nl_behov WHERE id = ?;
                    """.trimIndent()
                ).use { preparedStatement ->
                    preparedStatement.setObject(1, id)

                    preparedStatement.executeQuery().use { resultSet ->
                        if (resultSet.next()) {
                            resultSet.toNarmestelederBehovEntity()
                        } else {
                            null
                        }
                    }
                }
        }
    }

    override suspend fun findBehovByParameters(sykmeldtFnr: String, orgnummer: String, behovStatus: List<BehovStatus>): List<NarmestelederBehovEntity> = withContext(dispatcher)  {
        val placeholders = behovStatus.joinToString(", ") { "?" }
        return@withContext database.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                        SELECT * FROM nl_behov 
                          WHERE orgnummer = ? AND 
                              sykemeldt_fnr = ? AND
                              behov_status IN ($placeholders) 
                    """.trimIndent()
                ).use { preparedStatement ->
                    var idx = 1
                    preparedStatement.setObject(idx++, orgnummer)
                    preparedStatement.setObject(idx++, sykmeldtFnr)
                    behovStatus.forEach { status ->
                        preparedStatement.setObject(idx++, status, java.sql.Types.OTHER)
                    }

                    preparedStatement.executeQuery().use { resultSet ->
                        val resultSet = preparedStatement.executeQuery()
                        val nlBehov = mutableListOf<NarmestelederBehovEntity>()
                        while (resultSet.next()) {
                            nlBehov.add(resultSet.toNarmestelederBehovEntity())
                        }
                        nlBehov
                    }
                }
        }
    }

    override suspend fun getNlBehovByStatus(status: BehovStatus): List<NarmestelederBehovEntity> =
        withContext(dispatcher) {
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
                        LIMIT 100
                        """.trimIndent()
                    ).use { preparedStatement ->
                        preparedStatement.setObject(1, status, java.sql.Types.OTHER)
                        val resultSet = preparedStatement.executeQuery()
                        val nlBehov = mutableListOf<NarmestelederBehovEntity>()
                        while (resultSet.next()) {
                            nlBehov.add(resultSet.toNarmestelederBehovEntity())
                        }
                        nlBehov
                    }
            }
        }

    override suspend fun getNlBehovForDelete(limit: Int): List<NarmestelederBehovEntity> =
        withContext(dispatcher) {
            return@withContext database.connection.use { connection ->
                connection
                    // Add AND created < NOW() - INTERVAL '1 minute' in where clause if we add something that triggers sending immediately after insert
                    .prepareStatement(
                        """
                        SELECT *
                        FROM nl_behov
                        WHERE dialog_delete_performed IS NULL
                        ORDER BY created
                        LIMIT ?
                        """.trimIndent()
                    ).use { preparedStatement ->
                        preparedStatement.setInt(1, limit)
                        val resultSet = preparedStatement.executeQuery()
                        val nlBehov = mutableListOf<NarmestelederBehovEntity>()
                        while (resultSet.next()) {
                            nlBehov.add(resultSet.toNarmestelederBehovEntity())
                        }
                        nlBehov
                    }
            }
        }

    override suspend fun findBehovByParameters(
        orgNumber: String,
        createdAfter: Instant,
        status: List<BehovStatus>,
        limit: Int
    ): List<NarmestelederBehovEntity> =
        withContext(dispatcher) {
            return@withContext database.connection.use { connection ->
                val placeholders = status.joinToString(", ") { "?" }
                connection
                    .prepareStatement(
                        """
                        SELECT *
                        FROM nl_behov
                        WHERE 
                            orgnummer = ?
                        AND
                            behov_status in ($placeholders) 
                        AND
                            created > ? 
                        ORDER BY created
                        LIMIT ?
                        """.trimIndent()
                    ).use { preparedStatement ->
                        var idx = 1
                        preparedStatement.setString(idx++, orgNumber)
                        status.forEach { status ->
                            preparedStatement.setObject(idx++, status, java.sql.Types.OTHER)
                        }
                        preparedStatement.setTimestamp(idx++, Timestamp.from(createdAfter))
                        preparedStatement.setInt(idx++, limit)
                        val resultSet = preparedStatement.executeQuery()
                        val nlBehov = mutableListOf<NarmestelederBehovEntity>()
                        while (resultSet.next()) {
                            nlBehov.add(resultSet.toNarmestelederBehovEntity())
                        }
                        nlBehov
                    }
            }
        }
}

private fun ResultSet.getGeneratedUUID(idColumnLabel: String): UUID = this.use {
    val id = if (this.next()) {
        this.getObject(idColumnLabel) as? UUID
    } else {
        null
    }

    return id ?: throw NarmestelederGeneratedIDException(
        "Could not get the generated id."
    )
}

class NarmestelederBehovEntityInsertException(message: String) : RuntimeException(message)
