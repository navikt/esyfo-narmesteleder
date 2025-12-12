package no.nav.syfo.narmesteleder.domain

class LinemanagerRequirementCollection(
    val linemanagerRequirements: List<LinemanagerRequirementRead>,
    val meta: PageInfo
) {
    companion object {
        const val DEFAULT_PAGE_SIZE = 50
        fun from(list: List<LinemanagerRequirementRead>, pageSize: Int): LinemanagerRequirementCollection {
            val hasMore = list.size > pageSize
            return LinemanagerRequirementCollection(
                linemanagerRequirements = if (!hasMore) list else list.dropLast(1),
                meta = PageInfo(
                    size = if (!hasMore) list.size else list.size - 1,
                    pageSize = pageSize,
                    hasMore = hasMore
                )
            )
        }
    }
}

class PageInfo(
    val size: Int,
    val pageSize: Int,
    val hasMore: Boolean
)
