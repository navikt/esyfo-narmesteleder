package no.nav.syfo.pdl.client

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import java.util.Random
import net.datafaker.Faker
import no.nav.syfo.pdl.Person
import no.nav.syfo.texas.client.TexasHttpClient
import org.intellij.lang.annotations.Language

private const val BEHANDLINGSNUMMER_DIGITAL_OPPFOLGINGSPLAN = "B506"
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
      identer: hentIdenter(ident: ${'$'}ident, historikk: false, grupper: ["FOLKEREGISTERIDENT"]) {
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
): IPdlClient {
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

        return httpClient
            .post(pdlBaseUrl) {
                setBody(getPersonRequest)
                header(HttpHeaders.Authorization, "Bearer $token")
                header(PDL_BEHANDLINGSNUMMER_HEADER, BEHANDLINGSNUMMER_DIGITAL_OPPFOLGINGSPLAN)
                header(HttpHeaders.ContentType, "application/json")
            }
            .body()


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
                            mellomnavn = faker.name().firstName(),
                            etternavn = navn.lastName(),
                        ),
                    ),
                ),
                identer = IdentResponse(
                    identer = listOf(
                        Ident(ident = fnr, gruppe = "FOLKEREGISTERIDENT"),
                    ),
                ),
            ),
            errors = null,
        )
    }
}
