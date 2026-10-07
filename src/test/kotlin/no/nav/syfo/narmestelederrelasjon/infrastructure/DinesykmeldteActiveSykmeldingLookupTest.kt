package no.nav.syfo.narmestelederrelasjon.infrastructure

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.integration.dinesykmeldte.DinesykmeldteClient

class DinesykmeldteActiveSykmeldingLookupTest :
    FunSpec({
        val personIdent = PersonIdent("12345678901")
        val organizationNumber = OrganizationNumber("910000001")

        listOf(true, false).forEach { active ->
            test("returns $active and passes both identifiers to the client") {
                val client = RecordingActiveSykmeldingClient { active }
                DinesykmeldteActiveSykmeldingLookup(client).hasActiveSykmelding(personIdent, organizationNumber) shouldBe active
                client.requests shouldBe listOf(personIdent.value to organizationNumber.value)
            }
        }

        test("propagates client failures unchanged") {
            val failure = IllegalStateException("Dinesykmeldte unavailable")
            shouldThrow<IllegalStateException> {
                DinesykmeldteActiveSykmeldingLookup(RecordingActiveSykmeldingClient { throw failure })
                    .hasActiveSykmelding(personIdent, organizationNumber)
            } shouldBe failure
        }
    })

private class RecordingActiveSykmeldingClient(private val result: () -> Boolean) : DinesykmeldteClient {
    val requests = mutableListOf<Pair<String, String>>()
    override suspend fun getIsActiveSykmelding(fnr: String, orgnummer: String): Boolean {
        requests += fnr to orgnummer
        return result()
    }
}
