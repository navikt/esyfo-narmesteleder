package no.nav.syfo.narmesteleder.domain

@JvmInline
value class OrganizationNumber(val value: String) {
    init {
        require(value.length == 9 && value.all { it.isDigit() }) {
            "OrganizationNumber must be exactly 9 digits"
        }
    }

    companion object {
        fun parse(value: String): Result<OrganizationNumber> = runCatching { OrganizationNumber(value) }
    }
}
