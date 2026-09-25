package no.nav.syfo.narmestelederbehov.infrastructure

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import no.nav.syfo.application.valkey.PdlCache
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederbehov.application.PersonDetails
import no.nav.syfo.narmestelederbehov.domain.PersonNameDetails
import no.nav.syfo.narmestelederbehov.domain.RegisteredName
import no.nav.syfo.pdl.PdlService
import no.nav.syfo.pdl.client.GetPersonBolkResponse
import no.nav.syfo.pdl.client.GetPersonResponse
import no.nav.syfo.pdl.client.Ident
import no.nav.syfo.pdl.client.IdentResponse
import no.nav.syfo.pdl.client.Navn
import no.nav.syfo.pdl.client.PdlClient
import no.nav.syfo.pdl.client.PersonResponse
import no.nav.syfo.pdl.client.ResponseData
import no.nav.syfo.pdl.exception.PdlRequestException

class PdlPersonLookupTest :
    FunSpec({
        val personIdent = PersonIdent("12345678901")

        test("maps a found person to person details") {
            val lookup = lookupReturning(
                responseWith(
                    navn = listOf(Navn("Ola", "Mellom", "Nordmann"), Navn("Ola", null, "Tidligere")),
                    fnr = personIdent.value,
                ),
            )

            lookup.find(personIdent) shouldBe PersonDetails(
                personIdent,
                PersonNameDetails(
                    firstName = "Ola",
                    middleName = "Mellom",
                    lastName = "Nordmann",
                    registeredNames = listOf(RegisteredName("Nordmann", "Mellom"), RegisteredName("Tidligere", null)),
                ),
            )
        }

        test("returns null when PDL has no name for the person") {
            lookupReturning(responseWith(navn = emptyList(), fnr = personIdent.value))
                .find(personIdent)
                .shouldBeNull()
        }

        test("returns null when PDL has no national identification number for the person") {
            lookupReturning(responseWith(navn = listOf(Navn("Ola", null, "Nordmann")), fnr = null))
                .find(personIdent)
                .shouldBeNull()
        }

        test("propagates request errors instead of treating them as not found") {
            shouldThrow<PdlRequestException> {
                lookupReturning(GetPersonResponse(data = null, errors = null)).find(personIdent)
            }
        }
    })

private fun lookupReturning(response: GetPersonResponse): PdlPersonLookup {
    // Strict mock: any use of the legacy PDL cache fails the test.
    val legacyCache = mockk<PdlCache>()
    return PdlPersonLookup(PdlService(StubPdlClient(response), legacyCache))
}

private fun responseWith(navn: List<Navn>, fnr: String?) = GetPersonResponse(
    data = ResponseData(
        person = PersonResponse(navn = navn),
        identer = IdentResponse(listOfNotNull(fnr?.let { Ident(it, Ident.GRUPPE_IDENT_FNR) })),
    ),
    errors = null,
)

private class StubPdlClient(private val response: GetPersonResponse) : PdlClient {
    override suspend fun getSystemToken(): String = error("Not used")
    override suspend fun getPerson(fnr: String): GetPersonResponse = response
    override suspend fun getPersonBolk(fnrs: List<String>, token: String): GetPersonBolkResponse = error("Not used")
}
