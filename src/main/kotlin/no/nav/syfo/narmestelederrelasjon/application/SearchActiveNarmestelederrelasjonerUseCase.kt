package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccess
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject

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
        val access = organizationAccess.evaluate(subject, command.orgNumber)
        if (access is OrganizationAccessResult.Denied) {
            return SearchActiveNarmestelederrelasjonerResult.AccessDenied(access.reason)
        }
        val text = command.text?.trim()?.takeIf(String::isNotEmpty)
        if (text != null && text.length > TEXT_MAX_LENGTH) {
            return SearchActiveNarmestelederrelasjonerResult.InvalidText
        }
        val pageSize = command.pageSize?.takeIf { it in 1..DEFAULT_PAGE_SIZE } ?: DEFAULT_PAGE_SIZE
        val cursor = command.pageToken.toLinemanagerSearchCursor()
            .getOrElse { return SearchActiveNarmestelederrelasjonerResult.InvalidPageToken }
        val identText = text?.takeIf { it.length == 11 && it.all(Char::isDigit) }
        val results = repository.search(
            NarmestelederrelasjonSearchQuery(
                orgNumber = command.orgNumber,
                managerNationalIdentificationNumber = command.managerNationalIdentificationNumber,
                employeeNationalIdentificationNumber = command.employeeNationalIdentificationNumber,
                nationalIdentificationNumber = identText?.let(::PersonIdent),
                text = text?.takeUnless { identText != null },
                hasActiveSickLeave = command.hasActiveSickLeave,
                pageSize = pageSize,
                cursor = cursor,
            ),
        )
        val hasMore = results.size > pageSize
        val visibleResults = if (hasMore) results.dropLast(1) else results
        return SearchActiveNarmestelederrelasjonerResult.Success(
            linemanagers = visibleResults.map(NarmestelederrelasjonSearchRow::linemanager),
            pageSize = pageSize,
            hasMore = hasMore,
            nextPageToken = if (hasMore) visibleResults.lastOrNull()?.cursor?.toOpaqueCursor() else null,
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
