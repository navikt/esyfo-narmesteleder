package no.nav.syfo.dialogporten.service

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.spyk
import nlBehovEntity
import no.nav.syfo.altinn.dialogporten.service.DialogportenService
import no.nav.syfo.narmesteleder.db.FakeNarmestelederDb
import no.nav.syfo.narmesteleder.db.NarmestelederBehovEntity
import no.nav.syfo.narmestelederbehov.domain.BehovStatus
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.client.DialogPatchResult
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.client.DialogportenClient
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.client.HttpDialogportenClient
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.domain.Content
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.domain.ContentValue
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.domain.ContentValueItem
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.domain.DialogStatus
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.domain.ExtendedDialog
import no.nav.syfo.platform.upstream.UpstreamResult
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.util.*

class DialogportenServiceTest :
    DescribeSpec({
        val dialogportenClient = mockk<DialogportenClient>()

        lateinit var fakeNarmestelederDb: FakeNarmestelederDb
        lateinit var spyNarmestelederDb: FakeNarmestelederDb
        lateinit var dialogportenService: DialogportenService

        beforeTest {
            clearAllMocks(currentThreadOnly = true)
            fakeNarmestelederDb = FakeNarmestelederDb()
            spyNarmestelederDb = spyk(fakeNarmestelederDb)
            dialogportenService =
                DialogportenService(
                    dialogportenClient = dialogportenClient,
                    narmestelederDb = spyNarmestelederDb,
                )
            spyNarmestelederDb.clear()
        }
        it("logs need and dialog identifiers when a status update fails") {
            val logger = LoggerFactory.getLogger(DialogportenService::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>()
            appender.start()
            logger.addAppender(appender)
            try {
                val behovId = UUID.randomUUID()
                val dialogId = UUID.randomUUID()
                val behov = nlBehovEntity().copy(id = behovId, dialogId = dialogId, behovStatus = BehovStatus.BEHOV_FULFILLED)
                coEvery { dialogportenClient.getDialogById(dialogId) } throws IllegalStateException("private-canary")

                dialogportenService.setToCompletedInDialogporten(behov)

                val fields = appender.list.single().keyValuePairs.associate { it.key to it.value }
                fields["event_type"] shouldBe "dialogporten_dialog_failed"
                fields["action"] shouldBe "COMPLETE"
                fields["behov_id"] shouldBe behovId.toString()
                fields["dialog_id"] shouldBe dialogId.toString()
                appender.list.single().formattedMessage.contains("private-canary") shouldBe false
            } finally {
                logger.detachAppender(appender)
                appender.stop()
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
                    coVerify(exactly = 0) { dialogportenClient.patchDialog(any(), any(), any<HttpDialogportenClient.DialogportenPatch>()) }
                    coVerify(exactly = 0) { spyNarmestelederDb.updateNlBehov(any()) }
                }
            }

            context("when there is one fulfilled behov") {
                it("should update dialogporten status to Completed and update status in database to DIALOGPORTEN_STATUS_SET_COMPLETED") {
                    // Arrange
                    val behovEntity =
                        spyNarmestelederDb.seed(
                            nlBehovEntity().copy(
                                behovStatus = BehovStatus.BEHOV_FULFILLED,
                                dialogId = UUID.randomUUID(),
                            ),
                        )
                    coEvery { dialogportenClient.patchDialog(any(), any(), any<HttpDialogportenClient.DialogportenPatch>()) } returns UpstreamResult.Success(DialogPatchResult.Patched)
                    coEvery { dialogportenClient.getDialogById(eq(behovEntity.dialogId!!)) } returns
                        UpstreamResult.Success(
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
                        dialogportenClient.patchDialog(
                            dialogId = eq(behovEntity.dialogId!!),
                            revisionNumber = any<UUID>(),
                            patch = match<HttpDialogportenClient.DialogportenPatch> {
                                it.value == DialogStatus.Completed.name &&
                                    it.operation == HttpDialogportenClient.DialogportenPatch.OPERATION.REPLACE
                            }
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
                        UpstreamResult.Success(behovs.first { it.dialogId == idArg }.toExtendedDialog())
                    }
                    coEvery {
                        dialogportenClient.patchDialog(
                            match { dialogId -> behovs.any { behov -> dialogId == behov.dialogId } },
                            any(),
                            any<HttpDialogportenClient.DialogportenPatch>(),
                        )
                    } returns UpstreamResult.Success(DialogPatchResult.Patched)
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
                        dialogportenClient.patchDialog(
                            match {
                                behovs.any { behov -> behov.dialogId == it }
                            },
                            any(),
                            any<HttpDialogportenClient.DialogportenPatch>(),
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
                    coEvery { dialogportenClient.getDialogById(any()) } returns UpstreamResult.Success(behovEntity1.toExtendedDialog())
                    coEvery {
                        dialogportenClient.patchDialog(
                            any(),
                            any(),
                            any<HttpDialogportenClient.DialogportenPatch>(),
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
                        dialogportenClient.patchDialog(
                            behovEntity1.dialogId!!,
                            any(),
                            any<HttpDialogportenClient.DialogportenPatch>(),
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
                        UpstreamResult.Success(
                            behovs.first { it.dialogId == idArg }.toExtendedDialog()
                        )
                    }
                    coEvery { spyNarmestelederDb.updateNlBehov(any()) } returns Unit

                    val successfulUpdateIds = mutableSetOf<UUID>()
                    var callCount = 0
                    coEvery { dialogportenClient.patchDialog(any(), any(), any<HttpDialogportenClient.DialogportenPatch>()) } answers {
                        callCount++
                        if (callCount % failsEveryNth == 0) {
                            throw RuntimeException("Something went wrong")
                        }
                        val idArg = firstArg<UUID>()
                        successfulUpdateIds.add(idArg)
                        UpstreamResult.Success(DialogPatchResult.Patched)
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
                        dialogportenClient.patchDialog(
                            match {
                                behovs.any { behov -> behov.dialogId == it }
                            },
                            any(),
                            any<HttpDialogportenClient.DialogportenPatch>(),
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
            val expireableBehovs = setOf(BehovStatus.ERROR, BehovStatus.BEHOV_EXPIRED)
            context("when there are no expired behovs") {
                it("should not call dialogporten client") {
                    // Arrange
                    coEvery {
                        spyNarmestelederDb.getNlBehovForExpireInDialogporten(
                            DialogportenService.BEHOV_BY_STATUS_LIMIT,
                            match<List<BehovStatus>> { expireableBehovs.containsAll(it) },
                        )
                    } returns emptyList()

                    // Act
                    dialogportenService.setAllExpiredBehovsAsExpiredAndCompletedInDialogporten()

                    // Assert
                    coVerify(exactly = 1) {
                        spyNarmestelederDb.getNlBehovForExpireInDialogporten(
                            DialogportenService.BEHOV_BY_STATUS_LIMIT,
                            match<List<BehovStatus>> { expireableBehovs.containsAll(it) },
                        )
                    }
                    coVerify(exactly = 0) { dialogportenClient.getDialogById(any()) }
                    coVerify(exactly = 0) { dialogportenClient.patchDialog(any(), any(), any<List<HttpDialogportenClient.DialogportenPatch>>()) }
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
                        spyNarmestelederDb.getNlBehovForExpireInDialogporten(
                            DialogportenService.BEHOV_BY_STATUS_LIMIT,
                            match<List<BehovStatus>> { expireableBehovs.containsAll(it) },
                        )
                    } returns listOf(behovEntity)
                    coEvery { dialogportenClient.patchDialog(any(), any(), any<List<HttpDialogportenClient.DialogportenPatch>>()) } returns UpstreamResult.Success(DialogPatchResult.Patched)
                    coEvery { dialogportenClient.getDialogById(eq(behovEntity.dialogId!!)) } returns
                        UpstreamResult.Success(
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
                        )

                    // Act
                    dialogportenService.setAllExpiredBehovsAsExpiredAndCompletedInDialogporten()

                    // Assert
                    coVerify(exactly = 1) {
                        spyNarmestelederDb.getNlBehovForExpireInDialogporten(
                            DialogportenService.BEHOV_BY_STATUS_LIMIT,
                            match<List<BehovStatus>> { expireableBehovs.containsAll(it) },
                        )
                    }
                    coVerify(exactly = 1) { dialogportenClient.getDialogById(eq(behovEntity.dialogId!!)) }
                    coVerify(exactly = 1) {
                        dialogportenClient.patchDialog(
                            eq(behovEntity.dialogId!!),
                            any(),
                            any<List<HttpDialogportenClient.DialogportenPatch>>(),
                        )
                    }
                    coVerify(exactly = 1) {
                        spyNarmestelederDb.updateNlBehov(
                            match {
                                it.id == behovEntity.id &&
                                    it.dialogId == behovEntity.dialogId &&
                                    it.behovStatus == BehovStatus.BEHOV_EXPIRED &&
                                    it.expiredInDialogporten != null
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
                                behovStatus = BehovStatus.ERROR,
                                dialogId = UUID.randomUUID(),
                            ),
                        )

                    coEvery {
                        spyNarmestelederDb.getNlBehovForExpireInDialogporten(
                            DialogportenService.BEHOV_BY_STATUS_LIMIT,
                            match<List<BehovStatus>> { expireableBehovs.containsAll(it) },
                        )
                    } returns behovs
                    coEvery { dialogportenClient.getDialogById(any()) } answers {
                        val idArg = firstArg<UUID>()
                        UpstreamResult.Success(behovs.first { it.dialogId == idArg }.toExtendedDialog())
                    }
                    coEvery {
                        dialogportenClient.patchDialog(
                            match { dialogId -> behovs.any { behov -> dialogId == behov.dialogId } },
                            any(),
                            any<List<HttpDialogportenClient.DialogportenPatch>>(),
                        )
                    } returns UpstreamResult.Success(DialogPatchResult.Patched)
                    coEvery { spyNarmestelederDb.updateNlBehov(any()) } returns Unit

                    // Act
                    dialogportenService.setAllExpiredBehovsAsExpiredAndCompletedInDialogporten()

                    // Assert
                    coVerify(exactly = 1) {
                        spyNarmestelederDb.getNlBehovForExpireInDialogporten(
                            DialogportenService.BEHOV_BY_STATUS_LIMIT,
                            match<List<BehovStatus>> { expireableBehovs.containsAll(it) },
                        )
                    }
                    coVerify(exactly = 2) { dialogportenClient.getDialogById(any()) }
                    coVerify(exactly = 2) {
                        dialogportenClient.patchDialog(
                            match {
                                behovs.any { behov -> behov.dialogId == it }
                            },
                            any(),
                            any<List<HttpDialogportenClient.DialogportenPatch>>(),
                        )
                    }
                    coVerify(exactly = 2) {
                        spyNarmestelederDb.updateNlBehov(
                            match {
                                behovs.any { behov ->
                                    behov.id == it.id &&
                                        it.behovStatus in expireableBehovs
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
                        spyNarmestelederDb.getNlBehovForExpireInDialogporten(
                            DialogportenService.BEHOV_BY_STATUS_LIMIT,
                            match<List<BehovStatus>> { expireableBehovs.containsAll(it) },
                        )
                    } returns listOf(behovEntity1)
                    coEvery { dialogportenClient.getDialogById(any()) } returns UpstreamResult.Success(behovEntity1.toExtendedDialog())
                    coEvery {
                        dialogportenClient.patchDialog(
                            any(),
                            any(),
                            any<List<HttpDialogportenClient.DialogportenPatch>>(),
                        )
                    } throws RuntimeException("Dialogporten error")

                    // Act
                    dialogportenService.setAllExpiredBehovsAsExpiredAndCompletedInDialogporten()

                    // Assert
                    coVerify(exactly = 1) {
                        spyNarmestelederDb.getNlBehovForExpireInDialogporten(
                            DialogportenService.BEHOV_BY_STATUS_LIMIT,
                            match<List<BehovStatus>> { expireableBehovs.containsAll(it) },
                        )
                    }
                    coVerify(exactly = 1) {
                        dialogportenClient.patchDialog(
                            behovEntity1.dialogId!!,
                            any(),
                            any<List<HttpDialogportenClient.DialogportenPatch>>(),
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
                        spyNarmestelederDb.getNlBehovForExpireInDialogporten(
                            DialogportenService.BEHOV_BY_STATUS_LIMIT,
                            match<List<BehovStatus>> { expireableBehovs.containsAll(it) },
                        )
                    } returns behovs
                    coEvery { dialogportenClient.getDialogById(any()) } answers {
                        val idArg = firstArg<UUID>()
                        UpstreamResult.Success(
                            behovs.first { it.dialogId == idArg }.toExtendedDialog()
                        )
                    }
                    coEvery { spyNarmestelederDb.updateNlBehov(any()) } returns Unit

                    val successfulUpdateIds = mutableSetOf<UUID>()
                    var callCount = 0
                    coEvery { dialogportenClient.patchDialog(any(), any(), any<List<HttpDialogportenClient.DialogportenPatch>>()) } answers {
                        callCount++
                        if (callCount % failsEveryNth == 0) {
                            throw RuntimeException("Something went wrong")
                        }
                        val idArg = firstArg<UUID>()
                        successfulUpdateIds.add(idArg)
                        UpstreamResult.Success(DialogPatchResult.Patched)
                    }

                    // Act
                    dialogportenService.setAllExpiredBehovsAsExpiredAndCompletedInDialogporten()

                    // Assert
                    coVerify(exactly = 1) {
                        spyNarmestelederDb.getNlBehovForExpireInDialogporten(
                            DialogportenService.BEHOV_BY_STATUS_LIMIT,
                            match<List<BehovStatus>> { expireableBehovs.containsAll(it) },
                        )
                    }

                    coVerify(exactly = numOfBehovs) { dialogportenClient.getDialogById(any()) }

                    coVerify(exactly = numOfBehovs) {
                        dialogportenClient.patchDialog(
                            match {
                                behovs.any { behov -> behov.dialogId == it }
                            },
                            any(),
                            any<List<HttpDialogportenClient.DialogportenPatch>>(),
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
                it("should only update timestamp on behovs with dialogId") {
                    // Arrange
                    val behovWithoutDialogId =
                        nlBehovEntity().copy(
                            behovStatus = BehovStatus.ERROR,
                            dialogId = null,
                        )
                    val behovWithDialogId =
                        nlBehovEntity().copy(
                            behovStatus = BehovStatus.BEHOV_EXPIRED,
                            dialogId = UUID.randomUUID(),
                        )

                    coEvery {
                        spyNarmestelederDb.getNlBehovForExpireInDialogporten(
                            DialogportenService.BEHOV_BY_STATUS_LIMIT,
                            match<List<BehovStatus>> { expireableBehovs.containsAll(it) },
                        )
                    } returns listOf(behovWithoutDialogId, behovWithDialogId)
                    coEvery { dialogportenClient.getDialogById(any()) } returns UpstreamResult.Success(behovWithDialogId.toExtendedDialog())
                    coEvery { dialogportenClient.patchDialog(any(), any(), any<List<HttpDialogportenClient.DialogportenPatch>>()) } returns UpstreamResult.Success(DialogPatchResult.Patched)
                    coEvery { spyNarmestelederDb.updateNlBehov(any()) } returns Unit

                    // Act
                    dialogportenService.setAllExpiredBehovsAsExpiredAndCompletedInDialogporten()

                    // Assert
                    coVerify(exactly = 1) {
                        spyNarmestelederDb.getNlBehovForExpireInDialogporten(
                            DialogportenService.BEHOV_BY_STATUS_LIMIT,
                            match<List<BehovStatus>> { expireableBehovs.containsAll(it) },
                        )
                    }
                    // Should only call dialogporten for the behov with dialogId
                    coVerify(exactly = 1) { dialogportenClient.getDialogById(behovWithDialogId.dialogId!!) }
                    coVerify(exactly = 1) {
                        dialogportenClient.patchDialog(
                            behovWithDialogId.dialogId!!,
                            any(),
                            any<List<HttpDialogportenClient.DialogportenPatch>>(),
                        )
                    }
                    // Should only update behov with dialog id
                    coVerify(exactly = 1) {
                        spyNarmestelederDb.updateNlBehov(
                            match {
                                it.id == behovWithDialogId.id!! &&
                                    it.behovStatus == BehovStatus.BEHOV_EXPIRED
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
                val expirationDate = OffsetDateTime.now().plusMinutes(10)
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
                coEvery { dialogportenClient.getDialogById(dialogId) } returns UpstreamResult.Success(extendedDialog)
                coEvery { dialogportenClient.patchDialog(any(), any(), any<List<HttpDialogportenClient.DialogportenPatch>>()) } returns UpstreamResult.Success(DialogPatchResult.Patched)

                // Act
                dialogportenService.setToExpiredAndCompletedInDialogporten(dialogId, expirationDate)

                // Assert
                coVerify(exactly = 1) { dialogportenClient.getDialogById(dialogId) }
                coVerify(exactly = 1) {
                    dialogportenClient.patchDialog(
                        dialogId,
                        extendedDialog.revision,
                        any<List<HttpDialogportenClient.DialogportenPatch>>(),
                    )
                }
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
                coEvery { dialogportenClient.getDialogById(any()) } returns UpstreamResult.Success(extendedDialg)

                // Act
                dialogportenService.setToCompletedInDialogporten(behovEntity)

                // Assert
                coVerify(exactly = 1) { dialogportenClient.getDialogById(behovEntity.dialogId!!) }
                coVerify(exactly = 1) {
                    dialogportenClient.patchDialog(
                        behovEntity.dialogId!!,
                        extendedDialg.revision,
                        any<HttpDialogportenClient.DialogportenPatch>(),
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
