package no.nav.syfo.narmestelederbehov.infrastructure

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.integration.pdl.GetPersonBolkResponse
import no.nav.syfo.integration.pdl.Ident
import no.nav.syfo.integration.pdl.Navn
import no.nav.syfo.integration.pdl.PdlClient
import no.nav.syfo.integration.pdl.PdlPerson
import no.nav.syfo.integration.pdl.PdlPersonIdentifiers
import no.nav.syfo.integration.pdl.PersonResponse
import no.nav.syfo.narmestelederbehov.application.BehovPersonName
import no.nav.syfo.narmestelederbehov.application.EmployeeNameLookupResult
import no.nav.syfo.platform.upstream.UpstreamError
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName
import no.nav.syfo.platform.upstream.UpstreamResult

class PdlEmployeeNameLookupTest :
    FunSpec({
        val ident = PersonalIdentificationNumber("12345678901")
        val person = PdlPerson(
            PersonResponse(listOf(Navn("Test", "Middle", "Employee"), Navn("Previous", null, "Name"))),
            listOf(Ident(ident.value, Ident.GRUPPE_IDENT_FNR)),
        )

        test("maps the first name and performs a fresh lookup on every call") {
            val client = StubEmployeePdlClient(UpstreamResult.Success(person))
            val lookup = PdlEmployeeNameLookup(client)
            repeat(2) { lookup.find(ident) shouldBe EmployeeNameLookupResult.Found(BehovPersonName("Test", "Middle", "Employee")) }
            client.requests shouldBe listOf(ident.value, ident.value)
        }

        test("not found and missing name or folkeregisterident give NotFound") {
            listOf(null, person.copy(person = PersonResponse(emptyList())), person.copy(identer = listOf(Ident("actor", "AKTORID")))).forEach {
                PdlEmployeeNameLookup(StubEmployeePdlClient(UpstreamResult.Success(it))).find(ident) shouldBe EmployeeNameLookupResult.NotFound
            }
        }

        test("preserves validation of a malformed folkeregisterident") {
            shouldThrow<IllegalArgumentException> {
                PdlEmployeeNameLookup(StubEmployeePdlClient(UpstreamResult.Success(person.copy(identer = listOf(Ident("invalid", Ident.GRUPPE_IDENT_FNR)))))).find(ident)
            }.message shouldBe "PersonalIdentificationNumber must be exactly 11 digits"
        }

        test("propagates failure as Unavailable without interpreting the protocol") {
            val failure = UpstreamFailure(UpstreamName("pdl"), UpstreamFailureStage.RESPONSE, 200, errors = listOf(UpstreamError("server_error")))
            PdlEmployeeNameLookup(StubEmployeePdlClient(UpstreamResult.Failure(failure))).find(ident) shouldBe EmployeeNameLookupResult.Unavailable(failure)
        }
    })

private class StubEmployeePdlClient(private val result: UpstreamResult<PdlPerson?>) : PdlClient {
    val requests = mutableListOf<String>()
    override suspend fun getPerson(ident: String): UpstreamResult<PdlPerson?> {
        requests += ident
        return result
    }
    override suspend fun getPersonIdentifiers(ident: String): UpstreamResult<PdlPersonIdentifiers?> = UpstreamResult.Success(null)
    override suspend fun getPersonBolk(idents: List<String>): UpstreamResult<GetPersonBolkResponse> = error("Not used")
}
