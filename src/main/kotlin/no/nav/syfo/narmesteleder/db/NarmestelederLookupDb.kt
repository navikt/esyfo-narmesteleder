package no.nav.syfo.narmesteleder.db

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.syfo.application.database.DatabaseInterface
import no.nav.syfo.narmesteleder.domain.OrganizationNumber
import no.nav.syfo.narmesteleder.domain.PersonalIdentificationNumber
import java.time.Instant

data class ActiveNarmestelederEntity(
    val narmestelederFnr: PersonalIdentificationNumber,
    val narmestelederEpost: String,
    val aktivFom: Instant,
)

interface INarmestelederLookupDb {
    suspend fun findActiveNarmesteledere(
        sykmeldtFnr: PersonalIdentificationNumber,
        orgnummer: OrganizationNumber,
    ): List<ActiveNarmestelederEntity>
}

class NarmestelederLookupDb(
    private val database: DatabaseInterface,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : INarmestelederLookupDb {
    override suspend fun findActiveNarmesteledere(
        sykmeldtFnr: PersonalIdentificationNumber,
        orgnummer: OrganizationNumber,
    ): List<ActiveNarmestelederEntity> = withContext(dispatcher) {
        database.connection.use { connection ->
            connection.prepareStatement(
                """
                    SELECT narmeste_leder_fnr, narmeste_leder_epost, aktiv_fom
                    FROM narmeste_leder
                    WHERE sykmeldt_fnr = ?
                      AND orgnummer = ?
                      AND aktiv_tom IS NULL
                    ORDER BY aktiv_fom DESC, narmeste_leder_id DESC
                """.trimIndent()
            ).use { preparedStatement ->
                preparedStatement.setString(1, sykmeldtFnr.value)
                preparedStatement.setString(2, orgnummer.value)
                preparedStatement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            add(
                                ActiveNarmestelederEntity(
                                    narmestelederFnr = PersonalIdentificationNumber(resultSet.getString("narmeste_leder_fnr")),
                                    narmestelederEpost = resultSet.getString("narmeste_leder_epost"),
                                    aktivFom = resultSet.getTimestamp("aktiv_fom").toInstant(),
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
