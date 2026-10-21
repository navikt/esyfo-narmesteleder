package no.nav.syfo.integration.aareg

import no.nav.syfo.platform.upstream.UpstreamResult
import java.time.LocalDate

internal const val DEFAULT_REPORTING_ORGANIZATION_NUMBER = "910000003"

internal fun organizationIdent(organizationNumber: String, current: Boolean = true) = Ident(IdentType.ORGANISASJONSNUMMER, organizationNumber, current)

internal fun aaregEmployment(
    workplaceIdents: List<Ident> = listOf(organizationIdent("910000001")),
    workplaceType: ArbeidsstedType = ArbeidsstedType.Underenhet,
    reportingOrganizationNumber: String? = DEFAULT_REPORTING_ORGANIZATION_NUMBER,
    startDate: LocalDate? = null,
    endDate: LocalDate? = null,
) = Arbeidsforholdoversikt(
    arbeidssted = Arbeidssted(workplaceType, workplaceIdents),
    opplysningspliktig = Opplysningspliktig(
        OpplysningspliktigType.Hovedenhet,
        listOfNotNull(reportingOrganizationNumber?.let(::organizationIdent)),
    ),
    startdato = startDate,
    sluttdato = endDate,
)

internal fun aaregOverview(vararg employments: Arbeidsforholdoversikt) = AaregArbeidsforholdOversikt(employments.toList())

/** Aareg client stub that answers only the configured operations and records the requested person idents. */
internal class StubAaregClient(
    private val current: (suspend () -> UpstreamResult<AaregArbeidsforholdOversikt>)? = null,
    private val history: (suspend () -> UpstreamResult<AaregArbeidsforholdOversikt>)? = null,
) : AaregClient {
    val requests = mutableListOf<String>()

    override suspend fun getArbeidsforhold(personIdent: String): UpstreamResult<AaregArbeidsforholdOversikt> {
        val answer = current ?: error("Unexpected current employment lookup")
        requests += personIdent
        return answer()
    }

    override suspend fun getArbeidsforholdHistorikk(personIdent: String): UpstreamResult<AaregArbeidsforholdOversikt> {
        val answer = history ?: error("Unexpected employment history lookup")
        requests += personIdent
        return answer()
    }
}
