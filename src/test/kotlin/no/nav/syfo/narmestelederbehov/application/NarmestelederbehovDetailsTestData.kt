package no.nav.syfo.narmestelederbehov.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmestelederbehov.domain.BehovReason
import no.nav.syfo.narmestelederbehov.domain.BehovStatus
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import java.time.Instant
import java.util.UUID

internal fun narmestelederbehovDetails(
    id: NarmestelederbehovId = NarmestelederbehovId(UUID.randomUUID()),
    employeeIdent: PersonalIdentificationNumber = PersonalIdentificationNumber("12345678901"),
    organizationNumber: OrganizationNumber = OrganizationNumber("910000001"),
    mainOrganizationNumber: String = "910000002",
    managerIdent: PersonalIdentificationNumber? = null,
    firstName: String? = "Stored",
    middleName: String? = "Middle",
    lastName: String? = "Name",
    created: Instant = Instant.EPOCH,
    updated: Instant = Instant.EPOCH,
    status: BehovStatus = BehovStatus.BEHOV_CREATED,
    reason: BehovReason = BehovReason.NY_LEDER,
) = NarmestelederbehovDetails(
    id = id,
    employeeIdent = employeeIdent,
    organizationNumber = organizationNumber,
    mainOrganizationNumber = mainOrganizationNumber,
    managerIdent = managerIdent,
    firstName = firstName,
    middleName = middleName,
    lastName = lastName,
    created = created,
    updated = updated,
    status = status,
    reason = reason,
)
