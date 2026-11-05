package no.nav.syfo.platform.auth.texas

import io.ktor.server.application.ApplicationCall
import io.micrometer.core.instrument.Counter
import no.nav.syfo.platform.observability.METRICS_NS
import no.nav.syfo.platform.observability.METRICS_REGISTRY
import no.nav.syfo.platform.observability.applicationEvent
import no.nav.syfo.platform.observability.logEvent
import org.slf4j.event.Level

const val TOKEN_REJECTED_TOTAL = "${METRICS_NS}_token_rejected_total"

internal enum class TokenRejectionProvider(val providerName: String) {
    TOKEN_X(TOKEN_X_AUTH_PROVIDER),
    MASKINPORTEN(MASKINPORTEN_AUTH_PROVIDER),
    AZURE_AD(AZURE_AD_AUTH_PROVIDER),
}

internal enum class TokenRejectionReason {
    TOKEN_INACTIVE,
    LEVEL_INSUFFICIENT,
    PERSON_CLAIM_MISSING,
    CONSUMER_MISSING,
    SCOPE_INVALID,
    SYSTEM_USER_ORGANIZATION_MISSING,
    SYSTEM_USER_ID_MISSING,
    SYSTEM_USER_ID_INVALID,
    CLIENT_NOT_PRE_AUTHORIZED,
}

private data class TokenRejectionDetails(val reason: TokenRejectionReason, val acr: String?)

private val tokenRejectedEvents = TokenRejectionProvider.entries.associateWith { provider ->
    applicationEvent<TokenRejectionDetails>(
        name = "token_rejected",
        level = Level.WARN,
        message = "Token did not satisfy authentication requirements",
        upstream = provider.providerName,
        fields = mapOf(
            "acr" to { it.acr },
            "reason" to { it.reason.name },
        ),
    )
}

internal fun ApplicationCall.recordTokenRejection(
    provider: TokenRejectionProvider,
    reason: TokenRejectionReason,
    acr: String? = null,
) {
    application.environment.log.logEvent(
        tokenRejectedEvents.getValue(provider),
        TokenRejectionDetails(reason, acr),
    )
    tokenRejectedCounter(provider, reason).increment()
}

private fun tokenRejectedCounter(provider: TokenRejectionProvider, reason: TokenRejectionReason): Counter = Counter.builder(TOKEN_REJECTED_TOTAL)
    .description("Counts bearer tokens rejected by Texas authentication providers")
    .tag("provider", provider.providerName)
    .tag("reason", reason.name.lowercase())
    .register(METRICS_REGISTRY)
