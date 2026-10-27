package no.nav.syfo.ident

@JvmInline
value class PersonalIdentificationNumber(val value: String) {
    init {
        // Part of the HTTP 400 contract: Jackson returns this message for invalid identifiers in request bodies.
        require(isValid(value)) {
            "PersonalIdentificationNumber must be exactly $PERSONAL_IDENTIFICATION_NUMBER_LENGTH digits"
        }
    }

    companion object {
        private const val PERSONAL_IDENTIFICATION_NUMBER_LENGTH = 11

        // ASCII digits only: Char.isDigit accepts Unicode digits (e.g. Arabic-Indic) that upstream systems reject.
        fun isValid(value: String): Boolean = value.length == PERSONAL_IDENTIFICATION_NUMBER_LENGTH && value.all { it in '0'..'9' }
    }
}
