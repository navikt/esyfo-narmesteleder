package no.nav.syfo.pdl.client

import net.datafaker.Faker
import no.nav.syfo.pdl.client.Ident.Companion.GRUPPE_IDENT_FNR
import java.util.Random

class FakePdlClient : IPdlClient {
    override suspend fun getPerson(fnr: String): GetPersonResponse {
        val faker = Faker(Random(fnr.toLong()))
        val navn = faker.name()
        val foedselsdato = faker.timeAndDate().birthday()
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
                    foedselsdato = listOf(Foedselsdato(foedselsdato)),
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

    override suspend fun getPersonBolk(fnrs: List<String>): GetPersonBolkResponse {
        val hentPersonBolk = fnrs.map { fnr ->
            val faker = Faker(Random(fnr.toLong()))
            val navn = faker.name()
            HentPersonBolk(
                ident = fnr,
                person = Person(
                    navn = listOf(
                        Navn(
                            fornavn = navn.firstName(),
                            mellomnavn = navn.nameWithMiddle().split(" ").getOrNull(1),
                            etternavn = navn.lastName(),
                        ),
                    ),
                    foedselsdato = listOf(Foedselsdato(faker.timeAndDate().birthday())),
                ),
                code = "ok",
            )
        }
        val hentIdenterBolk = fnrs.map { fnr ->
            HentIdenterBolk(
                ident = fnr,
                identer = listOf(PdlIdent(ident = fnr, gruppe = GRUPPE_IDENT_FNR)),
                code = "ok",
            )
        }
        return GetPersonBolkResponse(
            data = PersonBolkResponseData(
                hentPersonBolk = hentPersonBolk,
                hentIdenterBolk = hentIdenterBolk,
            ),
            errors = null,
        )
    }
}
