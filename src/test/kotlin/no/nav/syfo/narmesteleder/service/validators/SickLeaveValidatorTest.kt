package no.nav.syfo.narmesteleder.service.validators

import faker
import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.spyk
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.dinesykmeldte.DinesykmeldteService
import no.nav.syfo.dinesykmeldte.client.FakeDinesykmeldteClient

class SickLeaveValidatorTest :
    DescribeSpec({
        val dinesykmeldteClient = FakeDinesykmeldteClient()
        val dinesykmeldteService = spyk(DinesykmeldteService(dinesykmeldteClient))
        val validator = SickLeaveValidator(dinesykmeldteService)
        val fnr = faker.numerify("###########")
        val orgnummer = faker.numerify("#########")

        beforeTest {
            clearAllMocks()
        }

        describe("validateActiveSickLeave") {
            it("should not throw when active sick leave exists") {
                shouldNotThrow<ApiErrorException.BadRequestException> {
                    validator.validateActiveSickLeave(fnr, orgnummer)
                }
            }

            it("should throw BadRequestException with NO_ACTIVE_SICK_LEAVE when no active sick leave exists") {
                coEvery { dinesykmeldteService.getIsActiveSykmelding(fnr, orgnummer) } returns false

                val exception = shouldThrow<ApiErrorException.BadRequestException> {
                    validator.validateActiveSickLeave(fnr, orgnummer)
                }

                exception.type shouldBe ErrorType.NO_ACTIVE_SICK_LEAVE
            }

            it("should include orgnummer in error message when no active sick leave exists") {
                coEvery { dinesykmeldteService.getIsActiveSykmelding(fnr, orgnummer) } returns false

                val exception = shouldThrow<ApiErrorException.BadRequestException> {
                    validator.validateActiveSickLeave(fnr, orgnummer)
                }

                exception.message shouldContain orgnummer
            }
        }
    })
