package no.nav.syfo.narmesteleder.domain

class LinemanagerRequirementCollection(
    val linemanagerRequirements: List<LinemanagerRequirementRead>,
    val meta: PageInfo
) {
    companion object {
        const val DEFAULT_PAGE_SIZE = 50

        fun from(list: List<LinemanagerRequirementRead>, pageSize: Int, total: Long? = null): LinemanagerRequirementCollection {
            val hasMore = list.size > pageSize
            val returnedSize = if (!hasMore) list.size else list.size - 1

            require(!hasMore || total != null) { "total must be provided when the page may have more results" }
            val resolvedTotal = total ?: returnedSize.toLong()

            return LinemanagerRequirementCollection(
                linemanagerRequirements = if (!hasMore) list else list.dropLast(1),
                meta = PageInfo(
                    size = returnedSize,
                    pageSize = pageSize,
                    hasMore = hasMore,
                    total = resolvedTotal,
                )
            )
        }
    }
}

class PageInfo(
    val size: Int,
    val pageSize: Int,
    val hasMore: Boolean,
    val total: Long,
)
