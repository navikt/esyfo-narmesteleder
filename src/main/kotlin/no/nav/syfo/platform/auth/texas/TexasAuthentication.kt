package no.nav.syfo.platform.auth.texas

import com.auth0.jwt.JWT
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.application.isHandled
import io.ktor.server.application.pluginOrNull
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.request.authorization
import io.ktor.server.routing.Route
import no.nav.syfo.platform.api.ApiErrorException
import no.nav.syfo.platform.api.authenticateRequired
import no.nav.syfo.platform.auth.JwtIssuer
import no.nav.syfo.platform.auth.texas.client.TexasHttpClient
import org.koin.ktor.ext.getKoin
import org.koin.ktor.plugin.Koin as KoinPlugin

const val TOKEN_X_AUTH_PROVIDER = "tokenx"
const val MASKINPORTEN_AUTH_PROVIDER = "maskinporten"
const val AZURE_AD_AUTH_PROVIDER = "azure-ad"

fun Application.installTexasAuthentication(azurePreAuthorizedApps: Set<String>) {
    val texasHttpClient = resolveTexasHttpClient()
    installTexasAuthentication(texasHttpClient, azurePreAuthorizedApps)
}

internal fun Application.installTexasAuthentication(
    texasHttpClient: TexasHttpClient,
    azurePreAuthorizedApps: Set<String>,
) {
    require(azurePreAuthorizedApps.isNotEmpty()) {
        "Texas Azure AD authentication installed without pre-authorized apps"
    }

    install(Authentication) {
        texasTokenX(texasHttpClient)
        texasMaskinporten(texasHttpClient)
        texasAzureAd(texasHttpClient, azurePreAuthorizedApps)
    }
}

fun Route.authenticateTokenX(build: Route.() -> Unit): Route = authenticateRequired(TOKEN_X_AUTH_PROVIDER, build = build)

fun Route.authenticateMaskinportenOrTokenX(build: Route.() -> Unit): Route = authenticateRequired(
    MASKINPORTEN_AUTH_PROVIDER,
    TOKEN_X_AUTH_PROVIDER,
    build = build,
)

fun Route.authenticateAzureAd(build: Route.() -> Unit): Route = authenticateRequired(AZURE_AD_AUTH_PROVIDER, build = build)

private fun AuthenticationConfig.texasTokenX(client: TexasHttpClient) {
    provider(TOKEN_X_AUTH_PROVIDER) {
        authenticate { context ->
            context.authenticateForIssuer(JwtIssuer.TOKEN_X, TOKEN_X_AUTH_PROVIDER) { token ->
                val principal = context.call.authenticateTokenX(client, token)
                if (principal != null) {
                    context.principal(principal)
                }
            }
        }
    }
}

private fun AuthenticationConfig.texasMaskinporten(client: TexasHttpClient) {
    provider(MASKINPORTEN_AUTH_PROVIDER) {
        authenticate { context ->
            context.authenticateForIssuer(JwtIssuer.MASKINPORTEN, MASKINPORTEN_AUTH_PROVIDER) { token ->
                context.principal(context.call.authenticateMaskinporten(client, token))
            }
        }
    }
}

private fun AuthenticationConfig.texasAzureAd(
    client: TexasHttpClient,
    preAuthorizedApps: Set<String>,
) {
    provider(AZURE_AD_AUTH_PROVIDER) {
        authenticate { context ->
            context.authenticateForIssuer(JwtIssuer.AZURE_AD, AZURE_AD_AUTH_PROVIDER) { token ->
                val introspectionResponse = context.call.introspectActiveToken(
                    client,
                    TokenRejectionProvider.AZURE_AD,
                    TexasHttpClient.IDENTITY_PROVIDER_AZUREAD,
                    token,
                )
                val clientId = introspectionResponse.azp
                if (clientId == null || clientId !in preAuthorizedApps) {
                    context.call.recordTokenRejection(
                        TokenRejectionProvider.AZURE_AD,
                        TokenRejectionReason.CLIENT_NOT_PRE_AUTHORIZED,
                    )
                    throw ApiErrorException.ForbiddenException("Application is not authorized")
                }
                context.principal(AzureAdPrincipal(clientId))
            }
        }
    }
}

private suspend fun AuthenticationContext.authenticateForIssuer(
    expectedIssuer: JwtIssuer,
    providerName: String,
    authenticate: suspend (String) -> Unit,
) {
    val token = call.bearerToken()
    if (token == null || unverifiedJwtIssuer(token) != expectedIssuer) {
        challenge(
            key = "$providerName-invalid-credentials",
            cause = if (token == null) {
                AuthenticationFailedCause.NoCredentials
            } else {
                AuthenticationFailedCause.InvalidCredentials
            },
        ) { _, _ ->
            if (!call.isHandled) {
                throw ApiErrorException.UnauthorizedException("Invalid token")
            }
        }
        return
    }
    authenticate(token)
}

private fun unverifiedJwtIssuer(token: String): JwtIssuer? {
    val decodedToken = try {
        JWT.decode(token)
    } catch (_: Exception) {
        return null
    }
    val issuer = decodedToken.issuer ?: return null
    if (!decodedToken.getClaim("idp").asString().isNullOrEmpty()) {
        return JwtIssuer.TOKEN_X
    }
    return JwtIssuer.fromIssuerString(issuer)
}

private data class AzureAdPrincipal(val clientId: String)

private fun Application.resolveTexasHttpClient(): TexasHttpClient = pluginOrNull(KoinPlugin)?.let { getKoin().getOrNull<TexasHttpClient>() }
    ?: error("Texas authentication installed without TexasHttpClient")

private fun ApplicationCall.bearerToken(): String? = request
    .authorization()
    ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
    ?.removePrefix("Bearer ")
    ?.removePrefix("bearer ")
