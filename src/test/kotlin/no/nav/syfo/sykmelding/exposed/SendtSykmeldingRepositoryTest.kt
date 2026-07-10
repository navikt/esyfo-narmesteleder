package no.nav.syfo.sykmelding.exposed

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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
        ): UUID {
            val sykmeldingId = UUID.randomUUID()
            transaction(TestDB.exposedDatabase) {
                SendtSykmeldingTable.insert {
                    it[SendtSykmeldingTable.sykmeldingId] = sykmeldingId
                    it[SendtSykmeldingTable.orgnummer] = orgnummer
                    it[syketilfelleStartDato] = tom.minusDays(10)
                    it[SendtSykmeldingTable.fnr] = fnr
                    it[fom] = tom.minusDays(20)
                    it[SendtSykmeldingTable.tom] = tom
                    it[SendtSykmeldingTable.revokedDate] = revokedDate
                }
            }
            return sykmeldingId
        }

        describe("LocalActiveSykmeldingResult.toString") {
            it("redacts sykmeldingId in string output") {
                val sykmeldingId = UUID.fromString("11111111-1111-1111-1111-111111111111")

                val result = LocalActiveSykmeldingResult(isActive = true, sykmeldingId = sykmeldingId)

                result.toString() shouldBe
                    "LocalActiveSykmeldingResult(isActive=true, sykmeldingId=<redacted>)"
                result.toString() shouldNotContain sykmeldingId.toString()
                result.toString() shouldContain "sykmeldingId=<redacted>"
            }
        }

        describe("findActiveSykmelding") {
            it("returns active result with sykmeldingId when tom is on the included grace boundary") {
                val sykmeldingId =
                    insertSendtSykmelding(tom = today.minusDays(SendtSykmeldingRepository.ACTIVE_SYKMELDING_GRACE_PERIOD_DAYS))

                val result = repository.findActiveSykmelding("12345678910", "123456789")

                result.isActive shouldBe true
                result.sykmeldingId shouldBe sykmeldingId
            }

            it("returns inactive result without sykmeldingId when tom is outside the grace period") {
                insertSendtSykmelding(tom = today.minusDays(SendtSykmeldingRepository.ACTIVE_SYKMELDING_GRACE_PERIOD_DAYS + 1))

                val result = repository.findActiveSykmelding("12345678910", "123456789")

                result.isActive shouldBe false
                result.sykmeldingId shouldBe null
            }

            it("returns inactive result without sykmeldingId when sykmeldingen is revoked") {
                insertSendtSykmelding(
                    tom = today,
                    revokedDate = today.minusDays(1),
                )

                val result = repository.findActiveSykmelding("12345678910", "123456789")

                result.isActive shouldBe false
                result.sykmeldingId shouldBe null
            }

            it("returns inactive result without sykmeldingId when there is no matching row") {
                val result = repository.findActiveSykmelding("12345678910", "123456789")

                result.isActive shouldBe false
                result.sykmeldingId shouldBe null
            }

            it("returns inactive result without sykmeldingId when fnr does not match an active row") {
                insertSendtSykmelding(fnr = "10987654321", tom = today)

                val result = repository.findActiveSykmelding("12345678910", "123456789")

                result.isActive shouldBe false
                result.sykmeldingId shouldBe null
            }

            it("returns inactive result without sykmeldingId when orgnummer does not match an active row") {
                insertSendtSykmelding(orgnummer = "987654321", tom = today)

                val result = repository.findActiveSykmelding("12345678910", "123456789")

                result.isActive shouldBe false
                result.sykmeldingId shouldBe null
            }

            it("returns active result with sykmeldingId when tom is in the future") {
                val sykmeldingId = insertSendtSykmelding(tom = today.plusDays(5))

                val result = repository.findActiveSykmelding("12345678910", "123456789")

                result.isActive shouldBe true
                result.sykmeldingId shouldBe sykmeldingId
            }
        }
    })
