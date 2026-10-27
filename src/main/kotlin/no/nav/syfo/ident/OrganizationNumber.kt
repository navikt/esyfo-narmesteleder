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

        // ASCII digits only: Char.isDigit accepts Unicode digits (e.g. Arabic-Indic) that upstream systems reject.
        fun isValid(value: String): Boolean = value.length == ORGANIZATION_NUMBER_LENGTH && value.all { it in '0'..'9' }
    }
}
