package no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.domain

import java.util.UUID

interface DialogContent {
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
    /** Unique per service owner; Dialogporten rejects a second dialog with the same key (3-36 characters). */
    val idempotentKey: String? = null,
) : DialogContent

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
) : DialogContent
