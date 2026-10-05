package no.nav.syfo.narmestelederbehov.api

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.altinntilganger.AltinnTilgangerService.Companion.OPPGI_NARMESTELEDER_RESOURCE
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmesteleder.domain.BehovReason
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmesteleder.domain.LineManagerRequirementStatus
import no.nav.syfo.narmesteleder.domain.RevokedBy
import no.nav.syfo.narmestelederbehov.application.BehovPersonName
import no.nav.syfo.narmestelederbehov.application.GetNarmestelederbehovResult
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovRead
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import java.time.Instant
import java.util.UUID

class GetNarmestelederbehovHttpMappingTest :
    FunSpec({
        test("maps all read response fields") {
            val behov = NarmestelederbehovRead(
                NarmestelederbehovId(UUID.randomUUID()),
                PersonIdent("12345678901"),
                OrganizationNumber("910000001"),
                "910000002",
                PersonIdent("12345678902"),
                "Stored",
                null,
                "Name",
                Instant.EPOCH,
                Instant.EPOCH,
                BehovStatus.BEHOV_CREATED,
                BehovReason.DEAKTIVERT_LEDER,
            )
            val dto = GetNarmestelederbehovResult.Found(behov, BehovPersonName("First", "Middle", "Last"), "Org").toLinemanagerRequirementRead()
            dto.id shouldBe behov.id.value
            dto.employeeIdentificationNumber.value shouldBe behov.employeeIdent.value
            dto.orgNumber.value shouldBe behov.organizationNumber.value
            dto.mainOrgNumber.value shouldBe behov.mainOrganizationNumber
            dto.managerIdentificationNumber?.value shouldBe behov.managerIdent?.value
            dto.orgName shouldBe "Org"
            dto.name.firstName shouldBe "First"
            dto.name.middleName shouldBe "Middle"
            dto.name.lastName shouldBe "Last"
            dto.created shouldBe behov.created
            dto.updated shouldBe behov.updated
            dto.status shouldBe LineManagerRequirementStatus.CREATED
            dto.revokedBy shouldBe RevokedBy.LINEMANAGER
        }

        test("missing id keeps legacy 404 message and type") {
            val error = shouldThrow<ApiErrorException.NotFoundException> {
                GetNarmestelederbehovResult.NotFound.toLinemanagerRequirementRead()
            }
            error.errorMessage shouldBe "LinemanagerRequirement"
            error.type shouldBe ErrorType.NOT_FOUND
            error.isAlreadyLogged shouldBe true
        }

        mapOf(
            DenialReason.MISSING_ORGANIZATION_ACCESS to Pair("User lacks access to organization: 910000001", ErrorType.MISSING_ORG_ACCESS),
            DenialReason.MISSING_RESOURCE_ACCESS to Pair(
                "User lacks access to required Altinn resource for organization: 910000001",
                ErrorType.MISSING_ALITINN_RESOURCE_ACCESS,
            ),
            DenialReason.SYSTEM_USER_REJECTED to Pair(
                "System user does not have access to $OPPGI_NARMESTELEDER_RESOURCE resource",
                ErrorType.MISSING_ALITINN_RESOURCE_ACCESS,
            ),
        ).forEach { (reason, expected) ->
            test("denial $reason retains PUT and legacy 403 contract") {
                val error = shouldThrow<ApiErrorException.ForbiddenException> {
                    GetNarmestelederbehovResult.AccessDenied(reason, OrganizationNumber("910000001")).toLinemanagerRequirementRead()
                }
                error.errorMessage shouldBe expected.first
                error.type shouldBe expected.second
                error.isAlreadyLogged shouldBe true
            }
        }

        test("missing person maps to 500") {
            val error = shouldThrow<ApiErrorException.InternalServerErrorException> {
                GetNarmestelederbehovResult.PersonNotFound.toLinemanagerRequirementRead()
            }
            error.errorMessage shouldBe "Something went wrong while fetching LinemanagerRequirement"
        }
    })
