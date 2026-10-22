package no.nav.syfo.narmestelederrelasjon.infrastructure

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CancellationException
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.integration.dinesykmeldte.DinesykmeldteClient
import no.nav.syfo.narmestelederrelasjon.application.ActiveSykmeldingOperation
import no.nav.syfo.narmestelederrelasjon.application.ActiveSykmeldingResult
import no.nav.syfo.platform.upstream.UpstreamError
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName
import no.nav.syfo.platform.upstream.UpstreamResult

class DinesykmeldteActiveSykmeldingLookupTest :
    FunSpec({
        val personIdent = PersonalIdentificationNumber("12345678901")
        val organizationNumber = OrganizationNumber("910000001")

        listOf(true, false).forEach { active ->
            test("returns $active and passes both identifiers to the client") {
                val client = RecordingActiveSykmeldingClient { _ -> UpstreamResult.Success(active) }
                DinesykmeldteActiveSykmeldingLookup(client).findActiveSykmelding(
                    listOf(personIdent),
                    organizationNumber,
                    ActiveSykmeldingOperation.ESTABLISH_RELATION,
                ) shouldBe
                    if (active) ActiveSykmeldingResult.Found else ActiveSykmeldingResult.NotFound
                client.requests shouldBe listOf(personIdent.value to organizationNumber.value)
            }
        }

        test("propagates client failures unchanged") {
            val failure = UpstreamFailure(UpstreamName("dinesykmeldte"), UpstreamFailureStage.RESPONSE, 503, errors = listOf(UpstreamError("server_error")))
            DinesykmeldteActiveSykmeldingLookup(RecordingActiveSykmeldingClient { _ -> UpstreamResult.Failure(failure) })
                .findActiveSykmelding(
                    listOf(personIdent),
                    organizationNumber,
                    ActiveSykmeldingOperation.ESTABLISH_RELATION,
                ) shouldBe ActiveSykmeldingResult.Unavailable(failure)
        }

        test("continues after a failure and returns a later positive result") {
            val failure = UpstreamFailure(UpstreamName("dinesykmeldte"), UpstreamFailureStage.RESPONSE, 503, errors = listOf(UpstreamError("server_error")))
            val historicalIdent = PersonalIdentificationNumber("12345678912")
            val client = RecordingActiveSykmeldingClient { ident ->
                if (ident == personIdent.value) UpstreamResult.Failure(failure) else UpstreamResult.Success(true)
            }

            DinesykmeldteActiveSykmeldingLookup(client).findActiveSykmelding(
                listOf(personIdent, historicalIdent),
                organizationNumber,
                ActiveSykmeldingOperation.GET_RELATION,
            ) shouldBe ActiveSykmeldingResult.Found
            client.requests shouldBe listOf(personIdent.value to organizationNumber.value, historicalIdent.value to organizationNumber.value)
        }

        test("records the operation, outcome, and historical hit") {
            val historicalIdent = PersonalIdentificationNumber("12345678912")
            val registry = SimpleMeterRegistry()
            val client = RecordingActiveSykmeldingClient { ident ->
                if (ident == personIdent.value) UpstreamResult.Success(false) else UpstreamResult.Success(true)
            }

            DinesykmeldteActiveSykmeldingLookup(client, registry).findActiveSykmelding(
                listOf(personIdent, historicalIdent),
                organizationNumber,
                ActiveSykmeldingOperation.GET_RELATION,
            ) shouldBe ActiveSykmeldingResult.Found

            registry.get(ACTIVE_SYKMELDING_LOOKUP_TOTAL)
                .tag("operation", "get_relation")
                .tag("outcome", "found")
                .tag("historical_hit", "true")
                .counter()
                .count() shouldBe 1.0
        }

        test("returns the first failure after checking all identities when none is positive") {
            val failure = UpstreamFailure(UpstreamName("dinesykmeldte"), UpstreamFailureStage.RESPONSE, 503, errors = listOf(UpstreamError("server_error")))
            val historicalIdent = PersonalIdentificationNumber("12345678912")
            val client = RecordingActiveSykmeldingClient { ident ->
                if (ident == personIdent.value) UpstreamResult.Failure(failure) else UpstreamResult.Success(false)
            }

            DinesykmeldteActiveSykmeldingLookup(client).findActiveSykmelding(
                listOf(personIdent, historicalIdent),
                organizationNumber,
                ActiveSykmeldingOperation.GET_RELATION,
            ) shouldBe ActiveSykmeldingResult.Unavailable(failure)
            client.requests shouldBe listOf(personIdent.value to organizationNumber.value, historicalIdent.value to organizationNumber.value)
        }

        test("propagates cancellation from the client") {
            val cancellation = CancellationException("cancelled")

            shouldThrow<CancellationException> {
                DinesykmeldteActiveSykmeldingLookup(RecordingActiveSykmeldingClient { _ -> throw cancellation })
                    .findActiveSykmelding(
                        listOf(personIdent),
                        organizationNumber,
                        ActiveSykmeldingOperation.GET_RELATION,
                    )
            } shouldBe cancellation
        }
    })

private class RecordingActiveSykmeldingClient(private val result: (String) -> UpstreamResult<Boolean>) : DinesykmeldteClient {
    val requests = mutableListOf<Pair<String, String>>()
    override suspend fun getIsActiveSykmelding(fnr: String, orgnummer: String): UpstreamResult<Boolean> {
        requests += fnr to orgnummer
        return result(fnr)
    }
}
