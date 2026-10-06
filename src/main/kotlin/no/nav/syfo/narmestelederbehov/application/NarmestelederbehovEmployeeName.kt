package no.nav.syfo.narmestelederbehov.application

import no.nav.syfo.narmestelederrelasjon.application.PersonLookup

class NarmestelederbehovEmployeeName(
    private val repository: NarmestelederbehovRepository,
    private val personLookup: PersonLookup,
) {
    suspend fun resolve(behov: NarmestelederbehovDetails): BehovPersonName? {
        if (behov.firstName != null && behov.lastName != null) {
            return BehovPersonName(
                firstName = behov.firstName,
                middleName = behov.middleName,
                lastName = behov.lastName,
            )
        }
        val details = personLookup.find(behov.employeeIdent) ?: return null
        return BehovPersonName(
            firstName = details.name.firstName,
            middleName = details.name.middleName,
            lastName = details.name.lastName,
        ).also { repository.saveEmployeeName(id = behov.id, name = it) }
    }
}
