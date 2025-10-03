package no.nav.syfo.narmesteleder.service

class ValidateNarmesteLederException(message: String) : RuntimeException(message)

private fun nlrequire(value: Boolean, lazyMessage: () -> String) {
    if (!value) throw ValidateNarmesteLederException(lazyMessage())
}

fun validateNarmesteLeder(
    sykemeldtOrgNumbers: Set<String>,
    narmesteLederOrgNumbers: Set<String>,
    innsenderOrgNumber: String?,
    orgNumberInRequest: String
) {
    nlrequire(sykemeldtOrgNumbers.isNotEmpty()) { "Ingen arbeidsforhold for sykemeldt" }
    nlrequire(narmesteLederOrgNumbers.isNotEmpty()) { "Ingen arbeidsforhold for narmesteleder" }

    with(sykemeldtOrgNumbers intersect narmesteLederOrgNumbers) {
        nlrequire(isNotEmpty()) { "Ikke samsvar mellom sykemeldt og nærmeste leders organisasjonsenheter" }
        nlrequire(contains(orgNumberInRequest)) { "Organisasjonsnummer i HTTP request body samsvarer ikke med nærmeste leder og sykemeldtes organisasjoner" }
        innsenderOrgNumber?.let { nlrequire(contains(innsenderOrgNumber)) { "Innsender samsvarer ikke med nærmeste leder og sykemeldts organisasjonsenhet" } }
    }
}

fun validateNarmesteLederAvkreft(
    sykemeldtOrgNumbers: Set<String>,
    orgNumberInRequest: String,
    innsenderOrgNumber: String?,
) {
    nlrequire(sykemeldtOrgNumbers.isNotEmpty()) { "Ingen arbeidsforhold for sykemeldt" }
    nlrequire(sykemeldtOrgNumbers.contains(orgNumberInRequest)) { "Organisasjonsnummer i HTTP request body samsvarer ikke med sykemeldtes organisasjoner" }
    innsenderOrgNumber?.let { nlrequire(sykemeldtOrgNumbers.contains(innsenderOrgNumber)) { "Innsender samsvarer ikke med sykemeldts organisasjonsenhet" } }
}
