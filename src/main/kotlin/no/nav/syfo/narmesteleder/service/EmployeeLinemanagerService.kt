package no.nav.syfo.narmesteleder.service

import no.nav.syfo.narmesteleder.domain.EmployeeLinemanagerCollection
import no.nav.syfo.narmesteleder.domain.EmployeeLinemanagerQuery
import no.nav.syfo.narmesteleder.domain.OrganizationNumber
import no.nav.syfo.narmesteleder.domain.PersonalIdentificationNumber
import no.nav.syfo.narmesteleder.exposed.EmployeeLinemanagerRepository

/**
 * Looks up active linemanagers for an employee.
 */
class EmployeeLinemanagerService(
    private val repository: EmployeeLinemanagerRepository,
) {
    suspend fun findActiveLinemanagersForEmployee(
        employee: PersonalIdentificationNumber,
        orgNumber: OrganizationNumber?,
    ): EmployeeLinemanagerCollection {
        val lookupResult = repository.findActiveForEmployee(
            EmployeeLinemanagerQuery(
                employeeNationalIdentificationNumber = employee,
                orgNumber = orgNumber,
            ),
        )
        countDiscardedEmployeeLinemanagerEmailAddresses(lookupResult.discardedEmailAddressCount)
        return EmployeeLinemanagerCollection(lookupResult.linemanagers)
    }
}
