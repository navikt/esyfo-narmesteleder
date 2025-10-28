package no.nav.syfo.dialogporten.domain

data class Dialog(
    val serviceResource: String,
    val party: String,
    val externalReference: String,
    val status: DialogStatus? = null,
    val content: Content,
    val transmissions: List<Transmission> = emptyList(),
    val attachments: List<Attachment>? = null,
    val isApiOnly: Boolean? = true,
)
