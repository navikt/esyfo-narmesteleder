package no.nav.syfo.narmesteleder.service

import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.auth.SystemPrincipal
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.narmesteleder.domain.Linemanager
import no.nav.syfo.narmesteleder.domain.LinemanagerRevoke
import no.nav.syfo.pdl.Person

fun nlrequire(value: Boolean, type: ErrorType, lazyMessage: () -> String) {
    if (!value) throw ApiErrorException.BadRequestException(type = type, errorMessage = lazyMessage())
}

private fun nlrequireOrForbidden(value: Boolean, type: ErrorType, lazyMessage: () -> String) {
    if (!value) throw ApiErrorException.ForbiddenException(lazyMessage(), type = type)
}

fun validateLinemanagerLastName(
    managerPdlPerson: Person,
    linemanager: Linemanager,
) {
    if (managerPdlPerson.name.etternavn.uppercase() != linemanager.manager.lastName.uppercase()) {
        throw ApiErrorException.BadRequestException(
            "Last name for linemanager does not correspond with registered value for the given national identification number",
            type = ErrorType.LINEMANAGER_NAME_NATIONAL_IDENTIFICATION_NUMBER_MISMATCH
        )
    }
}

fun validateEmployeeLastName(
    managerPdlPerson: Person,
    linemanager: Linemanager,
) {
    if (managerPdlPerson.name.etternavn.uppercase() != linemanager.lastName.uppercase()) {
        throw ApiErrorException.BadRequestException(
            "Last name for employee on sick leave does not correspond with registered value for the given national identification number",
            type = ErrorType.EMPLOYEE_NAME_NATIONAL_IDENTIFICATION_NUMBER_MISMATCH
        )
    }
}

fun validateEmployeeLastName(
    managerPdlPerson: Person,
    linemanagerRevoke: LinemanagerRevoke,
) {
    if (managerPdlPerson.name.etternavn.uppercase() != linemanagerRevoke.lastName.uppercase()) {
        throw ApiErrorException.BadRequestException(
            "Last name for employee on sick leave does not correspond with registered value for the given national identification number",
            type = ErrorType.EMPLOYEE_NAME_NATIONAL_IDENTIFICATION_NUMBER_MISMATCH
        )
    }
}

fun validateNarmesteLeder(
    sykemeldtOrgNumbers: Map<String, String>,
    narmesteLederOrgNumbers: Map<String, String>,
    systemPrincipal: SystemPrincipal?,
    orgNumberInRequest: String,
) {
    nlrequire(
        sykemeldtOrgNumbers.keys.contains(orgNumberInRequest),
        type = ErrorType.EMPLOYEE_MISSING_EMPLOYMENT_IN_ORG
    ) { "Employee on sick leave is missing employment in any organization" }
    val allSykmeldtOrgNumbers = sykemeldtOrgNumbers.map { listOf(it.key, it.value) }.flatten()
    val allNlOrgNumbers = narmesteLederOrgNumbers.map { listOf(it.key, it.value) }.flatten()

    nlrequire(
        allNlOrgNumbers.any { it in allSykmeldtOrgNumbers },
        type = ErrorType.LINEMANAGER_MISSING_EMPLOYMENT_IN_ORG
    ) { "Linemanager is missing employment in any branch under the parent organization of the employee on sick leave" }
    systemPrincipal?.let {
        nlrequireOrForbidden(
            type = ErrorType.MISSING_ORG_ACCESS,
            value = allSykmeldtOrgNumbers.contains(systemPrincipal.getSystemUserOrgNumber())
        ) { "Systemuser is missing access to the organization" }
    }
}

fun validateNarmesteLederAvkreft(
    sykemeldtOrgNumbers: Map<String, String>,
    orgNumberInRequest: String,
    systemPrincipal: SystemPrincipal?,
) {
    nlrequire(
        sykemeldtOrgNumbers.isNotEmpty(),
        ErrorType.EMPLOYEE_MISSING_EMPLOYMENT_IN_ORG
    ) { "Employee on sick leave is missing employment in any organization" }
    nlrequire(
        sykemeldtOrgNumbers.contains(orgNumberInRequest),
        ErrorType.EMPLOYEE_MISSING_EMPLOYMENT_IN_ORG
    ) { "Employee on sick leave does not have employment in the organization indicated in the request" }
    val allSykmeldtOrgNumbers = sykemeldtOrgNumbers.map { listOf(it.key, it.value) }.flatten()
    systemPrincipal?.let {
        nlrequireOrForbidden(
            type = ErrorType.MISSING_ORG_ACCESS,
            value = allSykmeldtOrgNumbers.contains(systemPrincipal.getSystemUserOrgNumber())
        ) { "Systemuser is missing access to the organization" }
    }
}
