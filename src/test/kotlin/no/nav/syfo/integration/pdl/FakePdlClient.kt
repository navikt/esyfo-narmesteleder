package no.nav.syfo.integration.pdl

import net.datafaker.Faker
import no.nav.syfo.integration.pdl.Ident.Companion.GRUPPE_IDENT_FNR
import no.nav.syfo.platform.upstream.UpstreamResult
import java.util.Random

class FakePdlClient : PdlClient {
    override suspend fun getPerson(ident: String): UpstreamResult<PdlPerson?> {
        val faker = Faker(Random(ident.toLong()))
        val navn = faker.name()
        val foedselsdato = faker.timeAndDate().birthday()
        return UpstreamResult.Success(
            PdlPerson(
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
                identer = listOf(
                    Ident(ident = ident, gruppe = GRUPPE_IDENT_FNR),
                ),
            ),
        )
    }

    override suspend fun getPersonBolk(idents: List<String>): UpstreamResult<GetPersonBolkResponse> {
        val hentPersonBolk = idents.map { fnr ->
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
        val hentIdenterBolk = idents.map { fnr ->
            HentIdenterBolk(
                ident = fnr,
                identer = listOf(PdlIdent(ident = fnr, gruppe = GRUPPE_IDENT_FNR)),
                code = "ok",
            )
        }
        return UpstreamResult.Success(
            GetPersonBolkResponse(
                data = PersonBolkResponseData(
                    hentPersonBolk = hentPersonBolk,
                    hentIdenterBolk = hentIdenterBolk,
                ),
                errors = null,
            )
        )
    }
}
