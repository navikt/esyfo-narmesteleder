package no.nav.syfo.narmesteleder.service.validators

import no.nav.esyfo.observability.apiRequestRejected
import no.nav.syfo.altinn.pdp.client.Decision

internal const val SYSTEM_USER_ACCESS_NOT_GRANTED = "SYSTEM_USER_ACCESS_NOT_GRANTED"

internal data class SystemUserAccessRejection(
    val directDecision: Decision,
    val fallbackDecision: Decision?,
)

internal val systemUserAccessRejected = apiRequestRejected<SystemUserAccessRejection>(
    operation = "validate_system_user_access",
    errorCode = "MISSING_ALTINN_RESOURCE_ACCESS",
    message = "System user access was not granted after resource and organization checks",
    reason = { SYSTEM_USER_ACCESS_NOT_GRANTED },
    fields = mapOf(
        "pdp_decision" to { it.directDecision.name },
        "pdp_fallback_decision" to { it.fallbackDecision?.name ?: "not_checked" },
    ),
)
