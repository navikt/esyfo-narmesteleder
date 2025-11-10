package no.nav.syfo.dialogporten.service

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import java.util.*
import nlBehovEntity
import no.nav.syfo.application.OtherEnvironmentProperties
import no.nav.syfo.altinn.dialogporten.client.IDialogportenClient
import no.nav.syfo.altinn.dialogporten.domain.AttachmentUrlConsumerType
import no.nav.syfo.altinn.dialogporten.domain.Dialog
import no.nav.syfo.altinn.dialogporten.domain.DialogStatus
import no.nav.syfo.altinn.dialogporten.service.DialogportenService
import no.nav.syfo.altinn.dialogporten.service.DialogportenService.Companion.DIALOG_TITLE_WITH_NAME
import no.nav.syfo.narmesteleder.db.FakeNarmestelederDb
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.pdl.PdlService
import no.nav.syfo.pdl.client.FakePdlClient

class DialogportenServiceTest : DescribeSpec({
    val dialogportenClient = mockk<IDialogportenClient>()
    val fakeNarmestelederDb = FakeNarmestelederDb()
    val spyNarmestelederDb = spyk(fakeNarmestelederDb)
    val publicIngressUrl = "https://test.nav.no"
    val frontendBaseUrl = "https://frontend.test.nav.no"
    val fakePdsClient = FakePdlClient()
    val pdlService = spyk(PdlService(fakePdsClient))

    val dialogportenService = DialogportenService(
        dialogportenClient = dialogportenClient,
        narmestelederDb = spyNarmestelederDb,
        otherEnvironmentProperties = OtherEnvironmentProperties(
            electorPath = "elector",
            publicIngressUrl = publicIngressUrl,
            frontendBaseUrl = frontendBaseUrl,
        ),
        pdlService = pdlService,
    )

    beforeTest {
        clearAllMocks()
    }
    describe("sendDocumentsToDialogporten") {
        context("when there are no behov to send") {
            it("should not call dialogporten client") {
                // Arrange
                // Act
                dialogportenService.sendDocumentsToDialogporten()

                // Assert
                coVerify(exactly = 1) { spyNarmestelederDb.getNlBehovByStatus(eq(BehovStatus.RECEIVED)) }
                coVerify(exactly = 0) { dialogportenClient.createDialog(any()) }
                coVerify(exactly = 0) { spyNarmestelederDb.updateNlBehov(any()) }
            }
        }
    }

    context("when there is one behov to send") {
        it("should send document to dialogporten and update status to COMPLETED") {
            // Arrange
            val behovEntity = nlBehovEntity()
            spyNarmestelederDb.insertNlBehov(behovEntity)
            val dialogId = UUID.randomUUID()
            val dialogSlot = slot<Dialog>()

            coEvery { dialogportenClient.createDialog(capture(dialogSlot)) } returns dialogId

            // Act
            dialogportenService.sendDocumentsToDialogporten()

            // Assert
            coVerify(exactly = 1) { spyNarmestelederDb.getNlBehovByStatus(BehovStatus.RECEIVED) }
            coVerify(exactly = 1) { dialogportenClient.createDialog(any()) }
            coVerify(exactly = 1) {
                spyNarmestelederDb.updateNlBehov(match {
                    it.id == it.id &&
                        it.dialogId == dialogId &&
                        it.behovStatus == BehovStatus.PENDING
                })
            }
            coVerify(exactly = 1) { pdlService.getPersonFor(eq(behovEntity.sykmeldtFnr)) }

            val capturedDialog = dialogSlot.captured
            capturedDialog.party shouldBe "urn:altinn:organization:identifier-no:${behovEntity.orgnummer}"
            capturedDialog.externalReference shouldBe behovEntity.id.toString()
            capturedDialog.isApiOnly shouldBe false
            capturedDialog.status shouldBe DialogStatus.RequiresAttention
            capturedDialog.attachments?.size shouldBe 2
            capturedDialog.attachments?.first()?.urls?.first()?.consumerType shouldBe AttachmentUrlConsumerType.Api
            capturedDialog.attachments?.last()?.urls?.first()?.consumerType shouldBe AttachmentUrlConsumerType.Gui
            capturedDialog.content.title.value.first().value shouldEndWith DIALOG_TITLE_WITH_NAME
        }
    }
    context("when there are multiple documents to send") {
        it("should send all documents to dialogporten") {
            // Arrange
            val behovEntity1 = nlBehovEntity()
            val behovEntity2 = nlBehovEntity()
            val dialogId1 = UUID.randomUUID()
            val dialogId2 = UUID.randomUUID()

            coEvery { spyNarmestelederDb.getNlBehovByStatus(BehovStatus.RECEIVED) } returns listOf(
                behovEntity1,
                behovEntity2
            )
            coEvery { dialogportenClient.createDialog(any()) } returnsMany listOf(dialogId1, dialogId2)
            coEvery { spyNarmestelederDb.updateNlBehov(any()) } returns Unit

            // Act
            dialogportenService.sendDocumentsToDialogporten()

            // Assert
            coVerify(exactly = 1) { spyNarmestelederDb.getNlBehovByStatus(BehovStatus.RECEIVED) }
            coVerify(exactly = 2) { dialogportenClient.createDialog(any()) }
            coVerify(exactly = 2) { spyNarmestelederDb.updateNlBehov(any()) }
        }
    }

    context("when dialogporten client throws exception") {
        it("should log error and continue without updating document status") {
            // Arrange
            val behovEntity1 = nlBehovEntity()
            coEvery { spyNarmestelederDb.getNlBehovByStatus(BehovStatus.RECEIVED) } returns listOf(behovEntity1)
            coEvery { dialogportenClient.createDialog(any()) } throws RuntimeException("Dialogporten error")

            // Act
            dialogportenService.sendDocumentsToDialogporten()

            // Assert
            coVerify(exactly = 1) { spyNarmestelederDb.getNlBehovByStatus(BehovStatus.RECEIVED) }
            coVerify(exactly = 1) { dialogportenClient.createDialog(any()) }
            coVerify(exactly = 0) { spyNarmestelederDb.updateNlBehov(any()) }
        }
    }

    context("when one dialog fails but others succeed") {
        it("should continue processing remaining documents") {
            // Arrange
            val behovEntity1 = nlBehovEntity()
            val behovEntity2 = nlBehovEntity()
            val behovEntity3 = nlBehovEntity()
            val dialogId2 = UUID.randomUUID()
            val dialogId3 = UUID.randomUUID()

            coEvery { spyNarmestelederDb.getNlBehovByStatus(BehovStatus.RECEIVED) } returns listOf(
                behovEntity1,
                behovEntity2,
                behovEntity3
            )
            coEvery { spyNarmestelederDb.updateNlBehov(any()) } returns Unit

            // First call succeeds, second fails, third succeeds
            var callCount = 0
            coEvery { dialogportenClient.createDialog(any()) } answers {
                callCount++
                when (callCount) {
                    1 -> dialogId2
                    2 -> throw RuntimeException("Error")
                    3 -> dialogId3
                    else -> throw RuntimeException("Unexpected call")
                }
            }

            // Act
            dialogportenService.sendDocumentsToDialogporten()

            // Assert
            coVerify(exactly = 1) { spyNarmestelederDb.getNlBehovByStatus(BehovStatus.RECEIVED) }
            coVerify(exactly = 3) { dialogportenClient.createDialog(any()) }
            coVerify(exactly = 2) { spyNarmestelederDb.updateNlBehov(any()) } // Only 2 successful updates
        }
    }

    context("when dialog content includes correct resource URN") {
        it("should use nav_syfo_dialog resource") {
            // Arrange
            val behovEntity1 = nlBehovEntity()
            val dialogId = UUID.randomUUID()
            val dialogSlot = slot<Dialog>()

            coEvery { spyNarmestelederDb.getNlBehovByStatus(BehovStatus.RECEIVED) } returns listOf(behovEntity1)
            coEvery { dialogportenClient.createDialog(capture(dialogSlot)) } returns dialogId
            coEvery { spyNarmestelederDb.updateNlBehov(any()) } returns Unit

            // Act
            dialogportenService.sendDocumentsToDialogporten()

            // Assert
            val capturedDialog = dialogSlot.captured
            capturedDialog.serviceResource shouldBe "urn:altinn:resource:nav_syfo_dialog"
        }
    }

    context("when dialog has attachment URLs") {
        it("should create correct document link with linkId") {
            // Arrange
            val behovEntity1 = nlBehovEntity()
            val dialogId = UUID.randomUUID()
            val dialogSlot = slot<Dialog>()

            coEvery { spyNarmestelederDb.getNlBehovByStatus(BehovStatus.RECEIVED) } returns listOf(behovEntity1)
            coEvery { dialogportenClient.createDialog(capture(dialogSlot)) } returns dialogId
            coEvery { spyNarmestelederDb.updateNlBehov(any()) } returns Unit

            // Act
            dialogportenService.sendDocumentsToDialogporten()

            // Assert
            val capturedDialog = dialogSlot.captured
            val apiAttachmentUrl = capturedDialog.attachments?.first()?.urls?.first()
            apiAttachmentUrl?.consumerType shouldBe AttachmentUrlConsumerType.Api
            apiAttachmentUrl?.url shouldBe "$publicIngressUrl/api/v1/linemanager/requirement/${behovEntity1.id}"

            val guiAttachmentUrl = capturedDialog.attachments?.last()?.urls?.first()
            guiAttachmentUrl?.consumerType shouldBe AttachmentUrlConsumerType.Gui
            guiAttachmentUrl?.url shouldBe "$frontendBaseUrl/ansatte/narmesteleder/${behovEntity1.id}"
        }
    }
})
