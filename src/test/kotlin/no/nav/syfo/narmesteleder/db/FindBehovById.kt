package no.nav.syfo.narmesteleder.db

import no.nav.syfo.application.database.DatabaseInterface
import java.util.UUID

fun DatabaseInterface.findBehovById(id: UUID): NarmestelederBehovEntity? = connection.use { connection ->
    connection.prepareStatement("SELECT * FROM nl_behov WHERE id = ?").use { preparedStatement ->
        preparedStatement.setObject(1, id)
        preparedStatement.executeQuery().use { resultSet ->
            if (resultSet.next()) resultSet.toNarmestelederBehovEntity() else null
        }
    }
}
