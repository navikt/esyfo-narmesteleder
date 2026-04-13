package no.nav.syfo.narmesteleder

import no.nav.syfo.TestDB
import java.time.Instant
import java.util.UUID

data class PersistedPerson(
    val id: UUID,
    val fnr: String,
    val fornavn: String?,
    val mellomnavn: String?,
    val etternavn: String?,
    val status: String,
    val created: Instant,
    val updated: Instant,
)

fun fetchPersons(): List<PersistedPerson> = TestDB.database.connection.use { connection ->
    connection.prepareStatement(
        """
        SELECT id, fnr, fornavn, mellomnavn, etternavn, status, created, updated
        FROM person
        ORDER BY fnr
        """.trimIndent(),
    ).use { preparedStatement ->
        preparedStatement.executeQuery().use { resultSet ->
            buildList {
                while (resultSet.next()) {
                    add(
                        PersistedPerson(
                            id = resultSet.getObject("id", UUID::class.java),
                            fnr = resultSet.getString("fnr"),
                            fornavn = resultSet.getString("fornavn"),
                            mellomnavn = resultSet.getString("mellomnavn"),
                            etternavn = resultSet.getString("etternavn"),
                            status = resultSet.getString("status"),
                            created = resultSet.getTimestamp("created").toInstant(),
                            updated = resultSet.getTimestamp("updated").toInstant(),
                        ),
                    )
                }
            }
        }
    }
}

fun fetchPerson(fnr: String): PersistedPerson? = TestDB.database.connection.use { connection ->
    connection.prepareStatement(
        """
        SELECT id, fnr, fornavn, mellomnavn, etternavn, status, created, updated
        FROM person
        WHERE fnr = ?
        """.trimIndent(),
    ).use { preparedStatement ->
        preparedStatement.setString(1, fnr)
        preparedStatement.executeQuery().use { resultSet ->
            if (resultSet.next()) {
                PersistedPerson(
                    id = resultSet.getObject("id", UUID::class.java),
                    fnr = resultSet.getString("fnr"),
                    fornavn = resultSet.getString("fornavn"),
                    mellomnavn = resultSet.getString("mellomnavn"),
                    etternavn = resultSet.getString("etternavn"),
                    status = resultSet.getString("status"),
                    created = resultSet.getTimestamp("created").toInstant(),
                    updated = resultSet.getTimestamp("updated").toInstant(),
                )
            } else {
                null
            }
        }
    }
}
