package no.nav.syfo.narmestelederrelasjon.infrastructure

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import no.nav.esyfo.observability.testkit.RuntimeLogContract
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.integration.pdl.ErrorExtension
import no.nav.syfo.integration.pdl.Foedselsdato
import no.nav.syfo.integration.pdl.GetPersonBolkResponse
import no.nav.syfo.integration.pdl.HentPersonBolk
import no.nav.syfo.integration.pdl.Navn
import no.nav.syfo.integration.pdl.PdlClient
import no.nav.syfo.integration.pdl.PersonBolkResponseData
import no.nav.syfo.integration.pdl.ResponseError
import no.nav.syfo.integration.pdl.pdlLookupDegraded
import no.nav.syfo.logging.withProductionLogs
import no.nav.syfo.narmestelederrelasjon.application.BulkPersonLookupResult.Found
import no.nav.syfo.narmestelederrelasjon.application.BulkPersonLookupResult.NotFound
import no.nav.syfo.narmestelederrelasjon.application.RegisteredPerson
import no.nav.syfo.platform.upstream.UpstreamError
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName
import no.nav.syfo.platform.upstream.UpstreamResult
import java.time.LocalDate
import no.nav.syfo.integration.pdl.Person as PdlClientPerson

class PdlBulkPersonLookupTest :
    FunSpec({
        val pdlClient = mockk<PdlClient>()
        val lookup = PdlBulkPersonLookup(pdlClient)

        beforeTest {
            clearMocks(pdlClient)
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

        fun response(hits: List<HentPersonBolk>) = UpstreamResult.Success(
            GetPersonBolkResponse(
                data = PersonBolkResponseData(hentPersonBolk = hits, hentIdenterBolk = null),
                errors = null,
            ),
        )

        fun respond(fnrs: List<String>, vararg hits: HentPersonBolk) {
            coEvery { pdlClient.getPersonBolk(fnrs) } returns response(hits.toList())
        }

        test("maps a person found in PDL to registered details") {
            val birthDate = LocalDate.of(1985, 12, 10)
            respond(listOf(fnr(1)), hit(fnr(1), pdlPerson("Ada", "Augusta", "Lovelace", birthDate)))

            lookup.findAll(listOf(PersonalIdentificationNumber(fnr(1)))) shouldBe mapOf(
                PersonalIdentificationNumber(fnr(1)) to Found(RegisteredPerson("Ada", "Augusta", "Lovelace", birthDate)),
            )
        }

        test("reports not found when the code is not ok, the person is missing or has no name") {
            val fnrs = listOf(fnr(1), fnr(2), fnr(3))
            respond(
                fnrs,
                hit(fnr(1), code = "not_found"),
                hit(fnr(2)),
                hit(fnr(3), PdlClientPerson(navn = emptyList())),
            )

            lookup.findAll(fnrs.map(::PersonalIdentificationNumber)) shouldBe fnrs.associate { PersonalIdentificationNumber(it) to NotFound }
        }

        test("leaves persons out of the result and logs the upstream failure when their chunk fails") {
            val failedChunk = (1..100).map(::fnr)
            val succeededChunk = listOf(fnr(101), fnr(102))
            coEvery { pdlClient.getPersonBolk(failedChunk) } returns UpstreamResult.Failure(
                UpstreamFailure(UpstreamName("pdl"), UpstreamFailureStage.RESPONSE, 503, errors = listOf(UpstreamError("server_error"))),
            )
            respond(
                succeededChunk,
                hit(fnr(101), pdlPerson("Test", etternavn = "Person")),
                hit(fnr(102), code = "not_found"),
            )

            withProductionLogs(PdlBulkPersonLookup::class.java) { capture ->
                lookup.findAll((failedChunk + succeededChunk).map(::PersonalIdentificationNumber)) shouldBe mapOf(
                    PersonalIdentificationNumber(fnr(101)) to Found(RegisteredPerson("Test", null, "Person", null)),
                    PersonalIdentificationNumber(fnr(102)) to NotFound,
                )

                RuntimeLogContract.forEvents(pdlLookupDegraded).assertValid(capture.records, expectedCount = 1)
                val record = jacksonObjectMapper().readTree(capture.records.single())
                record["reason"].asText() shouldBe "BATCH_FAILED"
                record["record_count"].asInt() shouldBe 100
                record["failure_stage"].asText() shouldBe "response"
                record["upstream_status"].asInt() shouldBe 503
                record["upstream_errors"][0]["code"].asText() shouldBe "server_error"
                failedChunk.forEach { capture.records.single() shouldNotContain it }
            }
        }

        test("logs sanitized GRAPHQL_ERRORS while keeping successful data and per-ident outcomes") {
            val fnrs = listOf(fnr(1), fnr(2))
            coEvery { pdlClient.getPersonBolk(fnrs) } returns UpstreamResult.Success(
                response(listOf(hit(fnr(1), pdlPerson("Test", etternavn = "Person")), hit(fnr(2), code = "bad_request")))
                    .value.copy(
                        errors = listOf(
                            ResponseError(
                                "Ukjent problem oppsto",
                                null,
                                listOf("hentPersonBolk", "navn"),
                                ErrorExtension("server_error", null, null, "lookup_failed"),
                            ),
                        ),
                    ),
            )

            withProductionLogs(PdlBulkPersonLookup::class.java) { capture ->
                lookup.findAll(fnrs.map(::PersonalIdentificationNumber)) shouldBe mapOf(
                    PersonalIdentificationNumber(fnr(1)) to Found(RegisteredPerson("Test", null, "Person", null)),
                    PersonalIdentificationNumber(fnr(2)) to NotFound,
                )

                RuntimeLogContract.forEvents(pdlLookupDegraded).assertValid(capture.records, expectedCount = 1)
                val record = jacksonObjectMapper().readTree(capture.records.single())
                record["reason"].asText() shouldBe "GRAPHQL_ERRORS"
                record["error_count"].asInt() shouldBe 1
                val error = record["pdl_errors"][0]
                error["code"].asText() shouldBe "server_error"
                error["detail"].asText() shouldBe "lookup_failed"
                error["path"].asText() shouldBe "hentPersonBolk.navn"
                error["message"].asText() shouldBe "Ukjent problem oppsto"
                error.fieldNames().asSequence().toSet() shouldBe setOf("code", "detail", "path", "message")
                fnrs.forEach { capture.records.single() shouldNotContain it }
            }
        }

        test("rethrows cancellation") {
            coEvery { pdlClient.getPersonBolk(listOf(fnr(1))) } throws CancellationException("cancelled")

            shouldThrow<CancellationException> { lookup.findAll(listOf(PersonalIdentificationNumber(fnr(1)))) }
        }

        test("looks up persons in chunks of 100") {
            coEvery { pdlClient.getPersonBolk(any()) } answers {
                response(firstArg<List<String>>().map { hit(it, code = "not_found") })
            }

            lookup.findAll((1..201).map { PersonalIdentificationNumber(fnr(it)) }).size shouldBe 201

            coVerify(exactly = 2) { pdlClient.getPersonBolk(match { it.size == 100 }) }
            coVerify(exactly = 1) { pdlClient.getPersonBolk(match { it.size == 1 }) }
        }

        test("returns an empty result without calling PDL for no persons") {
            lookup.findAll(emptyList()).shouldBeEmpty()

            coVerify(exactly = 0) { pdlClient.getPersonBolk(any()) }
        }
    })
