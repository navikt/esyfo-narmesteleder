package no.nav.syfo.narmestelederrelasjon.infrastructure

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.integration.aareg.AaregArbeidsforholdOversikt
import no.nav.syfo.integration.aareg.ArbeidsstedType
import no.nav.syfo.integration.aareg.Ident
import no.nav.syfo.integration.aareg.IdentType
import no.nav.syfo.integration.aareg.StubAaregClient
import no.nav.syfo.integration.aareg.aaregEmployment
import no.nav.syfo.integration.aareg.organizationIdent
import no.nav.syfo.narmestelederrelasjon.application.employmentcheck.EmploymentHistoryResult
import no.nav.syfo.narmestelederrelasjon.domain.Employment
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName
import no.nav.syfo.platform.upstream.UpstreamResult
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException

class AaregEmploymentHistoryLookupTest :
    FunSpec({
        val personIdent = PersonalIdentificationNumber("12345678901")
        val organizationNumber = OrganizationNumber("910000001")

        test("calls the history operation and maps workplace organization and dates without requiring gjeldende") {
            val client = historyClient { UpstreamResult.Success(AaregArbeidsforholdOversikt(listOf(historyEmployment()))) }
            AaregEmploymentHistoryLookup(client).findEmploymentHistory(personIdent) shouldBe EmploymentHistoryResult.Found(
                listOf(
                    Employment(
                        workplaceOrganizationNumber = organizationNumber,
                        startDate = LocalDate.of(2025, 1, 1),
                        endDate = LocalDate.of(2026, 3, 31),
                    ),
                ),
            )
            client.requests shouldBe listOf(personIdent.value)
        }

        test("returns unavailable with the upstream failure when Aareg does not know the person") {
            val failure = UpstreamFailure(UpstreamName("aareg"), UpstreamFailureStage.RESPONSE, 404, IllegalStateException())
            val client = historyClient(failure)
            AaregEmploymentHistoryLookup(client).findEmploymentHistory(personIdent) shouldBe EmploymentHistoryResult.Unavailable(failure)
        }

        test("treats Person workplaces as non-organizations even if they contain an organization ident") {
            val client = historyClient {
                UpstreamResult.Success(
                    AaregArbeidsforholdOversikt(
                        listOf(historyEmployment(type = ArbeidsstedType.Person, startDate = null, endDate = null)),
                    )
                )
            }
            AaregEmploymentHistoryLookup(client).findEmploymentHistory(personIdent) shouldBe
                EmploymentHistoryResult.Found(listOf(Employment(null, null)))
        }

        test("does not substitute the reporting organization when a workplace lacks ORGANISASJONSNUMMER") {
            val client = historyClient {
                UpstreamResult.Success(
                    AaregArbeidsforholdOversikt(
                        listOf(
                            historyEmployment(identer = emptyList()),
                            historyEmployment(identer = listOf(Ident(IdentType.AKTORID, "synthetic-actor", true))),
                        ),
                    )
                )
            }
            AaregEmploymentHistoryLookup(client).findEmploymentHistory(personIdent) shouldBe EmploymentHistoryResult.Found(
                List(2) { Employment(null, LocalDate.of(2026, 3, 31), LocalDate.of(2025, 1, 1)) },
            )
        }

        test("maps malformed and blank workplace organization numbers to null in a Found result") {
            val invalidOrganizationNumbers = listOf("12345", "", "   ", "12345678X")
            val client = historyClient {
                UpstreamResult.Success(
                    AaregArbeidsforholdOversikt(
                        invalidOrganizationNumbers.map { value ->
                            historyEmployment(identer = listOf(Ident(IdentType.ORGANISASJONSNUMMER, value, true)))
                        },
                    )
                )
            }
            AaregEmploymentHistoryLookup(client).findEmploymentHistory(personIdent) shouldBe EmploymentHistoryResult.Found(
                List(invalidOrganizationNumbers.size) { Employment(null, LocalDate.of(2026, 3, 31), LocalDate.of(2025, 1, 1)) },
            )
        }

        test("returns a successful empty history when Aareg has no employments") {
            val client = historyClient { UpstreamResult.Success(AaregArbeidsforholdOversikt()) }
            AaregEmploymentHistoryLookup(client).findEmploymentHistory(personIdent) shouldBe EmploymentHistoryResult.Found(emptyList())
        }

        test("returns unavailable for other upstream failures rather than an empty history") {
            val failure = UpstreamFailure(UpstreamName("aareg"), UpstreamFailureStage.REQUEST, null, IllegalStateException())
            val client = historyClient(failure)
            AaregEmploymentHistoryLookup(client).findEmploymentHistory(personIdent) shouldBe EmploymentHistoryResult.Unavailable(failure)
        }

        test("propagates the original cancellation") {
            val cancelled = CancellationException("cancelled")
            val lookup = AaregEmploymentHistoryLookup(historyClient { throw cancelled })
            shouldThrow<CancellationException> { lookup.findEmploymentHistory(personIdent) } shouldBe cancelled
        }
    })

private fun historyClient(failure: UpstreamFailure) = historyClient { UpstreamResult.Failure(failure) }

private fun historyClient(result: suspend () -> UpstreamResult<AaregArbeidsforholdOversikt>) = StubAaregClient(history = result)

private fun historyEmployment(
    type: ArbeidsstedType = ArbeidsstedType.Underenhet,
    identer: List<Ident> = listOf(
        Ident(IdentType.AKTORID, "synthetic-actor", true),
        organizationIdent("910000001", current = false),
    ),
    startDate: LocalDate? = LocalDate.of(2025, 1, 1),
    endDate: LocalDate? = LocalDate.of(2026, 3, 31),
) = aaregEmployment(workplaceIdents = identer, workplaceType = type, startDate = startDate, endDate = endDate)
