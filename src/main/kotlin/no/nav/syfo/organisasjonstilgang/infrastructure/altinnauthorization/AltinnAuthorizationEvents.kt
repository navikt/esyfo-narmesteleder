package no.nav.syfo.organisasjonstilgang.infrastructure.altinnauthorization

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject
import no.nav.syfo.platform.observability.applicationEvent
import org.slf4j.event.Level

internal data class PdpAuthorizationNotPermitContext(
    val subject: OrganizationAccessSubject.LpsSystemUser,
    val organizationNumber: OrganizationNumber,
    val decision: AuthorizationDecision
)

internal val pdpAuthorizationNotPermit = applicationEvent<PdpAuthorizationNotPermitContext>(
    name = "altinn_pdp_authorization_not_permit",
    level = Level.WARN,
    message = "Altinn PDP authorization decision was not Permit",
    upstream = ALTINN_PDP.value,
    fields = mapOf(
        "lps_system_user" to { it.subject },
        "organization_number" to { it.organizationNumber.value },
        "authorization_decision" to { it.decision.name },
    ),
)
