package no.nav.syfo.ident

@JvmInline
value class OrganizationNumber(val value: String) {
    init {
        require(isValid(value)) {
            "OrganizationNumber must be exactly $ORGANIZATION_NUMBER_LENGTH digits"
        }
    }

    companion object {
        private const val ORGANIZATION_NUMBER_LENGTH = 9

        fun isValid(value: String): Boolean = value.length == ORGANIZATION_NUMBER_LENGTH && value.all(Char::isDigit)
    }
}
