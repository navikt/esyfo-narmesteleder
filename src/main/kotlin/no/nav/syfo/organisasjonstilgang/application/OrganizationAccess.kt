package no.nav.syfo.organisasjonstilgang.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent

fun interface OrganizationAccess {
    suspend fun evaluate(
        subject: OrganizationAccessSubject,
        organizationNumber: OrganizationNumber,
    ): OrganizationAccessResult
}

sealed interface OrganizationAccessSubject {
    data class PersonnelManager(
        val personIdent: PersonIdent,
        val accessToken: AccessToken,
    ) : OrganizationAccessSubject

    data class LpsSystemUser(
        val systemUserId: String,
        val systemUserOrganizationNumber: OrganizationNumber,
    ) : OrganizationAccessSubject
}

class AccessToken(private val value: String) {
    fun value(): String = value

    override fun toString(): String = "AccessToken(REDACTED)"

    override fun equals(other: Any?): Boolean = other is AccessToken && value == other.value

    override fun hashCode(): Int = value.hashCode()
}

sealed interface OrganizationAccessResult {
    data object Granted : OrganizationAccessResult
    data class Denied(val reason: DenialReason) : OrganizationAccessResult
}

enum class DenialReason {
    MISSING_ORGANIZATION_ACCESS,
    MISSING_RESOURCE_ACCESS,
    SYSTEM_USER_REJECTED,
}
