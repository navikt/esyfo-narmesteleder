package no.nav.syfo.narmesteleder.db

import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID
import no.nav.syfo.application.database.DatabaseInterface

class NarmesteLederDb(private val database: DatabaseInterface) {
    fun insertNlAvbrudd(nlAvbrutt: NarmesteLederAvbruttEntity): UUID {
        return database.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                       insert into narmesteleder(orgnummer, sykemeldt_fnr, narmeste_leder_fnr, status, aktiv_fom, aktiv_tom) 
                       values (?, ?, ?, ?, ?, ?);
                    """
                ).use { preparedStatement ->
                    preparedStatement.setString(1, nlAvbrutt.orgnummer)
                    preparedStatement.setString(2, nlAvbrutt.sykmeldtFnr)
                    preparedStatement.setString(3, nlAvbrutt.narmesteLederFnr)
                    preparedStatement.setString(4, nlAvbrutt.status)
                    preparedStatement.setTimestamp(5, Timestamp.from(nlAvbrutt.aktivFom.toInstant()))
                    preparedStatement.setTimestamp(6, Timestamp.from(nlAvbrutt.aktivTom.toInstant()))

                    val rowsUpdated = preparedStatement.executeUpdate() > 0
                    if (rowsUpdated) {
                        preparedStatement.generatedKeys.getGeneratedUUID(1)
                            ?: throw RuntimeException("Could not get generated id for NL Avbrutt")
                    } else {
                        throw RuntimeException("Failed to insert NL Avbrutt")
                    }
                }.also {
                    connection.commit()
                }
        }
    }
}

private fun ResultSet.getGeneratedUUID(idColumnIndex: Int): UUID? =
    if (this.next()) {
        this.getObject(idColumnIndex) as UUID
    } else {
        null
    }
