package no.nav.syfo.sykmelding.infrastructure

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.TestDB
import org.jetbrains.exposed.v1.jdbc.SchemaUtils.checkMappingConsistence
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID

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

        test("round-trips through the table mapping with a sykmelding id") {
            val sykmeldingId = UUID.randomUUID()
            transaction(TestDB.exposedDatabase) {
                SykmeldingsregisterDeadLetterTable.insert {
                    it[sourcePartition] = 3
                    it[sourceOffset] = 4_000_000_000L
                    it[SykmeldingsregisterDeadLetterTable.sykmeldingId] = sykmeldingId
                    it[reason] = "INVALID_PAYLOAD: sykmelding.sykmeldingsperioder"
                }

                val row = SykmeldingsregisterDeadLetterTable.selectAll().single()
                assertSoftly {
                    row[SykmeldingsregisterDeadLetterTable.sourcePartition] shouldBe 3
                    row[SykmeldingsregisterDeadLetterTable.sourceOffset] shouldBe 4_000_000_000L
                    row[SykmeldingsregisterDeadLetterTable.sykmeldingId] shouldBe sykmeldingId
                    row[SykmeldingsregisterDeadLetterTable.reason] shouldBe "INVALID_PAYLOAD: sykmelding.sykmeldingsperioder"
                }
            }
        }

        test("round-trips through the table mapping without a sykmelding id") {
            transaction(TestDB.exposedDatabase) {
                SykmeldingsregisterDeadLetterTable.insert {
                    it[sourcePartition] = 0
                    it[sourceOffset] = 7L
                    it[reason] = "INVALID_KEY"
                }

                val row = SykmeldingsregisterDeadLetterTable.selectAll().single()
                assertSoftly {
                    row[SykmeldingsregisterDeadLetterTable.sykmeldingId] shouldBe null
                    row[SykmeldingsregisterDeadLetterTable.reason] shouldBe "INVALID_KEY"
                }
            }
        }
    })
