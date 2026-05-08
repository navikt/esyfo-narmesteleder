package no.nav.syfo.sykmelding.exposed

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.TestDB
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class SendtSykmeldingRepositoryTest :
    DescribeSpec({
        val fixedClock = Clock.fixed(Instant.parse("2026-05-08T10:15:30Z"), ZoneOffset.UTC)
        val repository = SendtSykmeldingRepository(TestDB.exposedDatabase, fixedClock)
        val today = LocalDate.now(fixedClock)

        beforeTest {
            TestDB.clearSendtSykmeldingData()
        }

        fun insertSendtSykmelding(
            fnr: String = "12345678910",
            orgnummer: String = "123456789",
            tom: LocalDate,
            revokedDate: LocalDate? = null,
        ) {
            transaction(TestDB.exposedDatabase) {
                SendtSykmeldingTable.insert {
                    it[sykmeldingId] = UUID.randomUUID()
                    it[SendtSykmeldingTable.orgnummer] = orgnummer
                    it[syketilfelleStartDato] = tom.minusDays(10)
                    it[SendtSykmeldingTable.fnr] = fnr
                    it[fom] = tom.minusDays(20)
                    it[SendtSykmeldingTable.tom] = tom
                    it[SendtSykmeldingTable.revokedDate] = revokedDate
                }
            }
        }

        describe("hasActiveSykmelding") {
            it("returns true when tom is on the included grace boundary") {
                insertSendtSykmelding(tom = today.minusDays(SendtSykmeldingRepository.ACTIVE_SYKMELDING_GRACE_PERIOD_DAYS))

                repository.hasActiveSykmelding("12345678910", "123456789") shouldBe true
            }

            it("returns false when tom is outside the grace period") {
                insertSendtSykmelding(tom = today.minusDays(SendtSykmeldingRepository.ACTIVE_SYKMELDING_GRACE_PERIOD_DAYS + 1))

                repository.hasActiveSykmelding("12345678910", "123456789") shouldBe false
            }

            it("returns false when sykmeldingen is revoked") {
                insertSendtSykmelding(
                    tom = today,
                    revokedDate = today.minusDays(1),
                )

                repository.hasActiveSykmelding("12345678910", "123456789") shouldBe false
            }

            it("returns false when there is no matching row") {
                repository.hasActiveSykmelding("12345678910", "123456789") shouldBe false
            }

            it("returns true when tom is in the future") {
                insertSendtSykmelding(tom = today.plusDays(5))

                repository.hasActiveSykmelding("12345678910", "123456789") shouldBe true
            }
        }
    })
