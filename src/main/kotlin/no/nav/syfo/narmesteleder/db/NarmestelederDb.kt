package no.nav.syfo.narmesteleder.db

import java.sql.ResultSet
import java.util.*
import no.nav.syfo.application.database.DatabaseInterface

class NarmestelederGeneratedIDException(message: String) : RuntimeException(message)

class NarmestelederDb(private val database: DatabaseInterface) {
    fun insertNlBehov(nlBehov: NarmesteLederBehovEntity): UUID {
        return database.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                       INSERT INTO nl_behov(orgnummer, sykemeldt_fnr, narmeste_leder_fnr, leesah_status, behov_status) 
                       VALUES (?, ?, ?, ?, ?) RETURNING id;
                    """
                ).use { preparedStatement ->
                    preparedStatement.setString(1, nlBehov.orgnummer)
                    preparedStatement.setString(2, nlBehov.sykmeldtFnr)
                    preparedStatement.setString(3, nlBehov.narmesteLederFnr)
                    preparedStatement.setString(4, nlBehov.leesahStatus)
                    preparedStatement.setObject(5, nlBehov.behovStatus, java.sql.Types.OTHER)

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
