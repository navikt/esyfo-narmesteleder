package no.nav.syfo.narmesteleder.db

import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.syfo.application.database.DatabaseInterface
import no.nav.syfo.application.database.ResultPage
import no.nav.syfo.application.database.SqlBuilder
import no.nav.syfo.narmesteleder.domain.BehovStatus

interface INarmestelederDb {
    suspend fun insertNlBehov(nlBehov: NarmestelederBehovEntity): NarmestelederBehovEntity
    suspend fun updateNlBehov(nlBehov: NarmestelederBehovEntity)
    suspend fun findBehovById(id: UUID): NarmestelederBehovEntity?
    suspend fun findBehovByParameters(
        sykmeldtFnr: String,
        orgnummer: String,
        behovStatus: List<BehovStatus>
    ): List<NarmestelederBehovEntity>

    suspend fun findBehovByParameters(
        orgNumber: String,
        createdAfter: Instant,
        status: List<BehovStatus>,
        limit: Int,
    ): List<NarmestelederBehovEntity>

    suspend fun findByCreatedBeforeAndStatus(
        createdBefore: Instant,
        page: Int,
        pageSize: Int,
        status: List<BehovStatus>,
    ): ResultPage<NarmestelederBehovEntity>

    suspend fun getNlBehovByStatus(status: List<BehovStatus>): List<NarmestelederBehovEntity>
    suspend fun getNlBehovByStatus(status: BehovStatus): List<NarmestelederBehovEntity> =
        getNlBehovByStatus(listOf(status))
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
                        etternavn            = ?
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
                        preparedStatement.setObject(10, id)
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

    override suspend fun findBehovByParameters(
        sykmeldtFnr: String,
        orgnummer: String,
        behovStatus: List<BehovStatus>
    ): List<NarmestelederBehovEntity> = withContext(dispatcher) {
        return@withContext database.connection.use { connection ->
            SqlBuilder.filterBuilder {
                filterParam(SqlBuilder.Column.ORGNUMMER, orgnummer)
                filterParam(SqlBuilder.Column.SYKEMELDT_FNR, sykmeldtFnr)
                filterParam(SqlBuilder.Column.BEHOV_STATUS, behovStatus, SqlBuilder.ComparisonOperator.IN)

                connection.prepareStatement(
                    """
                        SELECT * FROM nl_behov 
                          ${buildWhereClause()}
                    """.trimIndent()
                )
            }.use { preparedStatement ->
                val resultSet = preparedStatement.executeQuery()
                val nlBehov = mutableListOf<NarmestelederBehovEntity>()
                while (resultSet.next()) {
                    nlBehov.add(resultSet.toNarmestelederBehovEntity())
                }
                nlBehov
            }
        }
    }

    override suspend fun getNlBehovByStatus(status: List<BehovStatus>): List<NarmestelederBehovEntity> =
        withContext(dispatcher) {
            return@withContext database.connection.use { connection ->
                SqlBuilder.filterBuilder {
                    orderBy = SqlBuilder.Column.CREATED
                    orderDirection = SqlBuilder.OrderDirection.ASC
                    limit = 100

                    filterParam(SqlBuilder.Column.BEHOV_STATUS, status, SqlBuilder.ComparisonOperator.IN)
                    filterParam(
                        SqlBuilder.Column.CREATED,
                        Timestamp.from(Instant.now().minusSeconds(10)),
                        SqlBuilder.ComparisonOperator.LESS_THAN
                    )

                    connection.prepareStatement(
                        """
                    SELECT *
                    FROM nl_behov
                    ${buildWhereClause()}
                    """.trimIndent()
                    )
                }.use { preparedStatement ->
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
        limit: Int,
    ): List<NarmestelederBehovEntity> =
        withContext(dispatcher) {
            return@withContext database.connection.use { connection ->
                SqlBuilder.filterBuilder {
                    this.limit = limit
                    this.orderBy = SqlBuilder.Column.CREATED
                    this.orderDirection = SqlBuilder.OrderDirection.ASC

                    filterParam(SqlBuilder.Column.ORGNUMMER, orgNumber)
                    filterParam(SqlBuilder.Column.BEHOV_STATUS, status, SqlBuilder.ComparisonOperator.IN)
                    filterParam(SqlBuilder.Column.CREATED, createdAfter, SqlBuilder.ComparisonOperator.GREATER_THAN)

                    connection.prepareStatement(
                        """
                        SELECT * 
                        FROM nl_behov
                        ${buildWhereClause()}
                    """.trimIndent()
                    )
                }.use { preparedStatement ->
                    val resultSet = preparedStatement.executeQuery()
                    val nlBehov = mutableListOf<NarmestelederBehovEntity>()
                    while (resultSet.next()) {
                        nlBehov.add(resultSet.toNarmestelederBehovEntity())
                    }
                    nlBehov
                }
            }
        }

    override suspend fun findByCreatedBeforeAndStatus(
        createdBefore: Instant,
        page: Int,
        pageSize: Int,
        status: List<BehovStatus>
    ): ResultPage<NarmestelederBehovEntity> =
        withContext(dispatcher) {
            val page = page.coerceAtLeast(0)
            val pageSize = pageSize.coerceIn(1, 500)

            database.connection.use { connection ->
                SqlBuilder.filterBuilder {
                    offset = page * pageSize
                    limit = pageSize
                    orderBy = SqlBuilder.Column.CREATED
                    orderDirection = SqlBuilder.OrderDirection.ASC

                    filterParam(SqlBuilder.Column.CREATED, createdBefore, SqlBuilder.ComparisonOperator.LESS_THAN)
                    filterParam(SqlBuilder.Column.BEHOV_STATUS, status, SqlBuilder.ComparisonOperator.IN)
                    connection.prepareStatement(
                        """SELECT * 
                            FROM nl_behov 
                            ${buildWhereClause()}
                            """.trimIndent()
                    )
                }.use { preparedStatement ->
                    val resultSet = preparedStatement.executeQuery()

                    ResultPage(
                        items = buildList {
                            while (resultSet.next()) {
                                add(resultSet.toNarmestelederBehovEntity())
                            }
                        },
                        page = page,
                    )
                }
            }
        }
}

class NarmestelederBehovEntityInsertException(message: String) : RuntimeException(message)
