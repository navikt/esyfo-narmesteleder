package no.nav.syfo.dialogporten.service

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import nlBehovEntity
import no.nav.syfo.altinn.dialogporten.client.IDialogportenClient
import no.nav.syfo.altinn.dialogporten.domain.AttachmentUrlConsumerType
import no.nav.syfo.altinn.dialogporten.domain.Content
import no.nav.syfo.altinn.dialogporten.domain.ContentValue
import no.nav.syfo.altinn.dialogporten.domain.ContentValueItem
import no.nav.syfo.altinn.dialogporten.domain.Dialog
import no.nav.syfo.altinn.dialogporten.domain.DialogStatus
import no.nav.syfo.altinn.dialogporten.domain.ExtendedDialog
import no.nav.syfo.altinn.dialogporten.service.DialogportenService
import no.nav.syfo.application.environment.DeleteDialogportenDialogsTaskProperties
import no.nav.syfo.application.environment.OtherEnvironmentProperties
import no.nav.syfo.application.environment.UpdateDialogportenTaskProperties
import no.nav.syfo.application.valkey.PdlCache
import no.nav.syfo.narmesteleder.db.FakeNarmestelederDb
import no.nav.syfo.narmesteleder.db.NarmestelederBehovEntity
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.pdl.PdlService
import no.nav.syfo.pdl.client.FakePdlClient
import java.util.*

