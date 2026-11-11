package no.nav.syfo.sykmelding.infrastructure

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.TestDB
import org.jetbrains.exposed.v1.jdbc.SchemaUtils.checkMappingConsistence
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class SykmeldingsregisterDeadLetterTableTest :
    FunSpec({
        beforeTest {
            TestDB.clearAllData()
        }

        test("mapping matches the database schema") {
            transaction(TestDB.exposedDatabase) {
                checkMappingConsistence(SykmeldingsregisterDeadLetterTable, withLogs = true) shouldBe emptyList()
            }
        }

        test("round-trips through the table mapping") {
            transaction(TestDB.exposedDatabase) {
                SykmeldingsregisterDeadLetterTable.insert {
                    it[sourcePartition] = 3
                    it[sourceOffset] = 4_000_000_000L
                    it[reason] = "INVALID_PAYLOAD"
                }

                val row = SykmeldingsregisterDeadLetterTable.selectAll().single()
                assertSoftly {
                    row[SykmeldingsregisterDeadLetterTable.sourcePartition] shouldBe 3
                    row[SykmeldingsregisterDeadLetterTable.sourceOffset] shouldBe 4_000_000_000L
                    row[SykmeldingsregisterDeadLetterTable.reason] shouldBe "INVALID_PAYLOAD"
                }
            }
        }
    })
