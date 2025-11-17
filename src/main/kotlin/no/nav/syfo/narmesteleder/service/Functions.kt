package no.nav.syfo.narmesteleder.service

import no.nav.syfo.application.auth.SystemPrincipal
import no.nav.syfo.application.exception.ApiErrorException

class ValidateNarmesteLederException(message: String) : RuntimeException(message)
class ValidateActiveSykmeldingException(message: String) : RuntimeException(message)

fun nlrequire(value: Boolean, lazyMessage: () -> String) {
    if (!value) throw ValidateNarmesteLederException(lazyMessage())
}

private fun nlrequireOrForbidden(value: Boolean, lazyMessage: () -> String) {
    if (!value) throw ApiErrorException.ForbiddenException(lazyMessage())
}

fun validateNarmesteLeder(
    sykemeldtOrgNumbers: Map<String, String>,
    systemPrincipal: SystemPrincipal?,
    orgNumberInRequest: String
) {
    val validMaskinportenOrgnumbers = sykemeldtOrgNumbers.map { listOf(it.key, it.value) }.flatten()
    nlrequire(sykemeldtOrgNumbers.contains(orgNumberInRequest)) { "No arbeidsforhold for sykemeldt in provided organization" }
    systemPrincipal?.let { nlrequireOrForbidden(validMaskinportenOrgnumbers.contains(systemPrincipal.getSystemUserOrgNumber())) { "Consumer in token does not match organization in request payload, or its parent" } }
}

fun validateNarmesteLederAvkreft(
    sykemeldtOrgNumbers: Map<String, String>,
    orgNumberInRequest: String,
    systemPrincipal: SystemPrincipal?,
) {
    val validMaskinportenOrgnumbers = sykemeldtOrgNumbers.map { listOf(it.key, it.value) }.flatten()
    nlrequire(sykemeldtOrgNumbers.isNotEmpty()) { "No arbeidsforhold for sykemeldt" }
    nlrequire(sykemeldtOrgNumbers.contains(orgNumberInRequest)) { "No arbeidsforhold for sykemeldt in provided organization" }
    systemPrincipal?.let { nlrequireOrForbidden(validMaskinportenOrgnumbers.contains(systemPrincipal.getSystemUserOrgNumber())) { "Consumer in token does not match organization in request payload, or its parent" } }
}
