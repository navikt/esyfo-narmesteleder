package no.nav.syfo.narmestelederbehov.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.logging.applicationLogger
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccess
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject
import no.nav.syfo.platform.application.Step
import no.nav.syfo.platform.application.orStop
import java.time.Instant

data class ListNarmestelederbehovQuery(
    val organizationNumber: OrganizationNumber,
    val createdAfter: Instant,
    val pageSize: Int,
    val subject: OrganizationAccessSubject,
)

data class ListedNarmestelederbehov(val behov: NarmestelederbehovDetails, val name: BehovPersonName)

sealed interface ListNarmestelederbehovResult {
    data class Listed(
        val behov: List<ListedNarmestelederbehov>,
        val hasMore: Boolean,
        val total: Long,
        val organizationName: String?,
    ) : ListNarmestelederbehovResult
    data class AccessDenied(val reason: DenialReason, val organizationNumber: OrganizationNumber) : ListNarmestelederbehovResult
    data object PersonNotFound : ListNarmestelederbehovResult
}

class ListNarmestelederbehovUseCase(
    private val repository: OpenNarmestelederbehovRepository,
    private val organizationAccess: OrganizationAccess,
    private val employeeName: NarmestelederbehovEmployeeName,
) {
    suspend fun execute(query: ListNarmestelederbehovQuery): ListNarmestelederbehovResult = list(query).log()

    private suspend fun list(query: ListNarmestelederbehovQuery): ListNarmestelederbehovResult {
        val organizationName = verifyOrganizationAccess(query).orStop { return it }
        val fetched = repository.findOpen(query.organizationNumber, query.createdAfter, limit = query.pageSize + 1)
        val named = resolveNames(fetched).orStop { return it }
        val hasMore = fetched.size > query.pageSize
        val returned = named.take(query.pageSize)
        val total = if (hasMore) repository.countOpen(query.organizationNumber, query.createdAfter) else returned.size.toLong()
        return ListNarmestelederbehovResult.Listed(returned, hasMore, total, organizationName)
    }

    private suspend fun verifyOrganizationAccess(query: ListNarmestelederbehovQuery): ListStep<String?> = when (val access = organizationAccess.evaluate(subject = query.subject, organizationNumber = query.organizationNumber)) {
        is OrganizationAccessResult.Granted -> Step.Continue(access.organizationName)
        is OrganizationAccessResult.Denied -> Step.Stop(ListNarmestelederbehovResult.AccessDenied(access.reason, query.organizationNumber))
    }

    private suspend fun resolveNames(fetched: List<NarmestelederbehovDetails>): ListStep<List<ListedNarmestelederbehov>> {
        val named = mutableListOf<ListedNarmestelederbehov>()
        // Legacy parity: resolve all fetched names sequentially, including the overflow row.
        for (behov in fetched) {
            val name = employeeName.resolve(behov) ?: return Step.Stop(ListNarmestelederbehovResult.PersonNotFound)
            named += ListedNarmestelederbehov(behov, name)
        }
        return Step.Continue(named)
    }

    private fun ListNarmestelederbehovResult.log(): ListNarmestelederbehovResult = also {
        if (it !is ListNarmestelederbehovResult.Listed) logger.event(listNarmestelederbehovRejected, it)
    }

    private companion object {
        val logger = applicationLogger(ListNarmestelederbehovUseCase::class.java)
    }
}

private typealias ListStep<T> = Step<T, ListNarmestelederbehovResult>
