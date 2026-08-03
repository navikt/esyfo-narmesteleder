package no.nav.syfo.narmesteleder.service.validators

import io.micrometer.core.instrument.Counter
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.metric.METRICS_NS
import no.nav.syfo.application.metric.METRICS_REGISTRY
import no.nav.syfo.narmesteleder.domain.Linemanager
import no.nav.syfo.narmesteleder.domain.LinemanagerRevoke
import no.nav.syfo.pdl.Person

private const val PARALLEL_NAMES_VALIDATION_TOTAL =
    "${METRICS_NS}_parallel_names_validation_total"
private const val PARALLEL_NAMES_VALIDATION_DESCRIPTION =
    "Counts parallel names validation attempts and outcomes."
private const val RESULT_TAG = "result"
private const val RESULT_ATTEMPTED = "attempted"
private const val RESULT_SUCCESS = "success"
private const val RESULT_FAILED = "failed"

private const val EMPLOYEE_NAME_VALIDATION_FAILED_MESSAGE =
    "Last name for employee on sick leave does not correspond with registered value for the given national identification number"
private const val LINEMANAGER_NAME_VALIDATION_FAILED_MESSAGE =
    "Last name for linemanager does not correspond with registered value for the given national identification number"

object NameValidator {
    private val parallelNamesValidationCounters: Map<String, Counter> = listOf(
        RESULT_ATTEMPTED,
        RESULT_SUCCESS,
        RESULT_FAILED,
    ).associateWith { result ->
        Counter.builder(PARALLEL_NAMES_VALIDATION_TOTAL)
            .description(PARALLEL_NAMES_VALIDATION_DESCRIPTION)
            .tag(RESULT_TAG, result)
            .register(METRICS_REGISTRY)
    }

    fun validateLinemanagerLastName(
        managerPdlPerson: Person,
        linemanager: Linemanager,
    ) {
        nlrequire(
            validateLastName(linemanager.manager.lastName, managerPdlPerson),
            type = ErrorType.LINEMANAGER_NAME_NATIONAL_IDENTIFICATION_NUMBER_MISMATCH,
        ) {
            LINEMANAGER_NAME_VALIDATION_FAILED_MESSAGE
        }
    }

    fun validateEmployeeLastName(
        employeePdlPerson: Person,
        linemanager: Linemanager,
    ) {
        nlrequire(
            validateLastName(linemanager.lastName, employeePdlPerson),
            type = ErrorType.EMPLOYEE_NAME_NATIONAL_IDENTIFICATION_NUMBER_MISMATCH,
        ) {
            EMPLOYEE_NAME_VALIDATION_FAILED_MESSAGE
        }
    }

    fun validateEmployeeLastName(
        managerPdlPerson: Person,
        linemanagerRevoke: LinemanagerRevoke,
    ) {
        nlrequire(
            validateLastName(linemanagerRevoke.lastName, managerPdlPerson),
            type = ErrorType.EMPLOYEE_NAME_NATIONAL_IDENTIFICATION_NUMBER_MISMATCH,
        ) {
            EMPLOYEE_NAME_VALIDATION_FAILED_MESSAGE
        }
    }

    private fun validateLastName(nameToValidate: String, pdlPerson: Person): Boolean = if (pdlPerson.hasParallelNames) {
        countParallelNamesValidation(result = RESULT_ATTEMPTED)
        val matchesPdlNames = validateParallelNames(
            nameToValidate.normalizeName(),
            pdlPerson.names.map { it.etternavn.normalizeName() }
        )

        if (matchesPdlNames) {
            countParallelNamesValidation(result = RESULT_SUCCESS)
        } else {
            countParallelNamesValidation(result = RESULT_FAILED)
        }

        matchesPdlNames
    } else {
        nameToValidate.normalizeName() == pdlPerson.name.etternavn.normalizeName()
    }

    private fun String.normalizeName() = this.trim().uppercase().replace("\\s+".toRegex(), " ")

    // Under visse omstendigheter kan det forekomme at personer har parallelle navn i PDL
    // https://pdl-docs.ansatt.nav.no/ekstern/index.html#_navn
    private fun validateParallelNames(nameToValidate: String, parallelNames: List<String>): Boolean = parallelNames.any { it.equals(nameToValidate, ignoreCase = true) }

    private fun countParallelNamesValidation(result: String) {
        parallelNamesValidationCounters.getValue(result).increment()
    }
}
