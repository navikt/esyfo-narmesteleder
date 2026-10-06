package no.nav.syfo.narmestelederstatistikk.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccess
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject

class GetNarmestelederstatistikkUseCaseTest :
    FunSpec({
        DenialReason.entries.forEach { reason ->
            test("returns AccessDenied without counting when access is denied with $reason") {
                val effects = mutableListOf<String>()
                val access = StatisticsOrganizationAccess(OrganizationAccessResult.Denied(reason), effects)
                val repository = StatisticsRepository(effects)
                val useCase = GetNarmestelederstatistikkUseCase(access, repository)

                useCase.execute(organizationNumber, subject) shouldBe GetNarmestelederstatistikkResult.AccessDenied(reason, organizationNumber)
                access.evaluations shouldBe listOf(subject to organizationNumber)
                repository.organizations shouldBe emptyList()
                effects shouldBe listOf("access")
            }
        }

        test("returns Found after evaluating the supplied subject and organization before counting") {
            val effects = mutableListOf<String>()
            val access = StatisticsOrganizationAccess(OrganizationAccessResult.Granted(null), effects)
            val repository = StatisticsRepository(effects)
            val useCase = GetNarmestelederstatistikkUseCase(access, repository)

            useCase.execute(organizationNumber, subject) shouldBe GetNarmestelederstatistikkResult.Found(Narmestelederstatistikk(1, 2, 3))
            access.evaluations shouldBe listOf(subject to organizationNumber)
            repository.organizations shouldBe listOf(organizationNumber)
            effects shouldBe listOf("access", "count")
        }
    })

private val organizationNumber = OrganizationNumber("910000001")
private val subject = OrganizationAccessSubject.LpsSystemUser("synthetic-system-user", OrganizationNumber("910000002"))

private class StatisticsOrganizationAccess(
    private val result: OrganizationAccessResult,
    private val effects: MutableList<String>,
) : OrganizationAccess {
    val evaluations = mutableListOf<Pair<OrganizationAccessSubject, OrganizationNumber>>()

    override suspend fun evaluate(subject: OrganizationAccessSubject, organizationNumber: OrganizationNumber): OrganizationAccessResult {
        evaluations += subject to organizationNumber
        effects += "access"
        return result
    }
}

private class StatisticsRepository(private val effects: MutableList<String>) : NarmestelederstatistikkRepository {
    val organizations = mutableListOf<OrganizationNumber>()

    override suspend fun countFor(organizationNumber: OrganizationNumber): Narmestelederstatistikk {
        organizations += organizationNumber
        effects += "count"
        return Narmestelederstatistikk(1, 2, 3)
    }
}
