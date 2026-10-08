package no.nav.syfo.narmestelederrelasjon.infrastructure

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.integration.aareg.AaregArbeidsforholdOversikt
import no.nav.syfo.integration.aareg.AaregClient
import no.nav.syfo.integration.aareg.Arbeidsforholdoversikt
import no.nav.syfo.integration.aareg.Arbeidssted
import no.nav.syfo.integration.aareg.ArbeidsstedType
import no.nav.syfo.integration.aareg.Ident
import no.nav.syfo.integration.aareg.IdentType
import no.nav.syfo.integration.aareg.Opplysningspliktig
import no.nav.syfo.integration.aareg.OpplysningspliktigType
import no.nav.syfo.narmestelederrelasjon.application.EmploymentHistoryResult
import no.nav.syfo.narmestelederrelasjon.domain.Employment
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName
import no.nav.syfo.platform.upstream.UpstreamResult
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException

class AaregEmploymentHistoryLookupTest :
    FunSpec({
        val personIdent = PersonIdent("12345678901")
        val organizationNumber = OrganizationNumber("910000001")

        test("calls the history operation and maps workplace organization and dates without requiring gjeldende") {
            val client = StubHistoryClient { UpstreamResult.Success(AaregArbeidsforholdOversikt(listOf(historyEmployment()))) }
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
            val client = StubHistoryClient(failure)
            AaregEmploymentHistoryLookup(client).findEmploymentHistory(personIdent) shouldBe EmploymentHistoryResult.Unavailable(failure)
        }

        test("treats Person workplaces as non-organizations even if they contain an organization ident") {
            val client = StubHistoryClient {
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
            val client = StubHistoryClient {
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
            val client = StubHistoryClient {
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
            val client = StubHistoryClient { UpstreamResult.Success(AaregArbeidsforholdOversikt()) }
            AaregEmploymentHistoryLookup(client).findEmploymentHistory(personIdent) shouldBe EmploymentHistoryResult.Found(emptyList())
        }

        test("returns unavailable for other upstream failures rather than an empty history") {
            val failure = UpstreamFailure(UpstreamName("aareg"), UpstreamFailureStage.REQUEST, null, IllegalStateException())
            val client = StubHistoryClient(failure)
            AaregEmploymentHistoryLookup(client).findEmploymentHistory(personIdent) shouldBe EmploymentHistoryResult.Unavailable(failure)
        }

        test("propagates the original cancellation") {
            val cancelled = CancellationException("cancelled")
            val lookup = AaregEmploymentHistoryLookup(StubHistoryClient { throw cancelled })
            shouldThrow<CancellationException> { lookup.findEmploymentHistory(personIdent) } shouldBe cancelled
        }
    })

private class StubHistoryClient(private val result: () -> UpstreamResult<AaregArbeidsforholdOversikt>) : AaregClient {
    constructor(failure: UpstreamFailure) : this({ UpstreamResult.Failure(failure) })

    val requests = mutableListOf<String>()

    override suspend fun getArbeidsforhold(personIdent: String): UpstreamResult<AaregArbeidsforholdOversikt> = error("Only the history operation should be called")

    override suspend fun getArbeidsforholdHistorikk(personIdent: String): UpstreamResult<AaregArbeidsforholdOversikt> {
        requests += personIdent
        return result()
    }
}

private fun historyEmployment(
    type: ArbeidsstedType = ArbeidsstedType.Underenhet,
    identer: List<Ident> = listOf(
        Ident(IdentType.AKTORID, "synthetic-actor", true),
        Ident(IdentType.ORGANISASJONSNUMMER, "910000001", false),
    ),
    startDate: LocalDate? = LocalDate.of(2025, 1, 1),
    endDate: LocalDate? = LocalDate.of(2026, 3, 31),
) = Arbeidsforholdoversikt(
    arbeidssted = Arbeidssted(type, identer),
    opplysningspliktig = Opplysningspliktig(
        OpplysningspliktigType.Hovedenhet,
        listOf(Ident(IdentType.ORGANISASJONSNUMMER, "910000003", true)),
    ),
    startdato = startDate,
    sluttdato = endDate,
)
