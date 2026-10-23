package no.nav.syfo.organisasjonstilgang.api

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.http.HttpStatusCode
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.platform.api.ErrorType

class DenialReasonHttpMappingTest :
    FunSpec({
        val organization = OrganizationNumber("123456789")

        listOf(
            DenialCase(DenialReason.MISSING_ORGANIZATION_ACCESS, ErrorType.MISSING_ORG_ACCESS, "User lacks access to organization: 123456789", false),
            DenialCase(
                DenialReason.MISSING_RESOURCE_ACCESS,
                ErrorType.MISSING_ALITINN_RESOURCE_ACCESS,
                "User lacks access to required Altinn resource for organization: 123456789",
                false,
            ),
            DenialCase(
                DenialReason.SYSTEM_USER_REJECTED,
                ErrorType.MISSING_ALITINN_RESOURCE_ACCESS,
                "System user does not have access to nav_syfo_oppgi-narmesteleder resource",
                true,
            ),
        ).forEach { case ->
            test("maps ${case.reason} to 403 with its error type and message") {
                val error = case.reason.toForbiddenException(organization)
                val response = error.toApiError("/api/v1/linemanager")
                response.status shouldBe HttpStatusCode.Forbidden
                response.type shouldBe case.type
                response.message shouldBe case.message
                error.isAlreadyLogged shouldBe case.alreadyLogged
            }
        }
    })

private data class DenialCase(
    val reason: DenialReason,
    val type: ErrorType,
    val message: String,
    val alreadyLogged: Boolean,
)
