package no.nav.syfo.narmesteleder.service

import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.auth.SystemPrincipal
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.narmesteleder.domain.Linemanager
import no.nav.syfo.pdl.Person

class ValidateNarmesteLederException(message: String) : RuntimeException(message)
class ValidateActiveSykmeldingException(message: String) : RuntimeException(message)

fun nlrequire(value: Boolean, lazyMessage: () -> String) {
    if (!value) throw ValidateNarmesteLederException(lazyMessage())
}

private fun nlrequireOrForbidden(value: Boolean, type: ErrorType, lazyMessage: () -> String) {
    if (!value) throw ApiErrorException.ForbiddenException(lazyMessage(), type = type)
}

fun validateLinemanagerLastName(
    managerPdlPerson: Person,
    linemanager: Linemanager,
) {
    if (managerPdlPerson.name.etternavn.uppercase() != linemanager.manager.lastName.uppercase()) throw ApiErrorException.BadRequestException(
        "Last name for linemanager does not correspond with registered value for the given national identification number",
        type = ErrorType.BAD_REQUEST_NAME_NIN_MISMATCH_LINEMANAGER
    )
}

fun validateNarmesteLeder(
    sykemeldtOrgNumbers: Map<String, String>,
    narmesteLederOrgNumbers: Map<String, String>,
    systemPrincipal: SystemPrincipal?,
    orgNumberInRequest: String,
) {

    nlrequire(sykemeldtOrgNumbers.keys.contains(orgNumberInRequest)) { "Ingen arbeidsforhold for sykemeldt for angitt virksomhet" }
    val allSykmeldtOrgNumbers = sykemeldtOrgNumbers.map { listOf(it.key, it.value) }.flatten()
    val allNlOrgNumbers = narmesteLederOrgNumbers.map { listOf(it.key, it.value) }.flatten()

    nlrequire(
        allNlOrgNumbers.any { it in allSykmeldtOrgNumbers }
    ) { "Næremeste leder mangler arbeidsforhold i samme organisasjonsstruktur som sykmeldt" }
    systemPrincipal?.let {
        nlrequireOrForbidden(
            type = ErrorType.FORBIDDEN_LACKS_ORG_ACCESS,
            value = allSykmeldtOrgNumbers.contains(systemPrincipal.getSystemUserOrgNumber())
        )
        { "Systembruker har ikke tilgang til virksomhet" }
    }
}

fun validateNarmesteLederAvkreft(
    sykemeldtOrgNumbers: Map<String, String>,
    orgNumberInRequest: String,
    systemPrincipal: SystemPrincipal?,
) {
    nlrequire(sykemeldtOrgNumbers.isNotEmpty()) { "Ingen arbeidsforhold for sykemeldt" }
    nlrequire(sykemeldtOrgNumbers.contains(orgNumberInRequest)) { "Organisasjonsnummer i HTTP request body samsvarer ikke med sykemeldtes organisasjoner" }
    val allSykmeldtOrgNumbers = sykemeldtOrgNumbers.map { listOf(it.key, it.value) }.flatten()
    systemPrincipal?.let {
        nlrequireOrForbidden(
            type = ErrorType.FORBIDDEN_LACKS_ORG_ACCESS,
            value = allSykmeldtOrgNumbers.contains(systemPrincipal.getSystemUserOrgNumber())
        ) { "Innsender samsvarer ikke virksomhet i request" }
    }
}
