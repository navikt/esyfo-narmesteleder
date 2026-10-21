package no.nav.syfo.bootstrap

import no.nav.syfo.platform.database.Database
import no.nav.syfo.platform.database.DatabaseConfig
import no.nav.syfo.platform.database.DatabaseEnvironment
import no.nav.syfo.platform.database.DatabaseInterface
import org.koin.dsl.module
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase

internal fun databaseModule(database: DatabaseEnvironment) = module {
    single<DatabaseInterface> {
        Database(
            DatabaseConfig(
                jdbcUrl = database.jdbcUrl(),
                username = database.username,
                password = database.password,
            )
        )
    }
    single<ExposedDatabase> {
        val db = get<DatabaseInterface>() as Database
        ExposedDatabase.connect(datasource = db.dataSource)
    }
}
