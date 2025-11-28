package no.nav.syfo.pdl.client

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import java.util.*
import net.datafaker.Faker
import no.nav.syfo.pdl.client.Ident.Companion.GRUPPE_IDENT_FNR
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
    suspend fun getPerson(fnr: String): GetPersonResponse
}

class PdlClient(
    private val httpClient: HttpClient,
    private val pdlBaseUrl: String,
    private val texasHttpClient: TexasHttpClient,
    private val scope: String
) : IPdlClient {
    companion object {
        private val logger = logger()
    }

    override suspend fun getPerson(fnr: String): GetPersonResponse {
        val token = texasHttpClient.systemToken(
            "azuread",
            TexasHttpClient.getTarget(scope)
        ).accessToken

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
            if (!pdlReponse.errors.isNullOrEmpty() ) {
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
}

class FakePdlClient : IPdlClient {
    override suspend fun getPerson(fnr: String): GetPersonResponse {
        val faker = Faker(Random(fnr.toLong()))
        val navn = faker.name()
        return GetPersonResponse(
            data = ResponseData(
                person = PersonResponse(
                    navn = listOf(
                        Navn(
                            fornavn = navn.firstName(),
                            mellomnavn = navn.nameWithMiddle().split(" ").getOrNull(1),
                            etternavn = navn.lastName(),
                        ),
                    ),
                ),
                identer = IdentResponse(
                    identer = listOf(
                        Ident(ident = fnr, gruppe = GRUPPE_IDENT_FNR),
                    ),
                ),
            ),
            errors = null,
        )
    }
}
