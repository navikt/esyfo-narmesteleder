package no.nav.syfo.sykmelding.db

import java.sql.Date
import java.sql.Timestamp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.syfo.application.database.DatabaseInterface

interface ISykmeldingDb {
    suspend fun insertSykmelding(sykmeldingEntity: SykmeldingEntity)
}

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
}
