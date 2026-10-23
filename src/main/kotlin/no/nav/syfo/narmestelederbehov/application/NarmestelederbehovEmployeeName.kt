package no.nav.syfo.narmestelederbehov.application

import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId

fun interface NarmestelederbehovEmployeeNameRepository {
    suspend fun saveEmployeeName(id: NarmestelederbehovId, name: BehovPersonName)
}

class NarmestelederbehovEmployeeName(
    private val repository: NarmestelederbehovEmployeeNameRepository,
    private val employeeNameLookup: EmployeeNameLookup,
) {
    suspend fun resolve(behov: NarmestelederbehovDetails): EmployeeNameLookupResult {
        if (behov.firstName != null && behov.lastName != null) {
            return EmployeeNameLookupResult.Found(
                BehovPersonName(
                    firstName = behov.firstName,
                    middleName = behov.middleName,
                    lastName = behov.lastName,
                ),
            )
        }
        return employeeNameLookup.find(behov.employeeIdent).also {
            if (it is EmployeeNameLookupResult.Found) repository.saveEmployeeName(id = behov.id, name = it.name)
        }
    }
}
