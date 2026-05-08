package no.nav.syfo.narmesteleder.service.validators

import faker
import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import no.nav.syfo.TestDB
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.sykmelding.db.SendtSykmeldingEntity
import no.nav.syfo.sykmelding.db.SykmeldingDb
import no.nav.syfo.sykmelding.exposed.SendtSykmeldingRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class SickLeaveValidatorTest :
    DescribeSpec({
        val osloZone = ZoneId.of("Europe/Oslo")
        val clock = Clock.fixed(Instant.parse("2025-01-20T10:00:00Z"), osloZone)
        val sykmeldingDb = SykmeldingDb(TestDB.database)
        val activeSykmeldingRepository = SendtSykmeldingRepository(
            database = TestDB.exposedDatabase,
            clock = clock,
        )
        val validator = SickLeaveValidator(activeSykmeldingRepository)
        val fnr = faker.numerify("###########")
        val orgnummer = faker.numerify("#########")
        val today = LocalDate.now(clock)

        beforeTest {
            TestDB.clearSendtSykmeldingData()
        }

        describe("validateActiveSickLeave") {
            it("should not throw when active sick leave exists") {
                sykmeldingDb.transaction {
                    batchUpsertSykmeldingerIfMoreRecentTom(
                        listOf(
                            SendtSykmeldingEntity(
                                sykmeldingId = UUID.randomUUID(),
                                fnr = fnr,
                                orgnummer = orgnummer,
                                fom = today.minusDays(5),
                                tom = today.minusDays(1),
                                revokedDate = null,
                                syketilfelleStartDato = today.minusDays(5),
                                created = Instant.now(clock),
                                updated = Instant.now(clock),
                            )
                        )
                    )
                }
                shouldNotThrow<ApiErrorException.BadRequestException> {
                    validator.validateActiveSickLeave(fnr, orgnummer)
                }
            }

            it("should throw BadRequestException with NO_ACTIVE_SICK_LEAVE when no active sick leave exists") {
                val exception = shouldThrow<ApiErrorException.BadRequestException> {
                    validator.validateActiveSickLeave(fnr, orgnummer)
                }

                exception.type shouldBe ErrorType.NO_ACTIVE_SICK_LEAVE
            }

            it("should include orgnummer in error message when no active sick leave exists") {
                val exception = shouldThrow<ApiErrorException.BadRequestException> {
                    validator.validateActiveSickLeave(fnr, orgnummer)
                }

                exception.message shouldContain orgnummer
            }
        }
    })
