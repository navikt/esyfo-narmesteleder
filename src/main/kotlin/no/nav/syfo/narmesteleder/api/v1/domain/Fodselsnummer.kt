package no.nav.syfo.narmesteleder.api.v1.domain

data class Fodselsnummer(val value: String) {
    private val elevenDigits = Regex("^\\d{11}\$")

    init {
        require(elevenDigits.matches(value))
    }
}
