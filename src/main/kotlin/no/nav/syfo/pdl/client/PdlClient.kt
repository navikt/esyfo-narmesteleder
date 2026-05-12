package no.nav.syfo.pdl.client

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import no.nav.syfo.pdl.exception.PdlRequestException
import no.nav.syfo.pdl.exception.PdlResourceNotFoundException
import no.nav.syfo.texas.client.TexasHttpClient
import no.nav.syfo.util.logger
import org.intellij.lang.annotations.Language

private const val BEHANDLINGSNUMMER_NARMESTELEDER = "B506"
private const val PDL_BEHANDLINGSNUMMER_HEADER = "behandlingsnummer"

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

interface IPdlClient {
    suspend fun getSystemToken(): String
    suspend fun getPerson(fnr: String): GetPersonResponse
    suspend fun getPersonBolk(fnrs: List<String>, token: String): GetPersonBolkResponse
}

class PdlClient(
    private val httpClient: HttpClient,
    private val pdlBaseUrl: String,
    private val texasHttpClient: TexasHttpClient,
    private val scope: String
) : IPdlClient {
    companion object {
        private val logger = logger()
        private val getPersonBolkQuery =
            PdlClient::class.java.getResource("/graphql/hentPersonBolk.graphql")
                ?.readText()
                ?: throw IllegalStateException("Could not load hentPersonBolk.graphql")
    }

    override suspend fun getSystemToken(): String = texasHttpClient.systemToken(
        "azuread",
        TexasHttpClient.getTarget(scope)
    ).accessToken

    override suspend fun getPerson(fnr: String): GetPersonResponse {
        val token = getSystemToken()

        val getPersonRequest =
            GetPersonRequest(
                query = getPersonQuery,
                variables = GetPersonVariables(ident = fnr),
            )
        try {
            val pdlReponse = httpClient
                .post(pdlBaseUrl) {
                    setBody(getPersonRequest)
                    header(HttpHeaders.Authorization, "Bearer $token")
                    header(PDL_BEHANDLINGSNUMMER_HEADER, BEHANDLINGSNUMMER_NARMESTELEDER)
                    header(HttpHeaders.ContentType, "application/json")
                }
                .body<GetPersonResponse>()
            if (!pdlReponse.errors.isNullOrEmpty()) {
                logger.error("Error when requesting person from PDL. Got errors: ${pdlReponse.errors}")
            }
            if (pdlReponse.data?.person == null || pdlReponse.data.identer == null) {
                throw PdlResourceNotFoundException("Did not find person in PDL for given fnr")
            }
            return pdlReponse
        } catch (e: ResponseException) {
            logger.error("Error on findPerson query to PDL. Got status ${e.response.status} and message ${e.message}")
            throw PdlRequestException("Error on findPerson query to PDL", e)
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
                    header(HttpHeaders.Authorization, "Bearer $token")
                    header(PDL_BEHANDLINGSNUMMER_HEADER, BEHANDLINGSNUMMER_NARMESTELEDER)
                    header(HttpHeaders.ContentType, "application/json")
                }
                .body<GetPersonBolkResponse>()
            return response
        } catch (e: ResponseException) {
            logger.error("Error on getPersonBolk query to PDL. Got status ${e.response.status} and message ${e.message}")
            throw PdlRequestException("Error on getPersonBolk query to PDL", e)
        }
    }
}
