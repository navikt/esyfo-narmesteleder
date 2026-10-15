package no.nav.syfo.narmestelederbehov.infrastructure

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.integration.pdl.Foedselsdato
import no.nav.syfo.integration.pdl.GetPersonBolkResponse
import no.nav.syfo.integration.pdl.Ident
import no.nav.syfo.integration.pdl.Navn
import no.nav.syfo.integration.pdl.PDL
import no.nav.syfo.integration.pdl.PdlClient
import no.nav.syfo.integration.pdl.PdlPerson
import no.nav.syfo.integration.pdl.PersonResponse
import no.nav.syfo.narmestelederbehov.application.BehovPersonName
import no.nav.syfo.narmestelederbehov.application.EmployeePerson
import no.nav.syfo.narmestelederbehov.application.EmployeePersonLookupResult
import no.nav.syfo.platform.upstream.UpstreamError
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamResult
import java.time.LocalDate

class PdlEmployeePersonLookupTest :
    FunSpec({
        val employeeIdent = PersonIdent("12345678901")
        val names = listOf(Navn("Test", "Middle", "Employee"), Navn("Previous", null, "Name"))
        val person = PdlPerson(PersonResponse(names), listOf(Ident(employeeIdent.value, Ident.GRUPPE_IDENT_FNR)))

        fun lookup(result: UpstreamResult<PdlPerson?>) = PdlEmployeePersonLookup(
            object : PdlClient {
                override suspend fun getPerson(ident: String): UpstreamResult<PdlPerson?> = result
                override suspend fun getPersonBolk(idents: List<String>): UpstreamResult<GetPersonBolkResponse> = error("Not used")
            },
        )

        test("maps the first name and the first birth date") {
            val birthDates = listOf(Foedselsdato(LocalDate.of(1990, 1, 2)), Foedselsdato(LocalDate.of(1980, 3, 4)))

            lookup(UpstreamResult.Success(person.copy(person = PersonResponse(names, birthDates)))).find(employeeIdent) shouldBe
                EmployeePersonLookupResult.Found(EmployeePerson(BehovPersonName("Test", "Middle", "Employee"), LocalDate.of(1990, 1, 2)))
        }

        test("returns no birth date when PDL has none") {
            lookup(UpstreamResult.Success(person)).find(employeeIdent) shouldBe
                EmployeePersonLookupResult.Found(EmployeePerson(BehovPersonName("Test", "Middle", "Employee"), null))
        }

        test("not found and missing name or folkeregisterident give NotFound") {
            listOf(null, person.copy(person = PersonResponse(emptyList())), person.copy(identer = listOf(Ident("actor", "AKTORID")))).forEach {
                lookup(UpstreamResult.Success(it)).find(employeeIdent) shouldBe EmployeePersonLookupResult.NotFound
            }
        }

        test("propagates failure as Unavailable") {
            val failure = UpstreamFailure(PDL, UpstreamFailureStage.RESPONSE, 200, errors = listOf(UpstreamError("server_error")))

            lookup(UpstreamResult.Failure(failure)).find(employeeIdent) shouldBe EmployeePersonLookupResult.Unavailable(failure)
        }
    })
