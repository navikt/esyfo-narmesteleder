package no.nav.syfo.integration.pdl

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.CancellationException
import no.nav.syfo.platform.upstream.UpstreamError
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName
import no.nav.syfo.platform.upstream.UpstreamResult
import no.nav.syfo.platform.upstream.getOrElse
import no.nav.syfo.texas.client.TexasHttpClient
import org.intellij.lang.annotations.Language

private const val BEHANDLINGSNUMMER_NARMESTELEDER = "B506"
private const val PDL_BEHANDLINGSNUMMER_HEADER = "behandlingsnummer"
internal val PDL = UpstreamName("pdl")

@Language("GraphQL")
private val getPersonQuery =
    """
    query(${'$'}ident: ID!){
      person: hentPerson(ident: ${'$'}ident) {
        navn(historikk: false) {
          fornavn
          mellomnavn
          etternavn
        }
        foedselsdato { foedselsdato }
      }
      identer: hentIdenter(ident: ${'$'}ident, historikk: false) {
          identer {
            ident,
            gruppe
          }
        }
    }
"""
        .trimIndent()

interface PdlClient {
    suspend fun getSystemToken(): String
    suspend fun getPerson(ident: String): UpstreamResult<PdlPerson?>
    suspend fun getPersonBolk(fnrs: List<String>, token: String): GetPersonBolkResponse
}

class HttpPdlClient(
    private val httpClient: HttpClient,
    private val pdlBaseUrl: String,
    private val texasHttpClient: TexasHttpClient,
    private val scope: String
) : PdlClient {
    companion object {
        private val getPersonBolkQuery =
            HttpPdlClient::class.java.getResource("/graphql/hentPersonBolk.graphql")
                ?.readText()
                ?: throw IllegalStateException("Could not load hentPersonBolk.graphql")
    }

    override suspend fun getSystemToken(): String = texasHttpClient.systemToken(
        "azuread",
        TexasHttpClient.getTarget(scope)
    ).accessToken

    override suspend fun getPerson(ident: String): UpstreamResult<PdlPerson?> {
        val token = texasHttpClient.azureAdSystemToken(scope).getOrElse { return UpstreamResult.Failure(it) }

        val getPersonRequest =
            GetPersonRequest(
                query = getPersonQuery,
                variables = GetPersonVariables(ident = ident),
            )
        val response = try {
            httpClient
                .post(pdlBaseUrl) {
                    setBody(getPersonRequest)
                    bearerAuth(token)
                    header(PDL_BEHANDLINGSNUMMER_HEADER, BEHANDLINGSNUMMER_NARMESTELEDER)
                    header(HttpHeaders.ContentType, "application/json")
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ResponseException) {
            return UpstreamResult.Failure(UpstreamFailure(PDL, UpstreamFailureStage.RESPONSE, e.response.status.value, e))
        } catch (e: Exception) {
            return UpstreamResult.Failure(UpstreamFailure(PDL, UpstreamFailureStage.REQUEST, null, e))
        }
        val decoded = try {
            response.body<GetPersonResponse>()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return UpstreamResult.Failure(UpstreamFailure(PDL, UpstreamFailureStage.RESPONSE, response.status.value, e))
        }
        val errors = decoded.errors.orEmpty()
        if (errors.isNotEmpty()) {
            val notFound = errors.all {
                it.extensions?.code == "not_found" ||
                    (it.extensions?.code == "bad_request" && it.extensions.id == "ugyldig_ident")
            }
            if (notFound) return UpstreamResult.Success(null)
            return UpstreamResult.Failure(
                UpstreamFailure(
                    PDL,
                    UpstreamFailureStage.RESPONSE,
                    200,
                    errors = errors.map {
                        UpstreamError(it.extensions?.code ?: "unknown", it.extensions?.id, it.path?.joinToString("."))
                    },
                ),
            )
        }
        val person = decoded.data?.person
        val identer = decoded.data?.identer
        return if (person != null && identer != null) {
            UpstreamResult.Success(PdlPerson(person, identer.identer))
        } else {
            UpstreamResult.Failure(UpstreamFailure(PDL, UpstreamFailureStage.RESPONSE, 200, errors = listOf(UpstreamError("incomplete_response"))))
        }
    }

    override suspend fun getPersonBolk(fnrs: List<String>, token: String): GetPersonBolkResponse {
        val request = GetPersonBolkRequest(
            query = getPersonBolkQuery,
            variables = GetPersonBolkVariables(identer = fnrs),
        )
        try {
            val response = httpClient
                .post(pdlBaseUrl) {
                    setBody(request)
                    bearerAuth(token)
                    header(PDL_BEHANDLINGSNUMMER_HEADER, BEHANDLINGSNUMMER_NARMESTELEDER)
                    header(HttpHeaders.ContentType, "application/json")
                }
                .body<GetPersonBolkResponse>()
            return response
        } catch (e: Exception) {
            when (e) {
                is CancellationException -> throw e
                is ResponseException -> {
                    throw PdlRequestException("Error on getPersonBolk query to PDL", e)
                }

                else -> throw PdlRequestException("Error when calling PDL", e)
            }
        }
    }
}
