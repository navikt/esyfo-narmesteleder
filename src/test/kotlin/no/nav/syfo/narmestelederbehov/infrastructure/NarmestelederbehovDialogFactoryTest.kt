package no.nav.syfo.narmestelederbehov.infrastructure

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.narmestelederbehov.application.BehovPersonName
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.domain.Attachment
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.domain.AttachmentUrlConsumerType
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.domain.ContentValueItem
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.domain.Dialog
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.domain.DialogStatus
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.domain.Url
import java.time.LocalDate
import java.util.UUID

class NarmestelederbehovDialogFactoryTest :
    FunSpec({
        val id = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val factory = NarmestelederbehovDialogFactory(
            narmestelederbehovApiUrl = "https://api.test.nav.no/api/v1/linemanager/requirement",
            frontendBaseUrl = "https://frontend.test.nav.no",
        )

        fun create(
            name: BehovPersonName? = BehovPersonName("Kari", null, "Nordmann"),
            birthDate: LocalDate? = null,
            employeeIdent: String = "02039012345",
        ): Dialog = factory.create(
            id = id,
            organizationNumber = "123456789",
            employeeIdent = employeeIdent,
            name = name,
            birthDate = birthDate,
        )

        fun Dialog.title() = content.title.value.single().value
        fun Dialog.summary() = content.summary?.value?.single()?.value

        test("addresses the dialog to the organization and refers to the behov") {
            assertSoftly(create()) {
                serviceResource shouldBe "urn:altinn:resource:nav_syfo_oppgi-narmesteleder"
                status shouldBe DialogStatus.RequiresAttention
                party shouldBe "urn:altinn:organization:identifier-no:123456789"
                externalReference shouldBe id.toString()
                isApiOnly shouldBe false
            }
        }

        test("links to the behov in the API and the frontend") {
            create().attachments shouldBe listOf(
                Attachment(
                    displayName = listOf(ContentValueItem(NarmestelederbehovDialogFactory.URL_TITLE_API, "en")),
                    urls = listOf(
                        Url(
                            url = "https://api.test.nav.no/api/v1/linemanager/requirement/$id",
                            mediaType = "application/json",
                            consumerType = AttachmentUrlConsumerType.Api,
                        ),
                    ),
                ),
                Attachment(
                    displayName = listOf(ContentValueItem(NarmestelederbehovDialogFactory.URL_TITLE_GUI, "nb")),
                    urls = listOf(
                        Url(
                            url = "https://frontend.test.nav.no/ansatte/narmesteleder/$id",
                            mediaType = "text/html",
                            consumerType = AttachmentUrlConsumerType.Gui,
                        ),
                    ),
                ),
            )
        }

        test("names the employee with the birth date from PDL") {
            assertSoftly(create(name = BehovPersonName("Kari", "Mellom", "Nordmann"), birthDate = LocalDate.of(1985, 6, 7))) {
                title() shouldBe "Oppgi nærmeste leder for Kari Mellom Nordmann (f. 07.06.1985)"
                summary() shouldBe "Kari Mellom Nordmann ${NarmestelederbehovDialogFactory.DIALOG_SUMMARY}"
            }
        }

        test("reads the birth date from the ident when PDL has none") {
            create().title() shouldBe "Oppgi nærmeste leder for Kari Nordmann (f. 02.03.9012)"
        }

        test("shows the d-nummer when the ident is not a valid date") {
            create(employeeIdent = "42039012345").title() shouldBe "Oppgi nærmeste leder for Kari Nordmann (d-nummer: 42039012345)"
        }

        test("uses generic texts without a name") {
            assertSoftly(create(name = null)) {
                title() shouldBe NarmestelederbehovDialogFactory.DIALOG_TITLE_NO_NAME
                summary() shouldBe "En ansatt ${NarmestelederbehovDialogFactory.DIALOG_SUMMARY}"
            }
        }
    })
