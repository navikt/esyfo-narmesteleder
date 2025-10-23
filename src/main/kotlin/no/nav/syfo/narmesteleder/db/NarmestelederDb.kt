package no.nav.syfo.narmesteleder.db

import java.sql.ResultSet
import java.util.*
import no.nav.syfo.application.database.DatabaseInterface

class NarmestelederGeneratedIDException(message: String) : RuntimeException(message)
interface INarmestelederDb {
    fun insertNlBehov(nlBehov: NarmestelederBehovEntity): UUID
    fun updateNlBehov(nlBehov: NarmestelederBehovEntity)
    fun findBehovById(id: UUID): NarmestelederBehovEntity?
}

class NarmestelederDb(private val database: DatabaseInterface) : INarmestelederDb {
    override fun insertNlBehov(nlBehov: NarmestelederBehovEntity): UUID {
        return database.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                       INSERT INTO nl_behov(orgnummer, hovedenhet_orgnummer, sykemeldt_fnr, narmeste_leder_fnr, leesah_status, behov_status) 
                       VALUES (?, ?, ?, ?, ?, ?) RETURNING id;
                    """
                ).use { preparedStatement ->
                    preparedStatement.setString(1, nlBehov.orgnummer)
                    preparedStatement.setString(2, nlBehov.hovedenhetOrgnummer)
                    preparedStatement.setString(3, nlBehov.sykmeldtFnr)
                    preparedStatement.setString(4, nlBehov.narmestelederFnr)
                    preparedStatement.setString(5, nlBehov.leesahStatus)
                    preparedStatement.setObject(6, nlBehov.behovStatus, java.sql.Types.OTHER)

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

    override fun updateNlBehov(nlBehov: NarmestelederBehovEntity) {
        database.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                       UPDATE nl_behov
                       SET orgnummer = ?, hovedenhet_orgnummer = ?, sykemeldt_fnr = ?, narmeste_leder_fnr = ?, behov_status = ?
                       WHERE id = ?;
                    """
                ).use { preparedStatement ->
                    preparedStatement.setString(1, nlBehov.orgnummer)
                    preparedStatement.setString(2, nlBehov.hovedenhetOrgnummer)
                    preparedStatement.setString(3, nlBehov.sykmeldtFnr)
                    preparedStatement.setString(4, nlBehov.narmestelederFnr)
                    preparedStatement.setObject(5, nlBehov.behovStatus, java.sql.Types.OTHER)
                    preparedStatement.setObject(6, nlBehov.id)

                    preparedStatement.executeUpdate()
                }.also {
                    connection.commit()
                }
        }
    }

    override fun findBehovById(id: UUID): NarmestelederBehovEntity? {
        return database.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                       SELECT id, orgnummer, hovedenhet_orgnummer, sykemeldt_fnr, narmeste_leder_fnr, leesah_status, behov_status
                       FROM nl_behov
                       WHERE id = ?;
                    """
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
