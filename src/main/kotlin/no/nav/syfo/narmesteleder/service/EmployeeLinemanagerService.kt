package no.nav.syfo.narmesteleder.service

import no.nav.syfo.narmesteleder.domain.EmployeeLinemanagerCollection
import no.nav.syfo.narmesteleder.domain.EmployeeLinemanagerQuery
import no.nav.syfo.narmesteleder.domain.OrganizationNumber
import no.nav.syfo.narmesteleder.domain.PersonalIdentificationNumber
import no.nav.syfo.narmesteleder.exposed.IEmployeeLinemanagerRepository

/**
 * Looks up the authenticated employee's active linemanagers.
 *
 * This deliberately does not use [ValidationService] or PrincipalAccessValidator. Authorization is
 * implicit because the personal identification number comes from the `pid` claim in the user's TokenX token,
 * and the database predicate always filters on that number. This intentionally differs from `/search` and
 * `/statistics`, which are employer endpoints and require Altinn access validation.
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
