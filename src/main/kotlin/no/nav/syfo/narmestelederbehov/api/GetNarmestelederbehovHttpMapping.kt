package no.nav.syfo.narmestelederbehov.api

import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.narmesteleder.domain.LineManagerRequirementStatus
import no.nav.syfo.narmesteleder.domain.LinemanagerRequirementRead
import no.nav.syfo.narmesteleder.domain.Name
import no.nav.syfo.narmesteleder.domain.OrganizationNumber
import no.nav.syfo.narmesteleder.domain.PersonalIdentificationNumber
import no.nav.syfo.narmesteleder.domain.RevokedBy
import no.nav.syfo.narmestelederbehov.application.GetNarmestelederbehovResult

fun GetNarmestelederbehovResult.toLinemanagerRequirementRead(): LinemanagerRequirementRead = when (this) {
    is GetNarmestelederbehovResult.Found -> LinemanagerRequirementRead(
        id = behov.id.value,
        employeeIdentificationNumber = PersonalIdentificationNumber(behov.employeeIdent.value),
        orgNumber = OrganizationNumber(behov.organizationNumber.value),
        orgName = organizationName,
        mainOrgNumber = OrganizationNumber(behov.mainOrganizationNumber),
        managerIdentificationNumber = behov.managerIdent?.let { PersonalIdentificationNumber(it.value) },
        name = Name(firstName = name.firstName, lastName = name.lastName, middleName = name.middleName),
        created = behov.created,
        updated = behov.updated,
        status = LineManagerRequirementStatus.from(behov.status),
        revokedBy = RevokedBy.from(behov.reason),
    )
    GetNarmestelederbehovResult.NotFound ->
        throw ApiErrorException.NotFoundException(errorMessage = "LinemanagerRequirement", isAlreadyLogged = true)
    is GetNarmestelederbehovResult.AccessDenied -> throw accessDeniedException(reason = reason, organizationNumber = organizationNumber)
    GetNarmestelederbehovResult.PersonNotFound ->
        throw ApiErrorException.InternalServerErrorException(errorMessage = "Something went wrong while fetching LinemanagerRequirement")
}
