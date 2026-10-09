package no.nav.syfo.narmestelederrelasjon.infrastructure

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.integration.pdl.Foedselsdato
import no.nav.syfo.integration.pdl.GetPersonBolkResponse
import no.nav.syfo.integration.pdl.HentPersonBolk
import no.nav.syfo.integration.pdl.Navn
import no.nav.syfo.integration.pdl.PdlClient
import no.nav.syfo.integration.pdl.PdlRequestException
import no.nav.syfo.integration.pdl.PersonBolkResponseData
import no.nav.syfo.narmestelederrelasjon.application.BulkPersonLookupResult.Found
import no.nav.syfo.narmestelederrelasjon.application.BulkPersonLookupResult.NotFound
import no.nav.syfo.narmestelederrelasjon.application.RegisteredPerson
import java.time.LocalDate
import no.nav.syfo.integration.pdl.Person as PdlClientPerson

class PdlBulkPersonLookupTest :
    FunSpec({
        val token = "token"
        val pdlClient = mockk<PdlClient>()
        val lookup = PdlBulkPersonLookup(pdlClient)

        beforeTest {
            clearMocks(pdlClient)
            coEvery { pdlClient.getSystemToken() } returns token
        }

        fun fnr(n: Int) = n.toString().padStart(11, '0')

        fun pdlPerson(
            fornavn: String,
            mellomnavn: String? = null,
            etternavn: String,
            birthDate: LocalDate? = null,
        ) = PdlClientPerson(
            navn = listOf(Navn(fornavn = fornavn, mellomnavn = mellomnavn, etternavn = etternavn)),
            foedselsdato = listOfNotNull(birthDate?.let(::Foedselsdato)),
        )

        fun hit(fnr: String, person: PdlClientPerson? = null, code: String = "ok") = HentPersonBolk(fnr, person, code)

        fun response(hits: List<HentPersonBolk>) = GetPersonBolkResponse(
            data = PersonBolkResponseData(hentPersonBolk = hits, hentIdenterBolk = null),
            errors = null,
        )

        fun respond(fnrs: List<String>, vararg hits: HentPersonBolk) {
            coEvery { pdlClient.getPersonBolk(fnrs, token) } returns response(hits.toList())
        }

        test("maps a person found in PDL to registered details") {
            val birthDate = LocalDate.of(1985, 12, 10)
            respond(listOf(fnr(1)), hit(fnr(1), pdlPerson("Ada", "Augusta", "Lovelace", birthDate)))

            lookup.findAll(listOf(PersonIdent(fnr(1)))) shouldBe mapOf(
                PersonIdent(fnr(1)) to Found(RegisteredPerson("Ada", "Augusta", "Lovelace", birthDate)),
            )
            coVerify(exactly = 1) { pdlClient.getSystemToken() }
        }

        test("reports not found when the code is not ok, the person is missing or has no name") {
            val fnrs = listOf(fnr(1), fnr(2), fnr(3))
            respond(
                fnrs,
                hit(fnr(1), code = "not_found"),
                hit(fnr(2)),
                hit(fnr(3), PdlClientPerson(navn = emptyList())),
            )

            lookup.findAll(fnrs.map(::PersonIdent)) shouldBe fnrs.associate { PersonIdent(it) to NotFound }
        }

        test("leaves persons out of the result when their chunk fails") {
            val failedChunk = (1..100).map(::fnr)
            val succeededChunk = listOf(fnr(101), fnr(102))
            coEvery { pdlClient.getPersonBolk(failedChunk, token) } throws PdlRequestException("PDL error")
            respond(
                succeededChunk,
                hit(fnr(101), pdlPerson("Test", etternavn = "Person")),
                hit(fnr(102), code = "not_found"),
            )

            lookup.findAll((failedChunk + succeededChunk).map(::PersonIdent)) shouldBe mapOf(
                PersonIdent(fnr(101)) to Found(RegisteredPerson("Test", null, "Person", null)),
                PersonIdent(fnr(102)) to NotFound,
            )
        }

        test("rethrows cancellation") {
            coEvery { pdlClient.getPersonBolk(listOf(fnr(1)), token) } throws CancellationException("cancelled")

            shouldThrow<CancellationException> { lookup.findAll(listOf(PersonIdent(fnr(1)))) }
        }

        test("fetches the token once and looks up persons in chunks of 100") {
            coEvery { pdlClient.getPersonBolk(any(), token) } answers {
                response(firstArg<List<String>>().map { hit(it, code = "not_found") })
            }

            lookup.findAll((1..201).map { PersonIdent(fnr(it)) }).size shouldBe 201

            coVerify(exactly = 1) { pdlClient.getSystemToken() }
            coVerify(exactly = 2) { pdlClient.getPersonBolk(match { it.size == 100 }, token) }
            coVerify(exactly = 1) { pdlClient.getPersonBolk(match { it.size == 1 }, token) }
        }

        test("returns an empty result without fetching a token for no persons") {
            lookup.findAll(emptyList()).shouldBeEmpty()

            coVerify(exactly = 0) { pdlClient.getSystemToken() }
        }
    })
