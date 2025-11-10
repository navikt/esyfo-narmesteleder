package no.nav.syfo.altinn.dialogporten.domain

data class Attachment(
    val displayName: List<ContentValueItem>,
    val urls: List<Url>,
)

data class Url(
    val url: String,
    val mediaType: String,
    val consumerType: AttachmentUrlConsumerType,
)

enum class AttachmentUrlConsumerType {
    Gui,
    Api,
}
