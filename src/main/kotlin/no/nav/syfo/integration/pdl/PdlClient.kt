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
import no.nav.syfo.platform.auth.texas.client.TexasHttpClient
import no.nav.syfo.platform.upstream.UpstreamError
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName
import no.nav.syfo.platform.upstream.UpstreamResult
import no.nav.syfo.platform.upstream.getOrElse
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
    suspend fun getPerson(ident: String): UpstreamResult<PdlPerson?>
    suspend fun getPersonBolk(idents: List<String>): UpstreamResult<GetPersonBolkResponse>
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

    override suspend fun getPerson(ident: String): UpstreamResult<PdlPerson?> {
        val request = GetPersonRequest(query = getPersonQuery, variables = GetPersonVariables(ident = ident))
        val response = postQuery<GetPersonResponse>(request).getOrElse { return UpstreamResult.Failure(it) }
        val errors = response.errors.orEmpty()

        return when {
            errors.isEmpty() -> response.toPdlPerson()
            errors.all { it.meansPersonNotFound() } -> UpstreamResult.Success(null)
            else -> graphQlFailure(errors.map { it.toUpstreamError() })
        }
    }

    override suspend fun getPersonBolk(idents: List<String>): UpstreamResult<GetPersonBolkResponse> {
        val request = GetPersonBolkRequest(query = getPersonBolkQuery, variables = GetPersonBolkVariables(identer = idents))
        val response = postQuery<GetPersonBolkResponse>(request).getOrElse { return UpstreamResult.Failure(it) }

        return if (response.data?.hentPersonBolk != null) {
            UpstreamResult.Success(response)
        } else {
            graphQlFailure(response.errors.orEmpty().map { it.toUpstreamError() }.ifEmpty { listOf(INCOMPLETE_RESPONSE) })
        }
    }

    private suspend inline fun <reified T> postQuery(request: Any): UpstreamResult<T> {
        val token = texasHttpClient.azureAdSystemToken(scope).getOrElse { return UpstreamResult.Failure(it) }

        val response = try {
            httpClient.post(pdlBaseUrl) {
                setBody(request)
                bearerAuth(token)
                header(PDL_BEHANDLINGSNUMMER_HEADER, BEHANDLINGSNUMMER_NARMESTELEDER)
                header(HttpHeaders.ContentType, "application/json")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ResponseException) {
            return failure(UpstreamFailureStage.RESPONSE, e.response.status.value, e)
        } catch (e: Exception) {
            return failure(UpstreamFailureStage.REQUEST, null, e)
        }

        return try {
            UpstreamResult.Success(response.body<T>())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure(UpstreamFailureStage.RESPONSE, response.status.value, e)
        }
    }
}

private val INCOMPLETE_RESPONSE = UpstreamError("incomplete_response")

private fun failure(stage: UpstreamFailureStage, status: Int?, cause: Throwable) = UpstreamResult.Failure(UpstreamFailure(PDL, stage, status, cause))

// PDL answers GraphQL errors with HTTP 200.
private fun graphQlFailure(errors: List<UpstreamError>) = UpstreamResult.Failure(UpstreamFailure(PDL, UpstreamFailureStage.RESPONSE, 200, errors = errors))

private fun ResponseError.meansPersonNotFound(): Boolean = extensions?.code == "not_found" || (extensions?.code == "bad_request" && extensions.id == "ugyldig_ident")

private fun GetPersonResponse.toPdlPerson(): UpstreamResult<PdlPerson?> {
    val person = data?.person
    val identer = data?.identer
    return if (person != null && identer != null) {
        UpstreamResult.Success(PdlPerson(person, identer.identer))
    } else {
        graphQlFailure(listOf(INCOMPLETE_RESPONSE))
    }
}
