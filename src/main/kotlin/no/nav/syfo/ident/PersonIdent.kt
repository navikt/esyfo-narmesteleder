package no.nav.syfo.ident

@JvmInline
value class PersonIdent(val value: String) {
    init {
        require(isValid(value)) {
            "PersonIdent must be exactly $PERSON_IDENT_LENGTH digits"
        }
    }

    companion object {
        private const val PERSON_IDENT_LENGTH = 11

        fun isValid(value: String): Boolean = value.length == PERSON_IDENT_LENGTH && value.all(Char::isDigit)
    }
}
