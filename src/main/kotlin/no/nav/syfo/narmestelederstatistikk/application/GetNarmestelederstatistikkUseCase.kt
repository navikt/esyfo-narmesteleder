package no.nav.syfo.narmestelederstatistikk.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccess
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject
import no.nav.syfo.platform.application.Step
import no.nav.syfo.platform.application.orStop
import no.nav.syfo.platform.upstream.UpstreamFailure

sealed interface GetNarmestelederstatistikkResult {
    data class UpstreamUnavailable(val failure: UpstreamFailure) : GetNarmestelederstatistikkResult

    data class Found(val statistikk: Narmestelederstatistikk) : GetNarmestelederstatistikkResult

    data class AccessDenied(val reason: DenialReason, val organizationNumber: OrganizationNumber) : GetNarmestelederstatistikkResult
}

fun interface NarmestelederstatistikkRepository {
    suspend fun countFor(organizationNumber: OrganizationNumber): Narmestelederstatistikk
}

class GetNarmestelederstatistikkUseCase(
    private val organizationAccess: OrganizationAccess,
    private val repository: NarmestelederstatistikkRepository,
) {
    suspend fun execute(
        organizationNumber: OrganizationNumber,
        subject: OrganizationAccessSubject,
    ): GetNarmestelederstatistikkResult {
        requireAccess(subject, organizationNumber).orStop { return it }
        return GetNarmestelederstatistikkResult.Found(repository.countFor(organizationNumber))
    }

    private suspend fun requireAccess(
        subject: OrganizationAccessSubject,
        organizationNumber: OrganizationNumber,
    ): GetNarmestelederstatistikkStep<Unit> = when (val access = organizationAccess.evaluate(subject, organizationNumber)) {
        is OrganizationAccessResult.Granted -> Step.Proceed
        is OrganizationAccessResult.Unavailable -> Step.Stop(GetNarmestelederstatistikkResult.UpstreamUnavailable(access.failure))
        is OrganizationAccessResult.Denied -> Step.Stop(GetNarmestelederstatistikkResult.AccessDenied(access.reason, organizationNumber))
    }
}

private typealias GetNarmestelederstatistikkStep<T> = Step<T, GetNarmestelederstatistikkResult>
