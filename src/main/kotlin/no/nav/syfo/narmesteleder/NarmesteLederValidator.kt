package no.nav.syfo.narmesteleder

import no.nav.syfo.application.exception.ApiErrorException

object NarmesteLederValidator {
    fun nlvalidate(block: NarmesteLederValidator.() -> Unit) {
        block()
    }

    fun matchOne(validOrgNumbers: List<String>, checkOrgNumber: String) {
        if (checkOrgNumber !in validOrgNumbers) throw ApiErrorException.BadRequestException()
    }

    fun isNotEmpty(orgNumbers: List<String>) {
        if (orgNumbers.isEmpty()) throw ApiErrorException.BadRequestException()
    }

    fun checkArbeidsforhold(
        validOrgNumbers: List<String>,
        innsenderEmployerOrgnr: String,
        nlOrgNumber: String
    ) {
        if (validOrgNumbers.isEmpty()) {
            throw ApiErrorException.ForbiddenException("Ingen aktive arbeidsforhold for person")
        }
        // Arbeidstakers ansettelsforhold gitt ved organisasjonsnummer i over- og underenhet
        if (nlOrgNumber !in validOrgNumbers)
            throw ApiErrorException.ForbiddenException("Arbeidsgiver samsvarer ikke for arbeidstaker og leder")
        if (innsenderEmployerOrgnr !in validOrgNumbers)
            throw ApiErrorException.ForbiddenException("Arbeidsgiver samsvarer ikke for arbeidstaker og innsender")
    }
}


