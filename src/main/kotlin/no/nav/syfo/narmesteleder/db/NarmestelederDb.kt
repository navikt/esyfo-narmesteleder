package no.nav.syfo.narmesteleder.db

import java.sql.ResultSet
import java.util.*
import no.nav.syfo.application.database.DatabaseInterface

class NarmestelederGeneratedIDException(message: String) : RuntimeException(message)

class NarmestelederDb(private val database: DatabaseInterface) {
    fun insertNlBehov(nlAvbrutt: NarmesteLederBehovEntity): UUID {
        return database.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                       insert into nl_behov(orgnummer, sykemeldt_fnr, narmeste_leder_fnr, leesah_status, behov_status) 
                       values (?, ?, ?, ?, ?) RETURNING id;
                    """
                ).use { preparedStatement ->
                    preparedStatement.setString(1, nlAvbrutt.orgnummer)
                    preparedStatement.setString(2, nlAvbrutt.sykmeldtFnr)
                    preparedStatement.setString(3, nlAvbrutt.narmesteLederFnr)
                    preparedStatement.setString(4, nlAvbrutt.leesahStatus)
                    preparedStatement.setString(5, nlAvbrutt.behovStatus)

                    preparedStatement.execute()

                    runCatching { preparedStatement.resultSet.getGeneratedUUID("id") }.getOrElse {
                        connection.rollback()
                        throw it
                    }
                }.also {
                    connection.commit()
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
