package no.nav.syfo.narmesteleder.service

import no.nav.syfo.application.exception.ApiErrorException

class ValidateNarmesteLederException(message: String) : RuntimeException(message)
class ValidateActiveSykmeldingException(message: String) : RuntimeException(message)

private fun nlrequire(value: Boolean, lazyMessage: () -> String) {
    if (!value) throw ValidateNarmesteLederException(lazyMessage())
}

private fun nlrequireOrForbidden(value: Boolean, lazyMessage: () -> String) {
    if (!value) throw ApiErrorException.ForbiddenException(lazyMessage())
}

fun validateNarmesteLeder(
    sykemeldtOrgNumbers: Map<String, String>,
    narmesteLederOrgNumbers: Map<String, String>,
    innsenderOrgNumber: String?,
    orgNumberInRequest: String
) {
    val validMaskinportenOrgnumbers = sykemeldtOrgNumbers.map { listOf(it.key, it.value) }.flatten()
    nlrequire(sykemeldtOrgNumbers.keys.contains(orgNumberInRequest)) { "Ingen arbeidsforhold for sykemeldt for angitt virksomhet" }
    nlrequire(narmesteLederOrgNumbers.keys.contains(orgNumberInRequest)) { "Ingen arbeidsforhold for narmesteleder for angitt virksomhet" }
    nlrequire(
        narmesteLederOrgNumbers.keys == sykemeldtOrgNumbers.keys,
        { "Næremeste leder mangler arbeidsforhold i samme virksomhet som sykmeldt" })
    innsenderOrgNumber?.let { nlrequireOrForbidden(validMaskinportenOrgnumbers.contains(innsenderOrgNumber)) { "Innsender samsvarer ikke virksomhet i request" } }
}

fun validateNarmesteLederAvkreft(
    sykemeldtOrgNumbers: Map<String, String>,
    orgNumberInRequest: String,
    innsenderOrgNumber: String?,
) {
    val validMaskinportenOrgnumbers = sykemeldtOrgNumbers.map { listOf(it.key, it.value) }.flatten()
    nlrequire(sykemeldtOrgNumbers.isNotEmpty()) { "Ingen arbeidsforhold for sykemeldt" }
    nlrequire(sykemeldtOrgNumbers.contains(orgNumberInRequest)) { "Organisasjonsnummer i HTTP request body samsvarer ikke med sykemeldtes organisasjoner" }
    innsenderOrgNumber?.let { nlrequireOrForbidden(validMaskinportenOrgnumbers.contains(innsenderOrgNumber)) { "Innsender samsvarer ikke virksomhet i request" } }
}
