package no.nav.syfo.application.auth

sealed class Principal {
    abstract val ident: String
    abstract val token: String
}
data class UserPrincipal(
    override val ident: String,
    override val token: String,
) : Principal()

data class SystemPrincipal(
    override val ident: String,
    override val token: String,
    val systemOwner: String,
    val systemUserId: String,
) : Principal() {
    fun getSystemUserOrgNumber(): String = maskinportenIdToOrgnumber(ident)
    fun getSystemOwnerOrgNumber(): String = maskinportenIdToOrgnumber(systemOwner)
}
