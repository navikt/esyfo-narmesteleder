package no.nav.syfo.narmesteleder.domain

@JvmInline
value class PersonalIdentificationNumber(val value: String) {
    init {
        require(value.length == 11 && value.all { it.isDigit() }) {
            "PersonalIdentificationNumber must be exactly 11 digits"
        }
    }

    companion object {
        fun parse(value: String): Result<PersonalIdentificationNumber> = runCatching { PersonalIdentificationNumber(value) }
    }
}
