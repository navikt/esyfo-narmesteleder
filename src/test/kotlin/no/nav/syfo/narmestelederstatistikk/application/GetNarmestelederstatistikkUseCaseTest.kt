package no.nav.syfo.narmestelederstatistikk.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.FakeOrganizationAccess
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName

class GetNarmestelederstatistikkUseCaseTest :
    FunSpec({
        test("unavailable access stops before counting") {
            val effects = mutableListOf<String>()
            val failure = UpstreamFailure(UpstreamName("arbeidsgiver-altinn-tilganger"), UpstreamFailureStage.RESPONSE, 503, IllegalStateException())
            val access = FakeOrganizationAccess(OrganizationAccessResult.Unavailable(failure), effects)
            val repository = StatisticsRepository(effects)
            val result = GetNarmestelederstatistikkUseCase(access, repository).execute(organizationNumber, subject)
            result shouldBe GetNarmestelederstatistikkResult.UpstreamUnavailable(failure)
            result.shouldBeInstanceOf<GetNarmestelederstatistikkResult.UpstreamUnavailable>().failure shouldBeSameInstanceAs failure
            access.calls shouldBe listOf(subject to organizationNumber)
            repository.organizations shouldBe emptyList()
            effects shouldBe listOf("access")
        }

        DenialReason.entries.forEach { reason ->
            test("returns AccessDenied without counting when access is denied with $reason") {
                val effects = mutableListOf<String>()
                val access = FakeOrganizationAccess(OrganizationAccessResult.Denied(reason), effects)
                val repository = StatisticsRepository(effects)
                val useCase = GetNarmestelederstatistikkUseCase(access, repository)

                useCase.execute(organizationNumber, subject) shouldBe GetNarmestelederstatistikkResult.AccessDenied(reason, organizationNumber)
                access.calls shouldBe listOf(subject to organizationNumber)
                repository.organizations shouldBe emptyList()
                effects shouldBe listOf("access")
            }
        }

        test("returns Found after evaluating the supplied subject and organization before counting") {
            val effects = mutableListOf<String>()
            val access = FakeOrganizationAccess(OrganizationAccessResult.Granted(null), effects)
            val repository = StatisticsRepository(effects)
            val useCase = GetNarmestelederstatistikkUseCase(access, repository)

            useCase.execute(organizationNumber, subject) shouldBe GetNarmestelederstatistikkResult.Found(Narmestelederstatistikk(1, 2, 3))
            access.calls shouldBe listOf(subject to organizationNumber)
            repository.organizations shouldBe listOf(organizationNumber)
            effects shouldBe listOf("access", "count")
        }
    })

private val organizationNumber = OrganizationNumber("910000001")
private val subject = OrganizationAccessSubject.LpsSystemUser("synthetic-system-user", OrganizationNumber("910000002"))

private class StatisticsRepository(private val effects: MutableList<String>) : NarmestelederstatistikkRepository {
    val organizations = mutableListOf<OrganizationNumber>()

    override suspend fun countFor(organizationNumber: OrganizationNumber): Narmestelederstatistikk {
        organizations += organizationNumber
        effects += "count"
        return Narmestelederstatistikk(1, 2, 3)
    }
}
