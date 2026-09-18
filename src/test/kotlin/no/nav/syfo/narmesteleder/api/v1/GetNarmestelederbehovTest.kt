package no.nav.syfo.narmesteleder.api.v1

import DefaultSystemPrincipal
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import no.nav.syfo.narmesteleder.domain.LineManagerRequirementStatus
import no.nav.syfo.narmesteleder.domain.LinemanagerRequirementRead
import no.nav.syfo.narmesteleder.domain.Name
import no.nav.syfo.narmesteleder.domain.OrganizationNumber
import no.nav.syfo.narmesteleder.domain.PersonalIdentificationNumber
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

        test("should preserve cancellation from organization access validation") {
            val requirementId = UUID.randomUUID()
            val orgNumber = OrganizationNumber("123456789")
            coEvery {
                narmestelederService.getLinemanagerRequirementReadById(requirementId)
            } returns LinemanagerRequirementRead(
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

            coEvery {
                validationService.validatePrincipalAccessToOrgnumber(any(), orgNumber)
            } throws CancellationException("Request cancelled")

            shouldThrow<CancellationException> {
                handler.handleGetLinemanagerRequirement(
                    requirementId = requirementId,
                    principal = DefaultSystemPrincipal,
                )
            }
        }
    })
