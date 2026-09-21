package no.nav.syfo.narmesteleder.api.v1

import DefaultSystemPrincipal
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.narmesteleder.domain.LineManagerRequirementStatus
import no.nav.syfo.narmesteleder.domain.LinemanagerRequirementRead
import no.nav.syfo.narmesteleder.domain.Name
import no.nav.syfo.narmesteleder.domain.OrganizationNumber
import no.nav.syfo.narmesteleder.domain.PersonalIdentificationNumber
import no.nav.syfo.narmesteleder.exception.LinemanagerRequirementNotFoundException
import no.nav.syfo.narmesteleder.service.NarmestelederKafkaService
import no.nav.syfo.narmesteleder.service.NarmestelederService
import no.nav.syfo.narmesteleder.service.ValidationService
import java.time.Instant
import java.util.UUID

class GetNarmestelederbehovTest :
    FunSpec({
        val narmestelederService = mockk<NarmestelederService>()
        val validationService = mockk<ValidationService>()
        val narmestelederKafkaService = mockk<NarmestelederKafkaService>()
        val handler = LinemanagerRequirementRESTHandler(
            narmesteLederService = narmestelederService,
            validationService = validationService,
            narmestelederKafkaService = narmestelederKafkaService,
        )
        val requirementId = UUID.randomUUID()
        val orgNumber = OrganizationNumber("123456789")
        val requirement = LinemanagerRequirementRead(
            id = requirementId,
            employeeIdentificationNumber = PersonalIdentificationNumber("12345678901"),
            orgNumber = orgNumber,
            mainOrgNumber = OrganizationNumber("987654321"),
            name = Name(firstName = "Test", lastName = "Person", middleName = null),
            created = Instant.EPOCH,
            updated = Instant.EPOCH,
            status = LineManagerRequirementStatus.CREATED,
            revokedBy = null,
        )

        beforeTest {
            clearMocks(narmestelederService, validationService, narmestelederKafkaService)
            coEvery { narmestelederService.getLinemanagerRequirementReadById(requirementId) } returns requirement
        }

        test("should return the requirement with the organization name after checking access") {
            coEvery {
                validationService.validatePrincipalAccessToOrgnumber(DefaultSystemPrincipal, orgNumber)
            } returns "Organization"

            handler.handleGetLinemanagerRequirement(requirementId, DefaultSystemPrincipal) shouldBe
                requirement.copy(orgName = "Organization")

            coVerifyOrder {
                narmestelederService.getLinemanagerRequirementReadById(requirementId)
                validationService.validatePrincipalAccessToOrgnumber(DefaultSystemPrincipal, orgNumber)
            }
        }

        test("should report a missing requirement before checking access") {
            coEvery {
                narmestelederService.getLinemanagerRequirementReadById(requirementId)
            } throws LinemanagerRequirementNotFoundException("Missing requirement")

            shouldThrow<ApiErrorException.NotFoundException> {
                handler.handleGetLinemanagerRequirement(requirementId, DefaultSystemPrincipal)
            }

            verify { validationService wasNot Called }
        }

        test("should preserve access denial without returning the requirement") {
            val denied = ApiErrorException.ForbiddenException()
            coEvery {
                validationService.validatePrincipalAccessToOrgnumber(DefaultSystemPrincipal, orgNumber)
            } throws denied

            shouldThrow<ApiErrorException.ForbiddenException> {
                handler.handleGetLinemanagerRequirement(requirementId, DefaultSystemPrincipal)
            } shouldBe denied
        }

        test("should preserve cancellation from the requirement lookup") {
            val cancellation = CancellationException("Request cancelled")
            coEvery {
                narmestelederService.getLinemanagerRequirementReadById(requirementId)
            } throws cancellation

            shouldThrow<CancellationException> {
                handler.handleGetLinemanagerRequirement(requirementId, DefaultSystemPrincipal)
            } shouldBe cancellation

            verify { validationService wasNot Called }
        }

        test("should preserve cancellation from organization access validation") {
            val cancellation = CancellationException("Request cancelled")
            coEvery {
                validationService.validatePrincipalAccessToOrgnumber(any(), orgNumber)
            } throws cancellation

            shouldThrow<CancellationException> {
                handler.handleGetLinemanagerRequirement(
                    requirementId = requirementId,
                    principal = DefaultSystemPrincipal,
                )
            } shouldBe cancellation
        }
    })
