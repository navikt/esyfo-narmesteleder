package no.nav.syfo.narmesteleder.service

import no.nav.syfo.application.auth.SystemPrincipal
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.narmesteleder.domain.Linemanager
import no.nav.syfo.pdl.Person

class ValidateNarmesteLederException(message: String) : RuntimeException(message)
class ValidateActiveSykmeldingException(message: String) : RuntimeException(message)

fun nlrequire(value: Boolean, lazyMessage: () -> String) {
    if (!value) throw ValidateNarmesteLederException(lazyMessage())
}

private fun nlrequireOrForbidden(value: Boolean, lazyMessage: () -> String) {
    if (!value) throw ApiErrorException.ForbiddenException(lazyMessage())
}

fun validateLinemanagerLastName(
    managerPdlPerson: Person,
    linemanager: Linemanager,
) {
    if (managerPdlPerson.name.etternavn.uppercase() != linemanager.manager.lastName.uppercase()) throw ApiErrorException.BadRequestException(
        "Last name for linemanager does not correspond with registerd value for the given national identification number",
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
            allSykmeldtOrgNumbers.contains(systemPrincipal.getSystemUserOrgNumber())
        )
        { "Systembruker har ikke tilgang til virksomhet" }
    }
}

fun validateNarmesteLederAvkreft(
    sykemeldtOrgNumbers: Map<String, String>,
    orgNumberInRequest: String,
    systemPrincipal: SystemPrincipal?,
) {
    val validMaskinportenOrgnumbers = sykemeldtOrgNumbers.map { listOf(it.key, it.value) }.flatten()
    nlrequire(sykemeldtOrgNumbers.isNotEmpty()) { "Ingen arbeidsforhold for sykemeldt" }
    nlrequire(sykemeldtOrgNumbers.contains(orgNumberInRequest)) { "Organisasjonsnummer i HTTP request body samsvarer ikke med sykemeldtes organisasjoner" }
    systemPrincipal?.let { nlrequireOrForbidden(validMaskinportenOrgnumbers.contains(systemPrincipal.getSystemUserOrgNumber())) { "Innsender samsvarer ikke virksomhet i request" } }
}
