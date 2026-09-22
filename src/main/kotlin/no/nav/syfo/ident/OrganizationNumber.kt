package no.nav.syfo.ident

@JvmInline
value class OrganizationNumber(val value: String) {
    init {
        require(value.length == 9 && value.all(Char::isDigit)) {
            "OrganizationNumber must be exactly 9 digits"
        }
    }
}
