package no.nav.syfo.narmesteleder.domain

class LinemanagerRequiremenCollection(
    val linemanagerRequirements: List<LinemanagerRequirementRead>,
    val meta: PageInfo
) {
    companion object {
        const val DEFAULT_PAGE_SIZE = 50
    }
}

class PageInfo(
    val size: Int,
    val pageSize: Int
)
