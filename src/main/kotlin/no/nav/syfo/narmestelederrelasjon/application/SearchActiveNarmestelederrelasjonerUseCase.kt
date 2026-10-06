package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccess
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject
import no.nav.syfo.platform.application.Step
import no.nav.syfo.platform.application.orStop

private const val DEFAULT_PAGE_SIZE = 50
private const val TEXT_MAX_LENGTH = 50

class SearchActiveNarmestelederrelasjonerUseCase(
    private val organizationAccess: OrganizationAccess,
    private val repository: NarmestelederrelasjonSearchRepository,
) {
    suspend fun execute(
        subject: OrganizationAccessSubject,
        command: SearchActiveNarmestelederrelasjonerCommand,
    ): SearchActiveNarmestelederrelasjonerResult {
        verifyAccess(subject, command.orgNumber).orStop { return it }
        val query = command.toSearchQuery().orStop { return it }
        return repository.search(query).toSuccess(query.pageSize)
    }

    private suspend fun verifyAccess(
        subject: OrganizationAccessSubject,
        orgNumber: OrganizationNumber,
    ): SearchStep<Unit> = when (val access = organizationAccess.evaluate(subject, orgNumber)) {
        is OrganizationAccessResult.Granted -> Step.Proceed
        is OrganizationAccessResult.Denied -> Step.Stop(SearchActiveNarmestelederrelasjonerResult.AccessDenied(access.reason))
    }

    private fun SearchActiveNarmestelederrelasjonerCommand.toSearchQuery(): SearchStep<NarmestelederrelasjonSearchQuery> {
        val text = text?.trim()?.takeIf(String::isNotEmpty)
        if (text != null && text.length > TEXT_MAX_LENGTH) {
            return Step.Stop(SearchActiveNarmestelederrelasjonerResult.InvalidText)
        }
        val pageSize = pageSize?.takeIf { it in 1..DEFAULT_PAGE_SIZE } ?: DEFAULT_PAGE_SIZE
        val cursor = LinemanagerSearchCursor.fromPageToken(pageToken)
            .getOrElse { return Step.Stop(SearchActiveNarmestelederrelasjonerResult.InvalidPageToken) }
        val identText = text?.takeIf { PersonIdent.isValid(it) }
        return Step.Continue(
            NarmestelederrelasjonSearchQuery(
                orgNumber = orgNumber,
                managerNationalIdentificationNumber = managerNationalIdentificationNumber,
                employeeNationalIdentificationNumber = employeeNationalIdentificationNumber,
                nationalIdentificationNumber = identText?.let(::PersonIdent),
                text = text?.takeUnless { identText != null },
                hasActiveSickLeave = hasActiveSickLeave,
                pageSize = pageSize,
                cursor = cursor,
            ),
        )
    }

    private fun List<NarmestelederrelasjonSearchRow>.toSuccess(pageSize: Int): SearchActiveNarmestelederrelasjonerResult.Success {
        val hasMore = size > pageSize
        val visibleResults = if (hasMore) dropLast(1) else this
        return SearchActiveNarmestelederrelasjonerResult.Success(
            linemanagers = visibleResults.map(NarmestelederrelasjonSearchRow::linemanager),
            pageSize = pageSize,
            hasMore = hasMore,
            nextPageToken = if (hasMore) visibleResults.lastOrNull()?.cursor?.toPageToken() else null,
        )
    }
}

data class SearchActiveNarmestelederrelasjonerCommand(
    val orgNumber: OrganizationNumber,
    val managerNationalIdentificationNumber: PersonIdent? = null,
    val employeeNationalIdentificationNumber: PersonIdent? = null,
    val hasActiveSickLeave: Boolean? = null,
    val text: String? = null,
    val pageSize: Int? = null,
    val pageToken: String? = null,
)

sealed interface SearchActiveNarmestelederrelasjonerResult {
    data class AccessDenied(val reason: DenialReason) : SearchActiveNarmestelederrelasjonerResult
    data object InvalidText : SearchActiveNarmestelederrelasjonerResult
    data object InvalidPageToken : SearchActiveNarmestelederrelasjonerResult
    data class Success(
        val linemanagers: List<SearchNarmestelederrelasjon>,
        val pageSize: Int,
        val hasMore: Boolean,
        val nextPageToken: String?,
    ) : SearchActiveNarmestelederrelasjonerResult
}

private typealias SearchStep<T> = Step<T, SearchActiveNarmestelederrelasjonerResult>
