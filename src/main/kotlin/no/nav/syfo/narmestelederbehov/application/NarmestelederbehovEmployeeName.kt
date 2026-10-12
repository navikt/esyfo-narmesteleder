package no.nav.syfo.narmestelederbehov.application

class NarmestelederbehovEmployeeName(
    private val repository: NarmestelederbehovRepository,
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
