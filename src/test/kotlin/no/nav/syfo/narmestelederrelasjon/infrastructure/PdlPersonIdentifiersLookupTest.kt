package no.nav.syfo.narmestelederrelasjon.infrastructure

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.integration.pdl.GetPersonBolkResponse
import no.nav.syfo.integration.pdl.PdlClient
import no.nav.syfo.integration.pdl.PdlPerson
import no.nav.syfo.integration.pdl.PdlPersonIdentifiers
import no.nav.syfo.narmestelederrelasjon.application.PersonIdentifiers
import no.nav.syfo.narmestelederrelasjon.application.PersonIdentifiersLookupOperation
import no.nav.syfo.narmestelederrelasjon.application.PersonIdentifiersLookupResult
import no.nav.syfo.platform.upstream.UpstreamError
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName
import no.nav.syfo.platform.upstream.UpstreamResult

class PdlPersonIdentifiersLookupTest :
    FunSpec({
        val submittedIdent = PersonalIdentificationNumber("12345678901")
        val currentIdent = PersonalIdentificationNumber("12345678902")
        val historicalIdent = PersonalIdentificationNumber("12345678903")

        test("maps the current and all PDL identities") {
            val result = PdlPersonIdentifiersLookup(
                StubPersonIdentifiersPdlClient(
                    UpstreamResult.Success(
                        PdlPersonIdentifiers(
                            current = currentIdent.value,
                            all = listOf(currentIdent.value, historicalIdent.value),
                        ),
                    ),
                ),
            ).find(submittedIdent, PersonIdentifiersLookupOperation.ESTABLISH_RELATION)

            result shouldBe PersonIdentifiersLookupResult.Found(
                PersonIdentifiers(currentIdent, listOf(currentIdent, historicalIdent)),
            )
        }

        test("maps a missing PDL person to NotFound") {
            PdlPersonIdentifiersLookup(StubPersonIdentifiersPdlClient(UpstreamResult.Success(null))).find(
                submittedIdent,
                PersonIdentifiersLookupOperation.GET_RELATION,
            ) shouldBe
                PersonIdentifiersLookupResult.NotFound
        }

        test("propagates PDL failures as Unavailable") {
            val failure = UpstreamFailure(UpstreamName("pdl"), UpstreamFailureStage.RESPONSE, 200, errors = listOf(UpstreamError("server_error")))

            PdlPersonIdentifiersLookup(StubPersonIdentifiersPdlClient(UpstreamResult.Failure(failure))).find(
                submittedIdent,
                PersonIdentifiersLookupOperation.GET_RELATION,
            ) shouldBe
                PersonIdentifiersLookupResult.Unavailable(failure)
        }

        test("records the lookup outcome by operation") {
            val registry = SimpleMeterRegistry()

            PdlPersonIdentifiersLookup(StubPersonIdentifiersPdlClient(UpstreamResult.Success(null)), registry)
                .find(submittedIdent, PersonIdentifiersLookupOperation.GET_RELATION) shouldBe PersonIdentifiersLookupResult.NotFound

            registry.get(PERSON_IDENTIFIERS_LOOKUP_TOTAL)
                .tag("operation", "get_relation")
                .tag("outcome", "not_found")
                .counter()
                .count() shouldBe 1.0
        }
    })

private class StubPersonIdentifiersPdlClient(
    private val identifiers: UpstreamResult<PdlPersonIdentifiers?>,
) : PdlClient {
    override suspend fun getPerson(ident: String): UpstreamResult<PdlPerson?> = error("Not used")

    override suspend fun getPersonIdentifiers(ident: String): UpstreamResult<PdlPersonIdentifiers?> = identifiers

    override suspend fun getPersonBolk(idents: List<String>): UpstreamResult<GetPersonBolkResponse> = error("Not used")
}
