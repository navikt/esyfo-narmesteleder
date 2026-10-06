package no.nav.syfo.organisasjonstilgang.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.ident.PersonIdent

class ListAccessibleOrganizationsUseCaseTest :
    FunSpec({
        val subject = OrganizationAccessSubject.PersonnelManager(PersonIdent("12345678901"), AccessToken("test-token"))

        test("passes through listed organizations and forwards the subject") {
            val listed = ListAccessibleOrganizationsResult.Listed(
                listOf(AccessibleOrganization("123456789", "Test Org", emptyList())),
            )
            val lookup = FakeAccessibleOrganizationsLookup(listed)

            ListAccessibleOrganizationsUseCase(lookup).execute(subject) shouldBe listed
            lookup.subject shouldBe subject
        }

        test("passes through unavailable and forwards the subject") {
            val lookup = FakeAccessibleOrganizationsLookup(ListAccessibleOrganizationsResult.Unavailable)

            ListAccessibleOrganizationsUseCase(lookup).execute(subject) shouldBe ListAccessibleOrganizationsResult.Unavailable
            lookup.subject shouldBe subject
        }
    })

private class FakeAccessibleOrganizationsLookup(
    private val result: ListAccessibleOrganizationsResult,
) : AccessibleOrganizationsLookup {
    var subject: OrganizationAccessSubject.PersonnelManager? = null
        private set

    override suspend fun find(subject: OrganizationAccessSubject.PersonnelManager): ListAccessibleOrganizationsResult {
        this.subject = subject
        return result
    }
}
