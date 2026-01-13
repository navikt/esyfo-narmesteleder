package no.nav.syfo.application.database

data class ResultPage<out T>(
    val items: List<T>,
    val page: Int,
) {
    val pageSize = items.size
}
