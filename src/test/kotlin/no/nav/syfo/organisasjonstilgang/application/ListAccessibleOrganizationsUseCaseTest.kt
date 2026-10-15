package no.nav.syfo.organisasjonstilgang.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.platform.upstream.UpstreamError
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName

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
            val unavailable = ListAccessibleOrganizationsResult.Unavailable(
                UpstreamFailure(UpstreamName("arbeidsgiver-altinn-tilganger"), UpstreamFailureStage.RESPONSE, 200, errors = listOf(UpstreamError("is_error")))
            )
            val lookup = FakeAccessibleOrganizationsLookup(unavailable)

            ListAccessibleOrganizationsUseCase(lookup).execute(subject) shouldBe unavailable
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
