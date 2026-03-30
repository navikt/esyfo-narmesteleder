package no.nav.syfo.narmesteleder.service.validators

import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.narmesteleder.domain.Linemanager
import no.nav.syfo.narmesteleder.domain.LinemanagerRevoke
import no.nav.syfo.pdl.Person

object NameValidator {
    fun validateLinemanagerLastName(
        managerPdlPerson: Person,
        linemanager: Linemanager,
    ) {
        nlrequire(
            managerPdlPerson.name.etternavn.uppercase() == linemanager.manager.lastName.uppercase(),
            type = ErrorType.LINEMANAGER_NAME_NATIONAL_IDENTIFICATION_NUMBER_MISMATCH,
        ) {
            "Last name for linemanager does not correspond with registered value for the given national identification number"
        }
    }

    fun validateEmployeeLastName(
        managerPdlPerson: Person,
        linemanager: Linemanager,
    ) {
        nlrequire(
            managerPdlPerson.name.etternavn.uppercase() == linemanager.lastName.uppercase(),
            type = ErrorType.EMPLOYEE_NAME_NATIONAL_IDENTIFICATION_NUMBER_MISMATCH,
        ) {
            "Last name for employee on sick leave does not correspond with registered value for the given national identification number"
        }
    }

    fun validateEmployeeLastName(
        managerPdlPerson: Person,
        linemanagerRevoke: LinemanagerRevoke,
    ) {
        nlrequire(
            managerPdlPerson.name.etternavn.uppercase() == linemanagerRevoke.lastName.uppercase(),
            type = ErrorType.EMPLOYEE_NAME_NATIONAL_IDENTIFICATION_NUMBER_MISMATCH,
        ) {
            "Last name for employee on sick leave does not correspond with registered value for the given national identification number"
        }
    }
}
