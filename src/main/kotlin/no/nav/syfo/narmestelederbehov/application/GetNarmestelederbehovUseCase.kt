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
        val behov = repository.findDetails(id) ?: return GetNarmestelederbehovResult.NotFound.log()
        // Legacy parity: the name is resolved and persisted before access is checked. Redesign tracked in #602.
        val name = if (behov.firstName != null && behov.lastName != null) {
            BehovPersonName(
                firstName = behov.firstName,
                middleName = behov.middleName,
                lastName = behov.lastName,
            )
        } else {
            val details = personLookup.find(behov.employeeIdent) ?: return GetNarmestelederbehovResult.PersonNotFound.log()
            BehovPersonName(
                firstName = details.name.firstName,
                middleName = details.name.middleName,
                lastName = details.name.lastName,
            ).also { repository.saveEmployeeName(id = behov.id, name = it) }
        }
        val organizationName = when (val access = organizationAccess.evaluate(subject = subject, organizationNumber = behov.organizationNumber)) {
            is OrganizationAccessResult.Denied ->
                return GetNarmestelederbehovResult.AccessDenied(reason = access.reason, organizationNumber = behov.organizationNumber).log()
            is OrganizationAccessResult.Granted -> access.organizationName
        }
        return GetNarmestelederbehovResult.Found(behov = behov, name = name, organizationName = organizationName)
    }

    private fun <T : GetNarmestelederbehovResult> T.log(): T = also { logger.event(getNarmestelederbehovRejected, it) }

    private companion object {
        val logger = applicationLogger(GetNarmestelederbehovUseCase::class.java)
    }
}

data class BehovPersonName(val firstName: String, val middleName: String?, val lastName: String)

sealed interface GetNarmestelederbehovResult {
    data class Found(val behov: NarmestelederbehovDetails, val name: BehovPersonName, val organizationName: String?) : GetNarmestelederbehovResult
    data object NotFound : GetNarmestelederbehovResult
    data class AccessDenied(val reason: DenialReason, val organizationNumber: OrganizationNumber) : GetNarmestelederbehovResult
    data object PersonNotFound : GetNarmestelederbehovResult
}
