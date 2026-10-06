package no.nav.syfo.narmestelederbehov.infrastructure

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.integration.pdl.GetPersonBolkResponse
import no.nav.syfo.integration.pdl.GetPersonResponse
import no.nav.syfo.integration.pdl.Ident
import no.nav.syfo.integration.pdl.IdentResponse
import no.nav.syfo.integration.pdl.Navn
import no.nav.syfo.integration.pdl.PdlClient
import no.nav.syfo.integration.pdl.PdlRequestException
import no.nav.syfo.integration.pdl.PdlResourceNotFoundException
import no.nav.syfo.integration.pdl.PersonResponse
import no.nav.syfo.integration.pdl.ResponseData
import no.nav.syfo.narmestelederbehov.application.BehovPersonName

class PdlEmployeeNameLookupTest :
    FunSpec({
        val employeeIdent = PersonIdent("12345678901")
        val names = listOf(Navn("Test", "Middle", "Employee"), Navn("Previous", null, "Name"))

        test("maps the first name and performs a fresh lookup on every call") {
            val client = StubEmployeePdlClient { employeeResponse(names, listOf(Ident(employeeIdent.value, Ident.GRUPPE_IDENT_FNR))) }
            val lookup = PdlEmployeeNameLookup(client)
            repeat(2) {
                lookup.find(employeeIdent) shouldBe BehovPersonName("Test", "Middle", "Employee")
            }
            client.requests shouldBe listOf(employeeIdent.value, employeeIdent.value)
        }

        test("returns null when PDL reports not found") {
            PdlEmployeeNameLookup(StubEmployeePdlClient { throw PdlResourceNotFoundException("Person not found") })
                .find(employeeIdent) shouldBe null
        }

        test("returns null without a name") {
            PdlEmployeeNameLookup(
                StubEmployeePdlClient {
                    employeeResponse(emptyList(), listOf(Ident(employeeIdent.value, Ident.GRUPPE_IDENT_FNR)))
                },
            )
                .find(employeeIdent) shouldBe null
        }

        test("returns null without a folkeregisterident even when other identifiers exist") {
            PdlEmployeeNameLookup(StubEmployeePdlClient { employeeResponse(names, listOf(Ident("actor", "AKTORID"))) })
                .find(employeeIdent) shouldBe null
        }

        test("preserves validation of a malformed folkeregisterident") {
            shouldThrow<IllegalArgumentException> {
                PdlEmployeeNameLookup(
                    StubEmployeePdlClient {
                        employeeResponse(names, listOf(Ident("invalid", Ident.GRUPPE_IDENT_FNR)))
                    },
                )
                    .find(employeeIdent)
            }.message shouldBe "PersonIdent must be exactly 11 digits"
        }

        test("returns null without person or identifiers") {
            PdlEmployeeNameLookup(
                StubEmployeePdlClient {
                    GetPersonResponse(data = ResponseData(person = null, identer = null), errors = null)
                },
            )
                .find(employeeIdent) shouldBe null
            PdlEmployeeNameLookup(
                StubEmployeePdlClient {
                    GetPersonResponse(data = ResponseData(person = PersonResponse(names), identer = null), errors = null)
                },
            )
                .find(employeeIdent) shouldBe null
        }

        test("null data preserves the request error message") {
            val failure = shouldThrow<PdlRequestException> {
                PdlEmployeeNameLookup(StubEmployeePdlClient { GetPersonResponse(data = null, errors = null) }).find(employeeIdent)
            }
            failure.message shouldBe "Unexpected response from upstream service"
        }

        test("propagates other PDL request failures unchanged") {
            val failure = PdlRequestException("PDL unavailable")
            shouldThrow<PdlRequestException> {
                PdlEmployeeNameLookup(StubEmployeePdlClient { throw failure }).find(employeeIdent)
            } shouldBe failure
        }
    })

private class StubEmployeePdlClient(private val result: () -> GetPersonResponse) : PdlClient {
    val requests = mutableListOf<String>()
    override suspend fun getSystemToken(): String = error("Not used")
    override suspend fun getPerson(fnr: String): GetPersonResponse {
        requests += fnr
        return result()
    }
    override suspend fun getPersonBolk(fnrs: List<String>, token: String): GetPersonBolkResponse = error("Not used")
}

private fun employeeResponse(names: List<Navn>, identifiers: List<Ident>) = GetPersonResponse(
    data = ResponseData(person = PersonResponse(names), identer = IdentResponse(identifiers)),
    errors = null,
)
