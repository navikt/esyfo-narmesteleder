package no.nav.syfo.ident

@JvmInline
value class PersonIdent(val value: String) {
    init {
        require(value.length == 11 && value.all(Char::isDigit)) {
            "PersonIdent must be exactly 11 digits"
        }
    }
}
