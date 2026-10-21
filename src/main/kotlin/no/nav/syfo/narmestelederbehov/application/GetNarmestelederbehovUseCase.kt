package no.nav.syfo.narmestelederbehov.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccess
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject
import no.nav.syfo.platform.observability.applicationLogger
import no.nav.syfo.platform.upstream.UpstreamFailure

data class BehovPersonName(val firstName: String, val middleName: String?, val lastName: String)

sealed interface GetNarmestelederbehovResult {
    data class UpstreamUnavailable(val failure: UpstreamFailure) : GetNarmestelederbehovResult
    data class Found(val behov: NarmestelederbehovDetails, val name: BehovPersonName, val organizationName: String?) : GetNarmestelederbehovResult
    data object NotFound : GetNarmestelederbehovResult
    data class AccessDenied(val reason: DenialReason, val organizationNumber: OrganizationNumber) : GetNarmestelederbehovResult
    data object PersonNotFound : GetNarmestelederbehovResult
}

class GetNarmestelederbehovUseCase(
    private val repository: NarmestelederbehovRepository,
    private val organizationAccess: OrganizationAccess,
    private val employeeName: NarmestelederbehovEmployeeName,
) {
    suspend fun execute(id: NarmestelederbehovId, subject: OrganizationAccessSubject): GetNarmestelederbehovResult {
        val behov = repository.findDetails(id) ?: return GetNarmestelederbehovResult.NotFound.log()
        // Legacy parity: the name is resolved and persisted before access is checked. Redesign tracked in #602.
        val name = when (val result = employeeName.resolve(behov)) {
            is EmployeeNameLookupResult.Found -> result.name
            EmployeeNameLookupResult.NotFound -> return GetNarmestelederbehovResult.PersonNotFound.log()
            is EmployeeNameLookupResult.Unavailable -> return GetNarmestelederbehovResult.UpstreamUnavailable(result.failure)
        }
        val organizationName = when (val access = organizationAccess.evaluate(subject = subject, organizationNumber = behov.organizationNumber)) {
            is OrganizationAccessResult.Denied ->
                return GetNarmestelederbehovResult.AccessDenied(reason = access.reason, organizationNumber = behov.organizationNumber).log()

            is OrganizationAccessResult.Granted -> access.organizationName

            is OrganizationAccessResult.Unavailable -> return GetNarmestelederbehovResult.UpstreamUnavailable(access.failure)
        }
        return GetNarmestelederbehovResult.Found(behov = behov, name = name, organizationName = organizationName)
    }

    private fun <T : GetNarmestelederbehovResult> T.log(): T = also { logger.event(getNarmestelederbehovRejected, it) }

    private companion object {
        val logger = applicationLogger(GetNarmestelederbehovUseCase::class.java)
    }
}
