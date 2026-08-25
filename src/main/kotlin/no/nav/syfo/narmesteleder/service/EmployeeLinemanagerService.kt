package no.nav.syfo.narmesteleder.service

import no.nav.syfo.narmesteleder.domain.EmployeeLinemanagerCollection
import no.nav.syfo.narmesteleder.domain.EmployeeLinemanagerQuery
import no.nav.syfo.narmesteleder.domain.OrganizationNumber
import no.nav.syfo.narmesteleder.domain.PersonalIdentificationNumber
import no.nav.syfo.narmesteleder.exposed.IEmployeeLinemanagerRepository

/**
 * Looks up active linemanagers for an employee.
 */
class EmployeeLinemanagerService(
    private val repository: IEmployeeLinemanagerRepository,
) {
    suspend fun findActiveLinemanagersForEmployee(
        employee: PersonalIdentificationNumber,
        orgNumber: OrganizationNumber?,
    ): EmployeeLinemanagerCollection {
        val linemanagers = repository.findActiveForEmployee(
            EmployeeLinemanagerQuery(
                employeeNationalIdentificationNumber = employee,
                orgNumber = orgNumber,
            ),
        )
        return EmployeeLinemanagerCollection(linemanagers)
    }
}