class DialogportenServiceTest :
    DescribeSpec({
        val dialogportenClient = mockk<IDialogportenClient>()
        val publicIngressUrl = "https://test.nav.no"
        val frontendBaseUrl = "https://frontend.test.nav.no"
        val fakePdsClient = FakePdlClient()
        val pdlCacheMock = mockk<PdlCache>()
        val pdlService = spyk(PdlService(fakePdsClient, pdlCacheMock))

        lateinit var fakeNarmestelederDb: FakeNarmestelederDb
        lateinit var spyNarmestelederDb: FakeNarmestelederDb
        lateinit var dialogportenService: DialogportenService

        beforeTest {
            clearAllMocks()
            fakeNarmestelederDb = FakeNarmestelederDb()
            spyNarmestelederDb = spyk(fakeNarmestelederDb)
            dialogportenService =
                DialogportenService(
                    dialogportenClient = dialogportenClient,
                    narmestelederDb = spyNarmestelederDb,
                    otherEnvironmentProperties =
                    OtherEnvironmentProperties(
                        electorPath = "elector",
                        publicIngressUrl = publicIngressUrl,
                        frontendBaseUrl = frontendBaseUrl,
                        persistLeesahNlBehov = true,
                        updateDialogportenTaskProperties = UpdateDialogportenTaskProperties.createForLocal(),
                        isDialogportenBackgroundTaskEnabled = true,
                        dialogportenIsApiOnly = false,
                        deleteDialogportenDialogsTaskProperties = DeleteDialogportenDialogsTaskProperties.createForLocal(),
                        persistSendtSykmelding = false,
                        daysAfterTomToExpireBehovs = 7,
                        maintenanceTaskDelay = "1s",
                    ),
                    pdlService = pdlService,
                )
            spyNarmestelederDb.clear()
        }
        describe("sendDocumentsToDialogporten") {
            context("when there are no behov to send") {
                it("should not call dialogporten client") {
                    // Arrange
                    // Act
                    dialogportenService.sendDocumentsToDialogporten()

                    // Assert
                    coVerify(exactly = 1) { spyNarmestelederDb.getNlBehovByStatus(eq(BehovStatus.BEHOV_CREATED)) }
                    coVerify(exactly = 0) { dialogportenClient.createDialog(any()) }
                    coVerify(exactly = 0) { spyNarmestelederDb.updateNlBehov(any()) }
                }
            }

            context("when there is one behov to send") {
                it("should send document to dialogporten and update status to DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION") {
                    // Arrange
                    val behovEntity = spyNarmestelederDb.insertNlBehov(nlBehovEntity())
                    val dialogId = UUID.randomUUID()
                    val dialogSlot = slot<Dialog>()

                    coEvery { dialogportenClient.createDialog(capture(dialogSlot)) } returns dialogId

                    // Act
                    dialogportenService.sendDocumentsToDialogporten()

                    // Assert
                    coVerify(exactly = 1) { spyNarmestelederDb.getNlBehovByStatus(BehovStatus.BEHOV_CREATED) }
                    coVerify(exactly = 1) { dialogportenClient.createDialog(any()) }
                    coVerify(exactly = 1) {
                        spyNarmestelederDb.updateNlBehov(
                            match {
                                it.id == it.id &&
                                    it.dialogId == dialogId &&
                                    it.behovStatus == BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION
                            },
                        )
                    }
                    coVerify(exactly = 1) { pdlService.getPersonFor(eq(behovEntity.sykmeldtFnr)) }

                    val capturedDialog = dialogSlot.captured
                    capturedDialog.party shouldBe "urn:altinn:organization:identifier-no:${behovEntity.orgnummer}"
                    capturedDialog.externalReference shouldBe behovEntity.id.toString()
                    capturedDialog.isApiOnly shouldBe false
                    capturedDialog.status shouldBe DialogStatus.RequiresAttention
                    capturedDialog.attachments?.size shouldBe 2
                    capturedDialog.attachments
                        ?.first()
                        ?.urls
                        ?.first()
                        ?.consumerType shouldBe AttachmentUrlConsumerType.Api
                    capturedDialog.attachments
                        ?.last()
                        ?.urls
                        ?.first()
                        ?.consumerType shouldBe AttachmentUrlConsumerType.Gui
                    capturedDialog.content.title.value
                        .first()
                        .value shouldStartWith "Oppgi nærmeste leder for"
                    capturedDialog.content.title.value
                        .first()
                        .value shouldEndWith " (d-nummer: ${behovEntity.sykmeldtFnr})"
                }
            }
            context("when there are multiple documents to send") {
                it("should send all documents to dialogporten") {
                    // Arrange
                    val behovEntity1 = nlBehovEntity()
                    val behovEntity2 = nlBehovEntity()
                    val dialogId1 = UUID.randomUUID()
                    val dialogId2 = UUID.randomUUID()

                    coEvery { spyNarmestelederDb.getNlBehovByStatus(BehovStatus.BEHOV_CREATED) } returns
                        listOf(
                            behovEntity1,
                            behovEntity2,
                        )
                    coEvery { dialogportenClient.createDialog(any()) } returnsMany listOf(dialogId1, dialogId2)
                    coEvery { spyNarmestelederDb.updateNlBehov(any()) } returns Unit

                    // Act
                    dialogportenService.sendDocumentsToDialogporten()

                    // Assert
                    coVerify(exactly = 1) { spyNarmestelederDb.getNlBehovByStatus(BehovStatus.BEHOV_CREATED) }
                    coVerify(exactly = 2) { dialogportenClient.createDialog(any()) }
                    coVerify(exactly = 2) { spyNarmestelederDb.updateNlBehov(any()) }
                }
            }

            context("when dialogporten client throws exception") {
                it("should log error and continue without updating document status") {
                    // Arrange
                    val behovEntity1 = nlBehovEntity()
                    coEvery { spyNarmestelederDb.getNlBehovByStatus(BehovStatus.BEHOV_CREATED) } returns listOf(behovEntity1)
                    coEvery { dialogportenClient.createDialog(any()) } throws RuntimeException("Dialogporten error")

                    // Act
                    dialogportenService.sendDocumentsToDialogporten()

                    // Assert
                    coVerify(exactly = 1) { spyNarmestelederDb.getNlBehovByStatus(BehovStatus.BEHOV_CREATED) }
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

                    coEvery { spyNarmestelederDb.getNlBehovByStatus(BehovStatus.BEHOV_CREATED) } returns
                        listOf(
                            behovEntity1,
                            behovEntity2,
                            behovEntity3,
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
                    coVerify(exactly = 1) { spyNarmestelederDb.getNlBehovByStatus(BehovStatus.BEHOV_CREATED) }
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

                    coEvery { spyNarmestelederDb.getNlBehovByStatus(BehovStatus.BEHOV_CREATED) } returns listOf(behovEntity1)
                    coEvery { dialogportenClient.createDialog(capture(dialogSlot)) } returns dialogId
                    coEvery { spyNarmestelederDb.updateNlBehov(any()) } returns Unit

                    // Act
                    dialogportenService.sendDocumentsToDialogporten()

                    // Assert
                    val capturedDialog = dialogSlot.captured
                    capturedDialog.serviceResource shouldBe "urn:altinn:resource:nav_syfo_oppgi-narmesteleder"
                }
            }

            context("when dialog has attachment URLs") {
                it("should create correct document link with linkId") {
                    // Arrange
                    val behovEntity1 = nlBehovEntity()
                    val dialogId = UUID.randomUUID()
                    val dialogSlot = slot<Dialog>()

                    coEvery { spyNarmestelederDb.getNlBehovByStatus(BehovStatus.BEHOV_CREATED) } returns listOf(behovEntity1)
                    coEvery { dialogportenClient.createDialog(capture(dialogSlot)) } returns dialogId
                    coEvery { spyNarmestelederDb.updateNlBehov(any()) } returns Unit

                    // Act
                    dialogportenService.sendDocumentsToDialogporten()

                    // Assert
                    val capturedDialog = dialogSlot.captured
                    val apiAttachmentUrl =
                        capturedDialog.attachments
                            ?.first()
                            ?.urls
                            ?.first()
                    apiAttachmentUrl?.consumerType shouldBe AttachmentUrlConsumerType.Api
                    apiAttachmentUrl?.url shouldBe "$publicIngressUrl/api/v1/linemanager/requirement/${behovEntity1.id}"

                    val guiAttachmentUrl =
                        capturedDialog.attachments
                            ?.last()
                            ?.urls
                            ?.first()
                    guiAttachmentUrl?.consumerType shouldBe AttachmentUrlConsumerType.Gui
                    guiAttachmentUrl?.url shouldBe "$frontendBaseUrl/ansatte/narmesteleder/${behovEntity1.id}"
                }
            }
        }

        describe("Update status in dialogporten when behov is fulfilled") {
            context("when there are no fulfilled behovs") {
                it("should not call dialogporten client") {
                    // Arrange
                    // Act
                    dialogportenService.setAllFulfilledBehovsAsCompletedInDialogporten()

                    // Assert
                    coVerify(exactly = 1) {
                        spyNarmestelederDb.getNlBehovByStatus(
                            BehovStatus.BEHOV_FULFILLED,
                            DialogportenService.BEHOV_BY_STATUS_LIMIT
                        )
                    }
                    coVerify(exactly = 0) { dialogportenClient.getDialogById(any()) }
                    coVerify(exactly = 0) { dialogportenClient.updateDialogStatus(any(), any(), any()) }
                    coVerify(exactly = 0) { spyNarmestelederDb.updateNlBehov(any()) }
                }
            }

            context("when there is one fulfilled behov") {
                it("should update dialogporten status to Completed and update status in database to DIALOGPORTEN_STATUS_SET_COMPLETED") {
                    // Arrange
                    val behovEntity =
                        spyNarmestelederDb.insertNlBehov(
                            nlBehovEntity().copy(
                                behovStatus = BehovStatus.BEHOV_FULFILLED,
                                dialogId = UUID.randomUUID(),
                            ),
                        )
                    coEvery { dialogportenClient.updateDialogStatus(any(), any(), any()) } just Runs
                    coEvery { dialogportenClient.getDialogById(eq(behovEntity.dialogId!!)) } returns
                        ExtendedDialog(
                            id = UUID.randomUUID(),
                            externalReference = behovEntity.dialogId.toString(),
                            party = "urn:altinn:organization:identifier-no:123456789",
                            status = DialogStatus.RequiresAttention,
                            isApiOnly = false,
                            attachments = emptyList(),
                            revision = UUID.randomUUID(),
                            content =
                            Content(
                                title = ContentValue(value = listOf(ContentValueItem(value = "Test content title"))),
                                summary = ContentValue(value = listOf(ContentValueItem(value = "Test content summary"))),
                            ),
                            serviceResource = "service:resource",
                            transmissions = listOf(),
                        )

                    // Act
                    dialogportenService.setAllFulfilledBehovsAsCompletedInDialogporten()

                    // Assert
                    coVerify(exactly = 1) {
                        spyNarmestelederDb.getNlBehovByStatus(
                            eq(BehovStatus.BEHOV_FULFILLED),
                            DialogportenService.BEHOV_BY_STATUS_LIMIT
                        )
                    }
                    coVerify(exactly = 1) { dialogportenClient.getDialogById(eq(behovEntity.dialogId!!)) }
                    coVerify(exactly = 1) {
                        dialogportenClient.updateDialogStatus(eq(behovEntity.dialogId!!), any(), DialogStatus.Completed)
                    }
                    coVerify(exactly = 1) {
                        spyNarmestelederDb.updateNlBehov(
                            match {
                                it.id == behovEntity.id &&
                                    it.dialogId == behovEntity.dialogId &&
                                    it.behovStatus == BehovStatus.DIALOGPORTEN_STATUS_SET_COMPLETED
                            },
                        )
                    }
                }
            }

            context("when there are multiple fulfilled behovs") {
                it("should update all Dialogporten messages to Completed") {
                    // Arrange
                    val behovs =
                        listOf(
                            nlBehovEntity().copy(
                                behovStatus = BehovStatus.BEHOV_FULFILLED,
                                dialogId = UUID.randomUUID(),
                            ),
                            nlBehovEntity().copy(
                                behovStatus = BehovStatus.BEHOV_FULFILLED,
                                dialogId = UUID.randomUUID(),
                            ),
                        )

                    coEvery {
                        spyNarmestelederDb.getNlBehovByStatus(
                            eq(BehovStatus.BEHOV_FULFILLED),
                            DialogportenService.BEHOV_BY_STATUS_LIMIT
                        )
                    } returns behovs
                    coEvery { dialogportenClient.getDialogById(any()) } answers {
                        val idArg = firstArg<UUID>()
                        behovs.first { it.dialogId == idArg }.toExtendedDialog()
                    }
                    coEvery {
                        dialogportenClient.updateDialogStatus(
                            match { dialogId -> behovs.any { behov -> dialogId == behov.dialogId } },
                            any(),
                            DialogStatus.Completed,
                        )
                    } just Runs
                    coEvery { spyNarmestelederDb.updateNlBehov(any()) } returns Unit

                    // Act
                    dialogportenService.setAllFulfilledBehovsAsCompletedInDialogporten()

                    // Assert
                    coVerify(exactly = 1) {
                        spyNarmestelederDb.getNlBehovByStatus(
                            eq(BehovStatus.BEHOV_FULFILLED),
                            DialogportenService.BEHOV_BY_STATUS_LIMIT
                        )
                    }
                    coVerify(exactly = 2) { dialogportenClient.getDialogById(any()) }
                    coVerify(exactly = 2) {
                        dialogportenClient.updateDialogStatus(
                            match {
                                behovs.any { behov -> behov.dialogId == it }
                            },
                            any(),
                            DialogStatus.Completed,
                        )
                    }
                    coVerify(exactly = 2) {
                        spyNarmestelederDb.updateNlBehov(
                            match { behovToPersist ->
                                behovs.any { behov ->
                                    behov.id == behovToPersist.id
                                } &&
                                    behovToPersist.behovStatus == BehovStatus.DIALOGPORTEN_STATUS_SET_COMPLETED
                            },
                        )
                    }
                }
            }

            context("when dialogporten client throws exception") {
                it("should log error and continue without updating document status") {
                    // Arrange
                    val behovEntity1 =
                        nlBehovEntity().copy(
                            behovStatus = BehovStatus.BEHOV_FULFILLED,
                            dialogId = UUID.randomUUID(),
                        )
                    coEvery {
                        spyNarmestelederDb.getNlBehovByStatus(
                            eq(BehovStatus.BEHOV_FULFILLED),
                            DialogportenService.BEHOV_BY_STATUS_LIMIT
                        )
                    } returns listOf(behovEntity1)
                    coEvery { dialogportenClient.getDialogById(any()) } returns behovEntity1.toExtendedDialog()
                    coEvery {
                        dialogportenClient.updateDialogStatus(
                            any(),
                            any(),
                            any(),
                        )
                    } throws RuntimeException("Dialogporten error")

                    // Act
                    dialogportenService.setAllFulfilledBehovsAsCompletedInDialogporten()

                    // Assert
                    coVerify(exactly = 1) {
                        spyNarmestelederDb.getNlBehovByStatus(
                            eq(BehovStatus.BEHOV_FULFILLED),
                            DialogportenService.BEHOV_BY_STATUS_LIMIT
                        )
                    }
                    coVerify(exactly = 1) {
                        dialogportenClient.updateDialogStatus(
                            behovEntity1.dialogId!!,
                            any(),
                            any()
                        )
                    }
                    coVerify(exactly = 0) { spyNarmestelederDb.updateNlBehov(any()) }
                }
            }

            context("when one update fails but others succeed") {
                it("should continue processing remaining behovs") {
                    // Arrange
                    val numOfBehovs = 20
                    val numOfSuccesses = 10
                    val failsEveryNth = 2

                    val behovs =
                        buildList {
                            repeat(numOfBehovs) {
                                add(
                                    nlBehovEntity()
                                        .copy(
                                            behovStatus = BehovStatus.BEHOV_FULFILLED,
                                            dialogId = UUID.randomUUID(),
                                        ),
                                )
                            }
                        }

                    coEvery {
                        spyNarmestelederDb.getNlBehovByStatus(
                            eq(BehovStatus.BEHOV_FULFILLED),
                            DialogportenService.BEHOV_BY_STATUS_LIMIT,
                        )
                    } returns behovs
                    coEvery { dialogportenClient.getDialogById(any()) } answers {
                        val idArg = firstArg<UUID>()
                        behovs
                            .first {
                                it.dialogId == idArg
                            }.toExtendedDialog()
                    }
                    coEvery { spyNarmestelederDb.updateNlBehov(any()) } returns Unit

                    val successfulUpdateIds = mutableSetOf<UUID>()
                    var callCount = 0
                    coEvery { dialogportenClient.updateDialogStatus(any(), any(), any()) } answers {
                        callCount++
                        if (callCount % failsEveryNth == 0) {
                            throw RuntimeException("Something went wrong")
                        }
                        val idArg = firstArg<UUID>()
                        successfulUpdateIds.add(idArg)
                    }

                    // Act
                    dialogportenService.setAllFulfilledBehovsAsCompletedInDialogporten()

                    // Assert
                    coVerify(exactly = 1) {
                        spyNarmestelederDb.getNlBehovByStatus(
                            eq(BehovStatus.BEHOV_FULFILLED),
                            DialogportenService.BEHOV_BY_STATUS_LIMIT,
                        )
                    }

                    coVerify(exactly = numOfBehovs) {
                        dialogportenClient.getDialogById(
                            match { dialogId ->
                                behovs.any { it.dialogId == dialogId }
                            },
                        )
                    }

                    coVerify(exactly = numOfBehovs) {
                        dialogportenClient.updateDialogStatus(
                            match {
                                behovs.any { behov -> behov.dialogId == it }
                            },
                            any(),
                            DialogStatus.Completed,
                        )
                    }

                    coVerify(exactly = numOfSuccesses) {
                        spyNarmestelederDb.updateNlBehov(
                            match {
                                successfulUpdateIds.contains(it.dialogId!!)
                            },
                        )
                    }
                }
            }
        }

        describe("setAllExpiredBehovsAsExpiredAndCompletedInDialogporten") {
            context("when there are no expired behovs") {
                it("should not call dialogporten client") {
                    // Arrange
                    coEvery {
                        spyNarmestelederDb.getNlBehovByStatus(
                            eq(BehovStatus.BEHOV_EXPIRED),
                            DialogportenService.BEHOV_BY_STATUS_LIMIT
                        )
                    } returns emptyList()

                    // Act
                    dialogportenService.setAllExpiredBehovsAsExpiredAndCompletedInDialogporten()

                    // Assert
                    coVerify(exactly = 1) {
                        spyNarmestelederDb.getNlBehovByStatus(
                            eq(BehovStatus.BEHOV_EXPIRED),
                            DialogportenService.BEHOV_BY_STATUS_LIMIT
                        )
                    }
                    coVerify(exactly = 0) { dialogportenClient.getDialogById(any()) }
                    coVerify(exactly = 0) { dialogportenClient.updateDialogStatusAndExpirationDate(any(), any(), any(), any()) }
                    coVerify(exactly = 0) { spyNarmestelederDb.updateNlBehov(any()) }
                }
            }

            context("when there is one expired behov") {
                it("should update Dialogporten message to Expired and Completed") {
                    // Arrange
                    val behovEntity =
                        nlBehovEntity().copy(
                            behovStatus = BehovStatus.BEHOV_EXPIRED,
                            dialogId = UUID.randomUUID(),
                        )
                    coEvery {
                        spyNarmestelederDb.getNlBehovByStatus(
                            eq(BehovStatus.BEHOV_EXPIRED),
                            DialogportenService.BEHOV_BY_STATUS_LIMIT
                        )
                    } returns listOf(behovEntity)
                    coEvery { dialogportenClient.updateDialogStatusAndExpirationDate(any(), any(), any(), any()) } just Runs
                    coEvery { dialogportenClient.getDialogById(eq(behovEntity.dialogId!!)) } returns
                        ExtendedDialog(
                            id = UUID.randomUUID(),
                            externalReference = behovEntity.dialogId.toString(),
                            party = "urn:altinn:organization:identifier-no:123456789",
                            status = DialogStatus.RequiresAttention,
                            isApiOnly = false,
                            attachments = emptyList(),
                            revision = UUID.randomUUID(),
                            content =
                            Content(
                                title = ContentValue(value = listOf(ContentValueItem(value = "Test content title"))),
                                summary = ContentValue(value = listOf(ContentValueItem(value = "Test content summary"))),
                            ),
                            serviceResource = "service:resource",
                            transmissions = listOf(),
                        )

                    // Act
                    dialogportenService.setAllExpiredBehovsAsExpiredAndCompletedInDialogporten()

                    // Assert
                    coVerify(exactly = 1) {
                        spyNarmestelederDb.getNlBehovByStatus(
                            eq(BehovStatus.BEHOV_EXPIRED),
                            DialogportenService.BEHOV_BY_STATUS_LIMIT
                        )
                    }
                    coVerify(exactly = 1) { dialogportenClient.getDialogById(eq(behovEntity.dialogId!!)) }
                    coVerify(exactly = 1) {
                        dialogportenClient.updateDialogStatusAndExpirationDate(
                            eq(behovEntity.dialogId!!),
                            any(),
                            DialogStatus.Completed,
                            any()
                        )
                    }
                    coVerify(exactly = 1) {
                        spyNarmestelederDb.updateNlBehov(
                            match {
                                it.id == behovEntity.id &&
                                    it.dialogId == behovEntity.dialogId &&
                                    it.behovStatus == BehovStatus.DIALOGPORTEN_STATUS_SET_COMPLETED
                            },
                        )
                    }
                }
            }

            context("when there are multiple expired behovs") {
                it("should update all Dialogporten messages to Expired and Completed") {
                    // Arrange
                    val behovs =
                        listOf(
                            nlBehovEntity().copy(
                                behovStatus = BehovStatus.BEHOV_EXPIRED,
                                dialogId = UUID.randomUUID(),
                            ),
                            nlBehovEntity().copy(
                                behovStatus = BehovStatus.BEHOV_EXPIRED,
                                dialogId = UUID.randomUUID(),
                            ),
                        )

                    coEvery {
                        spyNarmestelederDb.getNlBehovByStatus(
                            eq(BehovStatus.BEHOV_EXPIRED),
                            DialogportenService.BEHOV_BY_STATUS_LIMIT
                        )
                    } returns behovs
                    coEvery { dialogportenClient.getDialogById(any()) } answers {
                        val idArg = firstArg<UUID>()
                        behovs.first { it.dialogId == idArg }.toExtendedDialog()
                    }
                    coEvery {
                        dialogportenClient.updateDialogStatusAndExpirationDate(
                            match { dialogId -> behovs.any { behov -> dialogId == behov.dialogId } },
                            any(),
                            DialogStatus.Completed,
                            any(),
                        )
                    } just Runs
                    coEvery { spyNarmestelederDb.updateNlBehov(any()) } returns Unit

                    // Act
                    dialogportenService.setAllExpiredBehovsAsExpiredAndCompletedInDialogporten()

                    // Assert
                    coVerify(exactly = 1) {
                        spyNarmestelederDb.getNlBehovByStatus(
                            eq(BehovStatus.BEHOV_EXPIRED),
                            DialogportenService.BEHOV_BY_STATUS_LIMIT
                        )
                    }
                    coVerify(exactly = 2) { dialogportenClient.getDialogById(any()) }
                    coVerify(exactly = 2) {
                        dialogportenClient.updateDialogStatusAndExpirationDate(
                            match {
                                behovs.any { behov -> behov.dialogId == it }
                            },
                            any(),
                            DialogStatus.Completed,
                            any(),
                        )
                    }
                    coVerify(exactly = 2) {
                        spyNarmestelederDb.updateNlBehov(
                            match {
                                behovs.any { behov ->
                                    behov.id == it.id &&
                                        it.behovStatus == BehovStatus.DIALOGPORTEN_STATUS_SET_COMPLETED
                                }
                            },
                        )
                    }
                }
            }

            context("when dialogporten call fails") {
                it("should not update the behov status and continue with other behovs") {
                    // Arrange
                    val behovEntity1 =
                        nlBehovEntity().copy(
                            behovStatus = BehovStatus.BEHOV_EXPIRED,
                            dialogId = UUID.randomUUID(),
                        )
                    coEvery {
                        spyNarmestelederDb.getNlBehovByStatus(
                            eq(BehovStatus.BEHOV_EXPIRED),
                            DialogportenService.BEHOV_BY_STATUS_LIMIT
                        )
                    } returns listOf(behovEntity1)
                    coEvery { dialogportenClient.getDialogById(any()) } returns behovEntity1.toExtendedDialog()
                    coEvery {
                        dialogportenClient.updateDialogStatusAndExpirationDate(
                            any(),
                            any(),
                            any(),
                            any(),
                        )
                    } throws RuntimeException("Dialogporten error")

                    // Act
                    dialogportenService.setAllExpiredBehovsAsExpiredAndCompletedInDialogporten()

                    // Assert
                    coVerify(exactly = 1) {
                        spyNarmestelederDb.getNlBehovByStatus(
                            eq(BehovStatus.BEHOV_EXPIRED),
                            DialogportenService.BEHOV_BY_STATUS_LIMIT
                        )
                    }
                    coVerify(exactly = 1) {
                        dialogportenClient.updateDialogStatusAndExpirationDate(
                            behovEntity1.dialogId!!,
                            any(),
                            any(),
                            any()
                        )
                    }
                    // Should not update behov status when dialogporten call fails
                    coVerify(exactly = 0) {
                        spyNarmestelederDb.updateNlBehov(any())
                    }
                }
            }

            context("when some dialogporten calls fail") {
                it("should still update the behovs that succeeded") {
                    // Arrange
                    val numOfBehovs = 5
                    val failsEveryNth = 2 // Will fail on calls 2 and 4
                    val numOfSuccesses = 3 // Calls 1, 3, and 5 should succeed

                    val behovs =
                        (1..numOfBehovs).map {
                            nlBehovEntity().copy(
                                behovStatus = BehovStatus.BEHOV_EXPIRED,
                                dialogId = UUID.randomUUID(),
                            )
                        }
                    coEvery {
                        spyNarmestelederDb.getNlBehovByStatus(
                            eq(BehovStatus.BEHOV_EXPIRED),
                            DialogportenService.BEHOV_BY_STATUS_LIMIT
                        )
                    } returns behovs
                    coEvery { dialogportenClient.getDialogById(any()) } answers {
                        val idArg = firstArg<UUID>()
                        behovs
                            .first {
                                it.dialogId == idArg
                            }.toExtendedDialog()
                    }
                    coEvery { spyNarmestelederDb.updateNlBehov(any()) } returns Unit

                    val successfulUpdateIds = mutableSetOf<UUID>()
                    var callCount = 0
                    coEvery { dialogportenClient.updateDialogStatusAndExpirationDate(any(), any(), any(), any()) } answers {
                        callCount++
                        if (callCount % failsEveryNth == 0) {
                            throw RuntimeException("Something went wrong")
                        }
                        val idArg = firstArg<UUID>()
                        successfulUpdateIds.add(idArg)
                    }

                    // Act
                    dialogportenService.setAllExpiredBehovsAsExpiredAndCompletedInDialogporten()

                    // Assert
                    coVerify(exactly = 1) {
                        spyNarmestelederDb.getNlBehovByStatus(
                            eq(BehovStatus.BEHOV_EXPIRED),
                            DialogportenService.BEHOV_BY_STATUS_LIMIT
                        )
                    }

                    coVerify(exactly = numOfBehovs) { dialogportenClient.getDialogById(any()) }

                    coVerify(exactly = numOfBehovs) {
                        dialogportenClient.updateDialogStatusAndExpirationDate(
                            match {
                                behovs.any { behov -> behov.dialogId == it }
                            },
                            any(),
                            DialogStatus.Completed,
                            any(),
                        )
                    }

                    // Only the successful ones should have their status updated
                    coVerify(exactly = numOfSuccesses) {
                        spyNarmestelederDb.updateNlBehov(
                            match {
                                successfulUpdateIds.contains(it.dialogId!!)
                            },
                        )
                    }
                }
            }

            context("when behov has no dialogId") {
                it("should skip dialogporten call but still update behov status to completed") {
                    // Arrange
                    val behovWithoutDialogId =
                        nlBehovEntity().copy(
                            behovStatus = BehovStatus.BEHOV_EXPIRED,
                            dialogId = null,
                        )
                    val behovWithDialogId =
                        nlBehovEntity().copy(
                            behovStatus = BehovStatus.BEHOV_EXPIRED,
                            dialogId = UUID.randomUUID(),
                        )

                    coEvery {
                        spyNarmestelederDb.getNlBehovByStatus(
                            eq(BehovStatus.BEHOV_EXPIRED),
                            DialogportenService.BEHOV_BY_STATUS_LIMIT
                        )
                    } returns listOf(behovWithoutDialogId, behovWithDialogId)
                    coEvery { dialogportenClient.getDialogById(any()) } returns behovWithDialogId.toExtendedDialog()
                    coEvery { dialogportenClient.updateDialogStatusAndExpirationDate(any(), any(), any(), any()) } just Runs
                    coEvery { spyNarmestelederDb.updateNlBehov(any()) } returns Unit

                    // Act
                    dialogportenService.setAllExpiredBehovsAsExpiredAndCompletedInDialogporten()

                    // Assert
                    coVerify(exactly = 1) {
                        spyNarmestelederDb.getNlBehovByStatus(
                            eq(BehovStatus.BEHOV_EXPIRED),
                            DialogportenService.BEHOV_BY_STATUS_LIMIT
                        )
                    }
                    // Should only call dialogporten for the behov with dialogId
                    coVerify(exactly = 1) { dialogportenClient.getDialogById(behovWithDialogId.dialogId!!) }
                    coVerify(exactly = 1) {
                        dialogportenClient.updateDialogStatusAndExpirationDate(
                            behovWithDialogId.dialogId!!,
                            any(),
                            DialogStatus.Completed,
                            any()
                        )
                    }
                    // Should update both behovs (with and without dialogId)
                    coVerify(exactly = 2) {
                        spyNarmestelederDb.updateNlBehov(
                            match { it.behovStatus == BehovStatus.DIALOGPORTEN_STATUS_SET_COMPLETED }
                        )
                    }
                }

                it("should update behov status even when only behovs without dialogId exist") {
                    // Arrange
                    val behovWithoutDialogId =
                        nlBehovEntity().copy(
                            behovStatus = BehovStatus.BEHOV_EXPIRED,
                            dialogId = null,
                        )

                    coEvery {
                        spyNarmestelederDb.getNlBehovByStatus(
                            eq(BehovStatus.BEHOV_EXPIRED),
                            DialogportenService.BEHOV_BY_STATUS_LIMIT
                        )
                    } returns listOf(behovWithoutDialogId)
                    coEvery { spyNarmestelederDb.updateNlBehov(any()) } returns Unit

                    // Act
                    dialogportenService.setAllExpiredBehovsAsExpiredAndCompletedInDialogporten()

                    // Assert
                    coVerify(exactly = 1) {
                        spyNarmestelederDb.getNlBehovByStatus(
                            eq(BehovStatus.BEHOV_EXPIRED),
                            DialogportenService.BEHOV_BY_STATUS_LIMIT
                        )
                    }
                    // Should not call dialogporten at all
                    coVerify(exactly = 0) { dialogportenClient.getDialogById(any()) }
                    coVerify(exactly = 0) { dialogportenClient.updateDialogStatusAndExpirationDate(any(), any(), any(), any()) }
                    // Should still update the behov status to prevent infinite loop
                    coVerify(exactly = 1) {
                        spyNarmestelederDb.updateNlBehov(
                            match {
                                it.id == behovWithoutDialogId.id &&
                                    it.behovStatus == BehovStatus.DIALOGPORTEN_STATUS_SET_COMPLETED
                            }
                        )
                    }
                }
            }
        }

        describe("setToExpiredAndCompletedInDialogporten") {
            it("should update dialog status to completed and set expiration date") {
                // Arrange
                val dialogId = UUID.randomUUID()
                val extendedDialog = ExtendedDialog(
                    id = dialogId,
                    externalReference = dialogId.toString(),
                    party = "urn:altinn:organization:identifier-no:123456789",
                    status = DialogStatus.RequiresAttention,
                    isApiOnly = false,
                    attachments = emptyList(),
                    revision = UUID.randomUUID(),
                    content =
                    Content(
                        title = ContentValue(value = listOf(ContentValueItem(value = "Test content title"))),
                        summary = ContentValue(value = listOf(ContentValueItem(value = "Test content summary"))),
                    ),
                    serviceResource = "service:resource",
                    transmissions = listOf(),
                )
                coEvery { dialogportenClient.getDialogById(dialogId) } returns extendedDialog
                coEvery { dialogportenClient.updateDialogStatusAndExpirationDate(any(), any(), any(), any()) } just Runs

                // Act
                dialogportenService.setToExpiredAndCompletedInDialogporten(dialogId)

                // Assert
                coVerify(exactly = 1) { dialogportenClient.getDialogById(dialogId) }
                coVerify(exactly = 1) {
                    dialogportenClient.updateDialogStatusAndExpirationDate(
                        dialogId,
                        extendedDialog.revision,
                        DialogStatus.Completed,
                        any()
                    )
                }
            }
        }

        describe("sendToDialogporten") {
            it("sendToDialogporten should call createDialog") {
                // Arrange
                val behovEntity = nlBehovEntity()

                // Act
                dialogportenService.sendToDialogporten(behovEntity)

                // Assert
                coVerify(exactly = 1) { dialogportenClient.createDialog(any()) }
            }
        }

        describe("setToCompletedInDialogportenIfFulfilled") {
            it("should call sendEntityToDialogporten in a coroutine") {
                // Arrange
                val behovEntity =
                    nlBehovEntity().copy(
                        behovStatus = BehovStatus.BEHOV_FULFILLED,
                        dialogId = UUID.randomUUID(),
                    )
                val extendedDialg = behovEntity.toExtendedDialog()
                coEvery { dialogportenClient.getDialogById(any()) } returns extendedDialg

                // Act
                dialogportenService.setToCompletedInDialogporten(behovEntity)

                // Assert
                coVerify(exactly = 1) { dialogportenClient.getDialogById(behovEntity.dialogId!!) }
                coVerify(exactly = 1) {
                    dialogportenClient.updateDialogStatus(
                        behovEntity.dialogId!!,
                        extendedDialg.revision,
                        DialogStatus.Completed,
                    )
                }
            }
        }
    })

private fun NarmestelederBehovEntity.toExtendedDialog(): ExtendedDialog {
    require(id != null) { "Cannot create Dialogporten Dialog without id" }
    return ExtendedDialog(
        id = UUID.randomUUID(),
        externalReference = dialogId.toString(),
        revision = UUID.randomUUID(),
        serviceResource = "urn:altinn:resource",
        status = DialogStatus.RequiresAttention,
        party = "urn:altinn:organization:identifier-no:$orgnummer",
        content =
        Content(
            title = ContentValue(value = listOf(ContentValueItem(value = "Test content title"))),
            summary = ContentValue(value = listOf(ContentValueItem(value = "Test content summary"))),
        ),
        isApiOnly = false,
        attachments = emptyList(),
    )
}
