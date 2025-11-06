package no.nav.syfo.dialogporten.domain

import java.util.UUID

interface IDialog {
    val serviceResource: String
    val party: String
    val externalReference: String
    val status: DialogStatus?
    val content: Content
    val transmissions: List<Transmission>
    val attachments: List<Attachment>?
    val isApiOnly: Boolean?
}

data class Dialog(
    override val party: String,
    override val serviceResource: String,
    override val externalReference: String,
    override val status: DialogStatus? = null,
    override val content: Content,
    override val transmissions: List<Transmission> = emptyList(),
    override val attachments: List<Attachment>? = null,
    override val isApiOnly: Boolean? = true,
) : IDialog

data class ExtendedDialog(
    val revision: UUID,
    val id: UUID,
    override val party: String,
    override val serviceResource: String,
    override val externalReference: String,
    override val status: DialogStatus? = null,
    override val content: Content,
    override val transmissions: List<Transmission> = emptyList(),
    override val attachments: List<Attachment>? = null,
    override val isApiOnly: Boolean? = true,
) : IDialog
