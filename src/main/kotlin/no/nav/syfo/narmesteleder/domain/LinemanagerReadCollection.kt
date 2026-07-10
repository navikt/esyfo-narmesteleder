package no.nav.syfo.narmesteleder.domain

data class LinemanagerReadCollection(
    val linemanagers: List<LinemanagerRead>,
    val meta: LinemanagerSearchPageInfo,
) {
    companion object {
        fun from(
            results: List<LinemanagerSearchResult>,
            pageSize: Int,
            toCursor: (LinemanagerSearchCursor) -> String,
        ): LinemanagerReadCollection {
            val hasMore = results.size > pageSize
            val visibleResults = if (hasMore) results.dropLast(1) else results

            return LinemanagerReadCollection(
                linemanagers = visibleResults.map(LinemanagerSearchResult::linemanager),
                meta = LinemanagerSearchPageInfo(
                    size = visibleResults.size,
                    pageSize = pageSize,
                    hasMore = hasMore,
                    nextPageToken = if (hasMore) {
                        visibleResults.lastOrNull()?.cursor?.let(toCursor)
                    } else {
                        null
                    },
                ),
            )
        }
    }
}
data class LinemanagerSearchPageInfo(
    val size: Int,
    val pageSize: Int,
    val hasMore: Boolean,
    val nextPageToken: String?,
)

data class LinemanagerSearchResult(
    val cursor: LinemanagerSearchCursor,
    val linemanager: LinemanagerRead,
)
