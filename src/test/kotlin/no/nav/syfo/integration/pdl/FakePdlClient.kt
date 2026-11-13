package no.nav.syfo.integration.pdl

import no.nav.syfo.TestDataFaker
import no.nav.syfo.integration.pdl.Ident.Companion.GRUPPE_IDENT_FNR
import no.nav.syfo.platform.upstream.UpstreamResult

class FakePdlClient : PdlClient {
    override suspend fun getPerson(ident: String): UpstreamResult<PdlPerson?> {
        val faker = TestDataFaker(seed = ident.toLong())
        val foedselsdato = faker.birthday()
        return UpstreamResult.Success(
            PdlPerson(
                person = PersonResponse(
                    navn = listOf(
                        Navn(
                            fornavn = faker.firstName(),
                            mellomnavn = faker.middleName(),
                            etternavn = faker.lastName(),
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
            val faker = TestDataFaker(seed = fnr.toLong())
            HentPersonBolk(
                ident = fnr,
                person = Person(
                    navn = listOf(
                        Navn(
                            fornavn = faker.firstName(),
                            mellomnavn = faker.middleName(),
                            etternavn = faker.lastName(),
                        ),
                    ),
                    foedselsdato = listOf(Foedselsdato(faker.birthday())),
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
