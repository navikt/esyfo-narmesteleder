package no.nav.syfo.narmestelederrelasjon.api.model

import no.nav.syfo.narmestelederrelasjon.application.EmployeeNarmesteleder
import java.time.Instant
import java.util.UUID

data class EmployeeLinemanagersResponse(
    val linemanagers: List<EmployeeLinemanagerResponse>,
)

/**
 * Personal identification numbers are intentionally omitted to minimize data exposure. See issue #474.
 */
data class EmployeeLinemanagerResponse(
    val id: UUID,
    val orgNumber: String,
    val activeFrom: Instant,
    val name: Name?,
    val emailAddresses: List<String>,
    val mobile: String,
) {
    data class Name(
        val firstName: String,
        val lastName: String,
        val middleName: String?,
    )
}

fun List<EmployeeNarmesteleder>.toResponse(): EmployeeLinemanagersResponse = EmployeeLinemanagersResponse(
    linemanagers = map { manager ->
        EmployeeLinemanagerResponse(
            id = manager.id,
            orgNumber = manager.organizationNumber.value,
            activeFrom = manager.activeFrom,
            name = manager.name?.let { EmployeeLinemanagerResponse.Name(it.firstName, it.lastName, it.middleName) },
            emailAddresses = manager.emailAddresses.map { it.value },
            mobile = manager.mobile,
        )
    },
)
