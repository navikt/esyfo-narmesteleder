package no.nav.syfo.application.database

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import no.nav.syfo.TestDB
import no.nav.syfo.narmesteleder.exposed.PersonTable
import no.nav.syfo.narmestelederbehov.infrastructure.NarmestelederbehovTable
import no.nav.syfo.narmestelederrelasjon.infrastructure.NarmestelederTable
import no.nav.syfo.sykmelding.exposed.SendtSykmeldingNarmestelederBruddTable
import no.nav.syfo.sykmelding.exposed.SendtSykmeldingTable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.vendors.currentDialectMetadata
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils

// Register every Exposed table here so its columns, indexes and constraints are checked against the
// Flyway-migrated schema. Exposed ignores the WHERE condition of partial indexes when comparing.
private val tables: List<Table> =
    listOf(
        NarmestelederTable,
        PersonTable,
        SendtSykmeldingTable,
        SendtSykmeldingNarmestelederBruddTable,
        NarmestelederbehovTable,
    )

// Known drift. Each entry names the exact statement and why it is allowed; the test fails when an
// entry no longer occurs, so remove it once the cause is fixed.
private val knownDrift: Map<Table, Set<String>> =
    mapOf(
        // #584: the application limits these to 255 characters, but the database column is unbounded.
        NarmestelederTable to setOf(
            "ALTER TABLE narmeste_leder ALTER COLUMN narmeste_leder_telefonnummer TYPE VARCHAR(255)",
            "ALTER TABLE narmeste_leder ALTER COLUMN narmeste_leder_epost TYPE VARCHAR(255)",
        ),
        // Exposed cannot define the covering index person_fnr_names_idx (INCLUDE columns).
        PersonTable to setOf("DROP INDEX IF EXISTS person_fnr_names_idx"),
    )

class TableDefinitionTest :
    FunSpec({
        val database = TestDB.exposedDatabase

        tables.forEach { table ->
            test("Exposed mapping for '${table.tableName}' matches the migrated schema") {
                val statements = transaction(database) {
                    MigrationUtils.statementsRequiredForDatabaseMigration(table, withLogs = false)
                }
                val expectedDrift = knownDrift[table].orEmpty()
                val drift = statements.filterNot { it in expectedDrift }
                val resolvedDrift = expectedDrift - statements.toSet()

                withClue("Schema drift for '${table.tableName}':\n${drift.joinToString("\n")}") {
                    drift.shouldBeEmpty()
                }
                withClue("Known drift no longer occurs; remove it from knownDrift:\n${resolvedDrift.joinToString("\n")}") {
                    resolvedDrift.shouldBeEmpty()
                }
            }
        }

        test("every migrated table is registered") {
            val migratedTables = transaction(database) {
                currentDialectMetadata.allTablesNames
                    .map { it.substringAfterLast('.') }
                    .filterNot { it.startsWith("flyway") }
            }

            withClue("Register the Exposed table for each migrated table in TableDefinitionTest") {
                migratedTables shouldContainExactlyInAnyOrder tables.map { it.tableName }
            }
        }
    })
