package no.nav.syfo.narmestelederbehov.api

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.narmesteleder.domain.LineManagerRequirementStatus
import no.nav.syfo.narmesteleder.domain.LinemanagerRequirementRead
import no.nav.syfo.narmesteleder.domain.Name
import no.nav.syfo.narmesteleder.domain.RevokedBy
import no.nav.syfo.narmestelederbehov.application.BehovPersonName
import no.nav.syfo.narmestelederbehov.application.GetNarmestelederbehovResult
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovDetails
import no.nav.syfo.platform.api.ApiErrorException
import no.nav.syfo.platform.api.toUpstreamUnavailableException

fun GetNarmestelederbehovResult.toLinemanagerRequirementRead(): LinemanagerRequirementRead = when (this) {
    is GetNarmestelederbehovResult.UpstreamUnavailable -> throw failure.toUpstreamUnavailableException()
    is GetNarmestelederbehovResult.Found -> behov.toLinemanagerRequirementRead(name, organizationName)
    GetNarmestelederbehovResult.NotFound ->
        throw ApiErrorException.NotFoundException(errorMessage = "LinemanagerRequirement", isAlreadyLogged = true)
    is GetNarmestelederbehovResult.AccessDenied -> throw accessDeniedException(reason = reason, organizationNumber = organizationNumber)
    GetNarmestelederbehovResult.PersonNotFound ->
        throw ApiErrorException.InternalServerErrorException(errorMessage = "Something went wrong while fetching LinemanagerRequirement")
}

fun NarmestelederbehovDetails.toLinemanagerRequirementRead(name: BehovPersonName, organizationName: String?): LinemanagerRequirementRead = LinemanagerRequirementRead(
    id = id.value,
    employeeIdentificationNumber = employeeIdent,
    orgNumber = organizationNumber,
    orgName = organizationName,
    mainOrgNumber = OrganizationNumber(mainOrganizationNumber),
    managerIdentificationNumber = managerIdent,
    name = Name(firstName = name.firstName, lastName = name.lastName, middleName = name.middleName),
    created = created,
    updated = updated,
    status = LineManagerRequirementStatus.from(status),
    revokedBy = RevokedBy.from(reason),
)
