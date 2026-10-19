package no.nav.syfo.ident

@JvmInline
value class PersonIdent(val value: String) {
    init {
        // Part of the HTTP 400 contract: Jackson returns this message for invalid identifiers in request bodies.
        require(isValid(value)) {
            "PersonalIdentificationNumber must be exactly $PERSON_IDENT_LENGTH digits"
        }
    }

    companion object {
        private const val PERSON_IDENT_LENGTH = 11

        fun isValid(value: String): Boolean = value.length == PERSON_IDENT_LENGTH && value.all(Char::isDigit)
    }
}
