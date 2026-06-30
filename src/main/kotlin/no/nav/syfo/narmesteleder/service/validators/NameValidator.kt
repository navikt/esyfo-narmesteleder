package no.nav.syfo.narmesteleder.service.validators

import io.micrometer.core.instrument.Counter
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.metric.METRICS_NS
import no.nav.syfo.application.metric.METRICS_REGISTRY
import no.nav.syfo.narmesteleder.domain.Linemanager
import no.nav.syfo.narmesteleder.domain.LinemanagerRevoke
import no.nav.syfo.pdl.Person

private const val PARALLEL_NAMES_VALIDATED_TOTAL =
    "${METRICS_NS}_parallel_names_validated_total"

private val COUNT_PARALLEL_NAMES_VALIDATED: Counter = Counter
    .builder(PARALLEL_NAMES_VALIDATED_TOTAL)
    .description("Counts number of times parallel names have been successfully validated.")
    .register(METRICS_REGISTRY)

private const val EMPLOYEE_NAME_VALIDATION_FAILED_MESSAGE =
    "Last name for employee on sick leave does not correspond with registered value for the given national identification number"
private const val LINEMANAGER_NAME_VALIDATION_FAILED_MESSAGE =
    "Last name for linemanager does not correspond with registered value for the given national identification number"

object NameValidator {
    fun validateLinemanagerLastName(
        managerPdlPerson: Person,
        linemanager: Linemanager,
    ) {
        nlrequire(
            managerPdlPerson.name.etternavn.normalizeName() == linemanager.manager.lastName.normalizeName(),
            type = ErrorType.LINEMANAGER_NAME_NATIONAL_IDENTIFICATION_NUMBER_MISMATCH,
        ) {
            LINEMANAGER_NAME_VALIDATION_FAILED_MESSAGE
        }
    }

    fun validateEmployeeLastName(
        employeePdlPerson: Person,
        linemanager: Linemanager,
    ) {
        // Under visse omstendigheter kan det forekomme at personer har parallelle navn i PDL
        // https://pdl-docs.ansatt.nav.no/ekstern/index.html#_navn
        if (employeePdlPerson.hasParallelNames) {
            val matchesPdlNames = validateParallelNames(
                linemanager.lastName.normalizeName(),
                employeePdlPerson.names.map { it.etternavn.normalizeName() }
            )

            nlrequire(
                value = matchesPdlNames,
                type = ErrorType.EMPLOYEE_NAME_NATIONAL_IDENTIFICATION_NUMBER_MISMATCH
            ) {
                EMPLOYEE_NAME_VALIDATION_FAILED_MESSAGE
            }
            COUNT_PARALLEL_NAMES_VALIDATED.increment()
        } else {
            nlrequire(
                value = linemanager.lastName.normalizeName() == employeePdlPerson.name.etternavn.normalizeName(),
                type = ErrorType.EMPLOYEE_NAME_NATIONAL_IDENTIFICATION_NUMBER_MISMATCH,
            ) {
                EMPLOYEE_NAME_VALIDATION_FAILED_MESSAGE
            }
        }
    }

    fun validateEmployeeLastName(
        managerPdlPerson: Person,
        linemanagerRevoke: LinemanagerRevoke,
    ) {
        nlrequire(
            managerPdlPerson.name.etternavn.normalizeName() == linemanagerRevoke.lastName.normalizeName(),
            type = ErrorType.EMPLOYEE_NAME_NATIONAL_IDENTIFICATION_NUMBER_MISMATCH,
        ) {
            EMPLOYEE_NAME_VALIDATION_FAILED_MESSAGE
        }
    }

    private fun String.normalizeName() = this.trim().uppercase().replace("\\s+".toRegex(), " ")
    private fun validateParallelNames(nameToValidate: String, parallelNames: List<String>): Boolean = parallelNames.any { it.equals(nameToValidate, ignoreCase = true) }
}
