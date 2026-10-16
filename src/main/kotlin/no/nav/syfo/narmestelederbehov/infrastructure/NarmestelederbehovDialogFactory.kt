package no.nav.syfo.narmestelederbehov.infrastructure

import io.ktor.http.ContentType
import no.nav.syfo.altinn.dialogporten.domain.Attachment
import no.nav.syfo.altinn.dialogporten.domain.AttachmentUrlConsumerType
import no.nav.syfo.altinn.dialogporten.domain.Content
import no.nav.syfo.altinn.dialogporten.domain.ContentValueItem
import no.nav.syfo.altinn.dialogporten.domain.Dialog
import no.nav.syfo.altinn.dialogporten.domain.DialogStatus
import no.nav.syfo.altinn.dialogporten.domain.Url
import no.nav.syfo.altinn.dialogporten.domain.create
import no.nav.syfo.narmestelederbehov.application.BehovPersonName
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.UUID

private const val NARMESTE_LEDER_RESOURCE = "nav_syfo_oppgi-narmesteleder"

/**
 * Builds the Dialogporten dialog that asks the employer to oppgi nærmeste leder for a behov.
 *
 * @param narmestelederbehovApiUrl absolute URL of the public narmestelederbehov endpoint, without the behov id.
 */
class NarmestelederbehovDialogFactory(
    private val narmestelederbehovApiUrl: String,
    private val frontendBaseUrl: String,
) {
    fun create(
        id: UUID,
        organizationNumber: String,
        employeeIdent: String,
        name: BehovPersonName?,
        birthDate: LocalDate?,
    ): Dialog = Dialog(
        serviceResource = "urn:altinn:resource:$NARMESTE_LEDER_RESOURCE",
        status = DialogStatus.RequiresAttention,
        party = "urn:altinn:organization:identifier-no:$organizationNumber",
        externalReference = id.toString(),
        content = Content.create(
            title = title(name, employeeIdent, birthDate),
            summary = summary(name),
        ),
        isApiOnly = false,
        attachments = listOf(
            attachment(AttachmentUrlConsumerType.Api, URL_TITLE_API, id),
            attachment(AttachmentUrlConsumerType.Gui, URL_TITLE_GUI, id),
        ),
    )

    private fun title(name: BehovPersonName?, employeeIdent: String, birthDate: LocalDate?): String = name?.let {
        "$DIALOG_TITLE_WITH_NAME ${it.fullName()} ${birthInfo(employeeIdent, birthDate)}"
    } ?: DIALOG_TITLE_NO_NAME

    private fun summary(name: BehovPersonName?): String = name?.let { "${it.fullName()} $DIALOG_SUMMARY" } ?: "En ansatt $DIALOG_SUMMARY"

    private fun attachment(type: AttachmentUrlConsumerType, displayName: String, id: UUID): Attachment = Attachment(
        displayName = listOf(ContentValueItem(displayName, if (type == AttachmentUrlConsumerType.Api) "en" else "nb")),
        urls = listOf(url(type, id)),
    )

    private fun url(type: AttachmentUrlConsumerType, id: UUID): Url = when (type) {
        AttachmentUrlConsumerType.Gui -> Url(
            url = "$frontendBaseUrl/ansatte/narmesteleder/$id",
            mediaType = ContentType.Text.Html.toString(),
            consumerType = type,
        )

        AttachmentUrlConsumerType.Api -> Url(
            url = "$narmestelederbehovApiUrl/$id",
            mediaType = ContentType.Application.Json.toString(),
            consumerType = type,
        )
    }

    private fun birthInfo(employeeIdent: String, birthDate: LocalDate?): String {
        val date = birthDate ?: birthDateFromIdent(employeeIdent)
        return if (date != null) {
            "(f. ${date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))})"
        } else {
            "(d-nummer: $employeeIdent)"
        }
    }

    private fun birthDateFromIdent(employeeIdent: String): LocalDate? = try {
        val day = employeeIdent.take(2)
        val month = employeeIdent.substring(2, 4)
        val year = employeeIdent.substring(4, 8)
        LocalDate.parse("$year-$month-$day")
    } catch (_: DateTimeParseException) {
        null
    }

    companion object {
        const val DIALOG_TITLE_NO_NAME = "Dere har en sykmeldt med behov for å bli tildelt nærmeste leder"
        const val DIALOG_TITLE_WITH_NAME = "Oppgi nærmeste leder for"
        const val DIALOG_SUMMARY =
            "er sykmeldt. Nav trenger informasjon om hvem som er nærmeste leder for å kunne gi tilgang til oppfølginstjenestene på \"Dine sykmeldte\" hos Nav"
        const val URL_TITLE_GUI = "Naviger til nærmeste leder skjema"
        const val URL_TITLE_API = "Endpoint for LinemanagerRequirement request"
    }
}

private fun BehovPersonName.fullName() = listOfNotNull(firstName, middleName, lastName).joinToString(" ")
