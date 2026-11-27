package no.nav.syfo.organisasjonstilgang.infrastructure

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.organisasjonstilgang.application.AccessToken
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.FakeOrganizationAccess
import no.nav.syfo.organisasjonstilgang.application.McpActingAs
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject

class McpDevOrganizationAccessTest :
    FunSpec({
        val organization = OrganizationNumber("123456789")

        McpActingAs.entries.forEach { actingAs ->
            test("grants MCP acting as $actingAs without calling the delegate") {
                val delegate = FakeOrganizationAccess(organization, OrganizationAccessResult.Denied(DenialReason.MISSING_ORGANIZATION_ACCESS))
                val access = McpDevOrganizationAccess(delegate)

                access.evaluate(OrganizationAccessSubject.McpDevTool(actingAs), organization) shouldBe OrganizationAccessResult.Granted(null)
                delegate.calls shouldBe emptyList()
            }
        }

        listOf(
            OrganizationAccessSubject.LpsSystemUser("system-user", organization),
            OrganizationAccessSubject.PersonnelManager(PersonalIdentificationNumber("12345678901"), AccessToken("test-token")),
        ).forEach { subject ->
            test("delegates ${subject::class.simpleName} unchanged") {
                val denial = OrganizationAccessResult.Denied(DenialReason.MISSING_RESOURCE_ACCESS)
                val delegate = FakeOrganizationAccess(organization, denial)
                val access = McpDevOrganizationAccess(delegate)

                access.evaluate(subject, organization) shouldBe denial
                delegate.calls shouldBe listOf(subject to organization)
            }
        }
    })
