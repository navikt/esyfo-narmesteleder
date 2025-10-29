package no.nav.syfo.application.auth

sealed class Principal{
    abstract val ident: String
    abstract val token: String
}
data class UserPrincipal(
    override val ident: String,
    override val token: String,
) : Principal()

data class OrganisasjonPrincipal(
    override val ident: String,
    override val token: String,
) : Principal()
