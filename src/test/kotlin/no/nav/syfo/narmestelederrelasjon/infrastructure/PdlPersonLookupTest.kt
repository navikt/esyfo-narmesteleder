package no.nav.syfo.narmestelederrelasjon.infrastructure

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.integration.pdl.GetPersonBolkResponse
import no.nav.syfo.integration.pdl.Ident
import no.nav.syfo.integration.pdl.Navn
import no.nav.syfo.integration.pdl.PdlClient
import no.nav.syfo.integration.pdl.PdlPerson
import no.nav.syfo.integration.pdl.PersonResponse
import no.nav.syfo.narmestelederrelasjon.application.PersonDetails
import no.nav.syfo.narmestelederrelasjon.application.PersonLookupResult
import no.nav.syfo.narmestelederrelasjon.domain.PersonNameDetails
import no.nav.syfo.narmestelederrelasjon.domain.RegisteredName
import no.nav.syfo.platform.upstream.UpstreamError
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName
import no.nav.syfo.platform.upstream.UpstreamResult

class PdlPersonLookupTest :
    FunSpec({
        val ident = PersonalIdentificationNumber("12345678901")
        val person = PdlPerson(
            PersonResponse(listOf(Navn("Test", "Middle", "Name"), Navn("Test", null, "Previous"))),
            listOf(Ident(ident.value, Ident.GRUPPE_IDENT_FNR)),
        )

        test("maps the resolved ident and registered names") {
            PdlPersonLookup(StubPdlClient(UpstreamResult.Success(person))).find(ident) shouldBe PersonLookupResult.Found(
                PersonDetails(ident, PersonNameDetails("Test", "Name", "Middle", listOf(RegisteredName("Name", "Middle"), RegisteredName("Previous", null)))),
            )
        }

        test("not found and missing name or folkeregisterident give NotFound") {
            listOf(null, person.copy(person = PersonResponse(emptyList())), person.copy(identer = emptyList())).forEach {
                PdlPersonLookup(StubPdlClient(UpstreamResult.Success(it))).find(ident) shouldBe PersonLookupResult.NotFound
            }
        }

        test("propagates failure as Unavailable without interpreting the protocol") {
            val failure = UpstreamFailure(UpstreamName("pdl"), UpstreamFailureStage.RESPONSE, 200, errors = listOf(UpstreamError("server_error")))
            PdlPersonLookup(StubPdlClient(UpstreamResult.Failure(failure))).find(ident) shouldBe PersonLookupResult.Unavailable(failure)
        }
    })

private class StubPdlClient(private val result: UpstreamResult<PdlPerson?>) : PdlClient {
    override suspend fun getPerson(ident: String) = result
    override suspend fun getPersonBolk(idents: List<String>): UpstreamResult<GetPersonBolkResponse> = error("Not used")
}
