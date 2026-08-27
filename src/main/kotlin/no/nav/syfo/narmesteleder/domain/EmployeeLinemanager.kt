package no.nav.syfo.narmesteleder.domain

import java.time.Instant
import java.util.UUID

data class EmployeeLinemanagerCollection(
    val linemanagers: List<EmployeeLinemanagerRead>,
)

data class EmployeeLinemanagerLookupResult(
    val linemanagers: List<EmployeeLinemanagerRead>,
    val discardedEmailAddressCount: Int,
)

/**
 * Personal identification numbers are intentionally omitted to minimize data exposure. See issue #474.
 */
data class EmployeeLinemanagerRead(
    val id: UUID,
    val orgNumber: OrganizationNumber,
    val activeFrom: Instant,
    val name: Name?,
    val emailAddresses: List<String>,
    val mobile: String,
)

data class EmployeeLinemanagerQuery(
    val employeeNationalIdentificationNumber: PersonalIdentificationNumber,
    val orgNumber: OrganizationNumber? = null,
)
