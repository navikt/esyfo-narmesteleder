package no.nav.syfo.altinn.dialogporten.domain

data class Content(
    val title: ContentValue,
    val summary: ContentValue? = null,
) {
    companion object
}

data class ContentValue(
    val value: List<ContentValueItem>,
    val mediaType: String = "text/plain",
)

data class ContentValueItem(
    val value: String,
    val languageCode: String = "nb",
)

fun String.toContentValue() =
    ContentValue(
        value = listOf(ContentValueItem(this)),
    )

fun Content.Companion.create(
    title: String,
    summary: String?,
): Content = Content(title.toContentValue(), summary?.toContentValue())
