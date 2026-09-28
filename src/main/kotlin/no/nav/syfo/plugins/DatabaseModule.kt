package no.nav.syfo.plugins

import no.nav.syfo.application.database.Database
import no.nav.syfo.application.database.DatabaseConfig
import no.nav.syfo.application.database.DatabaseInterface
import org.koin.dsl.module
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase

internal fun databaseModule() = module {
    single<DatabaseInterface> {
        Database(
            DatabaseConfig(
                jdbcUrl = env().database.jdbcUrl(),
                username = env().database.username,
                password = env().database.password,
            )
        )
    }
    single<ExposedDatabase> {
        val db = get<DatabaseInterface>() as Database
        ExposedDatabase.connect(datasource = db.dataSource)
    }
}
