package no.nav.syfo.organisasjonstilgang.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.platform.upstream.UpstreamFailure

fun interface OrganizationAccess {
    suspend fun evaluate(
        subject: OrganizationAccessSubject,
        organizationNumber: OrganizationNumber,
    ): OrganizationAccessResult
}

sealed interface OrganizationAccessSubject {
    data class PersonnelManager(
        val personIdent: PersonalIdentificationNumber,
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
    /**
     * [organizationName] is the name of the requested organization as registered in Altinn.
     * It is only known when a personnel manager is granted access; null for system users.
     */
    data class Granted(val organizationName: String?) : OrganizationAccessResult
    data class Denied(val reason: DenialReason) : OrganizationAccessResult

    /** An upstream failure; never means access is granted. */
    data class Unavailable(val failure: UpstreamFailure) : OrganizationAccessResult
}

enum class DenialReason {
    MISSING_ORGANIZATION_ACCESS,
    MISSING_RESOURCE_ACCESS,
    SYSTEM_USER_REJECTED,
}
