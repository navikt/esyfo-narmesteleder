package no.nav.syfo.narmestelederbehov.api

import no.nav.syfo.narmesteleder.domain.LinemanagerRequirementRead

class LinemanagerRequirementCollection(
    val linemanagerRequirements: List<LinemanagerRequirementRead>,
    val meta: PageInfo,
) {
    companion object {
        const val DEFAULT_PAGE_SIZE = 50
    }
}

class PageInfo(
    val size: Int,
    val pageSize: Int,
    val hasMore: Boolean,
    val total: Long,
)
