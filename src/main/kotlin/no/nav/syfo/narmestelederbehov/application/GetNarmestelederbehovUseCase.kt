package no.nav.syfo.narmestelederbehov.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.logging.applicationLogger
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.narmestelederrelasjon.application.PersonLookup
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccess
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject

class GetNarmestelederbehovUseCase(
    private val repository: NarmestelederbehovRepository,
    private val organizationAccess: OrganizationAccess,
    private val personLookup: PersonLookup,
) {
    suspend fun execute(id: NarmestelederbehovId, subject: OrganizationAccessSubject): GetNarmestelederbehovResult {
        val behov = repository.findForRead(id) ?: return GetNarmestelederbehovResult.NotFound.log()
        // Legacy parity: the name is resolved and persisted before access is checked. Redesign tracked in #602.
        val name = if (behov.firstName != null && behov.lastName != null) {
            BehovPersonName(behov.firstName, behov.middleName, behov.lastName)
        } else {
            val details = personLookup.find(behov.employeeIdent) ?: return GetNarmestelederbehovResult.PersonNotFound.log()
            BehovPersonName(details.name.firstName, details.name.middleName, details.name.lastName)
                .also { repository.saveEmployeeName(behov.id, it) }
        }
        val organizationName = when (val access = organizationAccess.evaluate(subject, behov.organizationNumber)) {
            is OrganizationAccessResult.Denied ->
                return GetNarmestelederbehovResult.AccessDenied(access.reason, behov.organizationNumber).log()
            is OrganizationAccessResult.Granted -> access.organizationName
        }
        return GetNarmestelederbehovResult.Found(behov, name, organizationName)
    }

    private fun <T : GetNarmestelederbehovResult> T.log(): T = also { logger.event(getNarmestelederbehovRejected, it) }

    private companion object {
        val logger = applicationLogger(GetNarmestelederbehovUseCase::class.java)
    }
}

data class BehovPersonName(val firstName: String, val middleName: String?, val lastName: String)

sealed interface GetNarmestelederbehovResult {
    data class Found(val behov: NarmestelederbehovRead, val name: BehovPersonName, val organizationName: String?) : GetNarmestelederbehovResult
    data object NotFound : GetNarmestelederbehovResult
    data class AccessDenied(val reason: DenialReason, val organizationNumber: OrganizationNumber) : GetNarmestelederbehovResult
    data object PersonNotFound : GetNarmestelederbehovResult
}
