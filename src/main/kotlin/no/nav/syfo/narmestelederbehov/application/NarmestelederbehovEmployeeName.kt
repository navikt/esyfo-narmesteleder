package no.nav.syfo.narmestelederbehov.application

class NarmestelederbehovEmployeeName(
    private val repository: NarmestelederbehovRepository,
    private val employeeNameLookup: EmployeeNameLookup,
) {
    suspend fun resolve(behov: NarmestelederbehovDetails): BehovPersonName? {
        if (behov.firstName != null && behov.lastName != null) {
            return BehovPersonName(
                firstName = behov.firstName,
                middleName = behov.middleName,
                lastName = behov.lastName,
            )
        }
        val name = employeeNameLookup.find(behov.employeeIdent) ?: return null
        return name.also { repository.saveEmployeeName(id = behov.id, name = it) }
    }
}
