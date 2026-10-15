package no.nav.syfo.dialogporten.service

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.util.LogbackMDCAdapter
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import nlBehovEntity
import no.nav.esyfo.observability.testkit.captureLogs
import no.nav.syfo.altinn.dialogporten.client.DialogPatchResult
import no.nav.syfo.altinn.dialogporten.client.DialogportenClient
import no.nav.syfo.altinn.dialogporten.client.HttpDialogportenClient
import no.nav.syfo.altinn.dialogporten.domain.Content
import no.nav.syfo.altinn.dialogporten.domain.ContentValue
import no.nav.syfo.altinn.dialogporten.domain.ContentValueItem
import no.nav.syfo.altinn.dialogporten.domain.Dialog
import no.nav.syfo.altinn.dialogporten.domain.DialogStatus
import no.nav.syfo.altinn.dialogporten.domain.ExtendedDialog
import no.nav.syfo.altinn.dialogporten.service.DialogportenService
import no.nav.syfo.application.environment.OtherEnvironmentProperties
import no.nav.syfo.integration.pdl.FakePdlClient
import no.nav.syfo.narmesteleder.db.FakeNarmestelederDb
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.pdl.PdlService
import no.nav.syfo.platform.upstream.UpstreamError
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName
import no.nav.syfo.platform.upstream.UpstreamResult
import org.slf4j.LoggerFactory
import java.util.UUID

class DialogportenServiceFailureTest :
    FunSpec({
        val mapper = jacksonObjectMapper()
        val logger = LoggerFactory.getLogger(DialogportenService::class.java) as Logger
        val original = logger.level to logger.isAdditive
        val context = LoggerContext().apply { mdcAdapter = LogbackMDCAdapter() }
        beforeSpec {
            context.putProperty("NAIS_CLUSTER_NAME", "test")
            JoranConfigurator().apply {
                this.context = context
                doConfigure("src/main/resources/logback.xml")
            }
            logger.level = Level.ERROR
            logger.isAdditive = false
            logger.addAppender(requireNotNull(context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("stdout_json")))
        }
        afterSpec {
            logger.detachAppender("stdout_json")
            logger.level = original.first
            logger.isAdditive = original.second
            context.stop()
        }

        listOf("COMPLETE", "EXPIRE").forEach { action ->
            test("$action revision conflict logs 412 and leaves all persisted state unchanged for retry") {
                val db = FakeNarmestelederDb()
                val client = LegacyRecordingClient()
                val service = DialogportenService(client, db, OtherEnvironmentProperties.createForLocal(), PdlService(FakePdlClient()))
                val behov = db.insertNlBehov(
                    nlBehovEntity().copy(
                        dialogId = LEGACY_DIALOG_ID,
                        behovStatus = if (action == "COMPLETE") BehovStatus.BEHOV_FULFILLED else BehovStatus.BEHOV_EXPIRED,
                    )
                )
                captureLogs(logger, "stdout_json").use { capture ->
                    if (action == "COMPLETE") service.setAllFulfilledBehovsAsCompletedInDialogporten() else service.setAllExpiredBehovsAsExpiredAndCompletedInDialogporten()
                    val record = mapper.readTree(capture.records.single())
                    record["event_type"].asText() shouldBe "dialogporten_dialog_failed"
                    record["action"].asText() shouldBe action
                    record["upstream"].asText() shouldBe "dialogporten"
                    record["failure_stage"].asText() shouldBe "response"
                    record["upstream_status"].asInt() shouldBe 412
                    record["exception_type"].asText() shouldBe "UpstreamRequestException"
                    record["behov_id"].asText() shouldBe behov.id.toString()
                    record["dialog_id"].asText() shouldBe LEGACY_DIALOG_ID.toString()
                }
                db.findBehovById(requireNotNull(behov.id)) shouldBe behov
                client.calls shouldBe listOf("get", "patch")
                client.patches.map { it.path } shouldBe if (action == "COMPLETE") {
                    listOf(HttpDialogportenClient.DialogportenPatch.PATH.STATUS)
                } else {
                    listOf(HttpDialogportenClient.DialogportenPatch.PATH.STATUS, HttpDialogportenClient.DialogportenPatch.PATH.EXPIRES_AT)
                }
            }

            listOf("get", "patch").forEach { step ->
                listOf("texas", "altinn-authentication").forEach { upstream ->
                    test("$action preserves $upstream metadata on $step failure and leaves persisted state unchanged") {
                        val failure = UpstreamResult.Failure(
                            UpstreamFailure(
                                UpstreamName(upstream),
                                UpstreamFailureStage.TOKEN_EXCHANGE,
                                502,
                                IllegalStateException("private-diagnostic-canary"),
                                errors = listOf(UpstreamError("server_error")),
                            )
                        )
                        val db = FakeNarmestelederDb()
                        val client = LegacyRecordingClient(failure, step)
                        val service = DialogportenService(client, db, OtherEnvironmentProperties.createForLocal(), PdlService(FakePdlClient()))
                        val behov = db.insertNlBehov(
                            nlBehovEntity().copy(
                                dialogId = LEGACY_DIALOG_ID,
                                behovStatus = if (action == "COMPLETE") BehovStatus.BEHOV_FULFILLED else BehovStatus.BEHOV_EXPIRED,
                            )
                        )
                        captureLogs(logger, "stdout_json").use { capture ->
                            if (action == "COMPLETE") service.setAllFulfilledBehovsAsCompletedInDialogporten() else service.setAllExpiredBehovsAsExpiredAndCompletedInDialogporten()
                            val output = capture.records.single()
                            val record = mapper.readTree(output)
                            record["event_type"].asText() shouldBe "dialogporten_dialog_failed"
                            record["action"].asText() shouldBe action
                            record["upstream"].asText() shouldBe upstream
                            record["failure_stage"].asText() shouldBe "token_exchange"
                            record["upstream_status"].asInt() shouldBe 502
                            output shouldNotContain "private-diagnostic-canary"
                        }

                        db.findBehovById(requireNotNull(behov.id)) shouldBe behov
                        client.calls shouldBe if (step == "get") listOf("get") else listOf("get", "patch")
                    }
                }
            }
        }
        listOf("texas", "altinn-authentication").forEach { upstream ->
            test("CREATE preserves $upstream token failure metadata without marking the dialog created") {
                val failure = UpstreamResult.Failure(UpstreamFailure(UpstreamName(upstream), UpstreamFailureStage.TOKEN_EXCHANGE, 503, IllegalStateException("private-diagnostic-canary")))
                val db = FakeNarmestelederDb()
                val client = LegacyRecordingClient(failure, "create")
                val service = DialogportenService(client, db, OtherEnvironmentProperties.createForLocal(), PdlService(FakePdlClient()))
                val behov = db.insertNlBehov(nlBehovEntity())
                captureLogs(logger, "stdout_json").use { capture ->
                    service.sendToDialogporten(behov)
                    val output = capture.records.single()
                    val record = mapper.readTree(output)
                    record["event_type"].asText() shouldBe "dialogporten_dialog_failed"
                    record["action"].asText() shouldBe "CREATE"
                    record["upstream"].asText() shouldBe upstream
                    record["failure_stage"].asText() shouldBe "token_exchange"
                    record["upstream_status"].asInt() shouldBe 503
                    output shouldNotContain "private-diagnostic-canary"
                }
                db.findBehovById(requireNotNull(behov.id)) shouldBe behov
                client.calls shouldBe listOf("create")
            }
        }
    })

private val LEGACY_DIALOG_ID = UUID.fromString("00000000-0000-0000-0000-000000000002")
private val LEGACY_REVISION = UUID.fromString("00000000-0000-0000-0000-000000000003")

private class LegacyRecordingClient(
    private val failure: UpstreamResult.Failure? = null,
    private val failAt: String? = null,
) : DialogportenClient {
    val calls = mutableListOf<String>()
    var patches = emptyList<HttpDialogportenClient.DialogportenPatch>()

    override suspend fun createDialog(dialog: Dialog): UpstreamResult<UUID> {
        calls += "create"
        if (failAt == "create") return requireNotNull(failure)
        error("Unexpected create")
    }

    override suspend fun getDialogById(dialogId: UUID): UpstreamResult<ExtendedDialog> {
        dialogId shouldBe LEGACY_DIALOG_ID
        calls += "get"
        if (failAt == "get") return requireNotNull(failure)
        return UpstreamResult.Success(
            ExtendedDialog(
                revision = LEGACY_REVISION,
                id = dialogId,
                party = "urn:altinn:organization:identifier-no:910000001",
                serviceResource = "service:resource",
                externalReference = dialogId.toString(),
                status = DialogStatus.RequiresAttention,
                content = Content(
                    title = ContentValue(value = listOf(ContentValueItem("Title"))),
                    summary = ContentValue(value = listOf(ContentValueItem("Summary"))),
                ),
            )
        )
    }

    override suspend fun patchDialog(dialogId: UUID, revisionNumber: UUID, patch: List<HttpDialogportenClient.DialogportenPatch>): UpstreamResult<DialogPatchResult> {
        dialogId shouldBe LEGACY_DIALOG_ID
        revisionNumber shouldBe LEGACY_REVISION
        calls += "patch"
        patches = patch
        return if (failAt == "patch") requireNotNull(failure) else UpstreamResult.Success(DialogPatchResult.RevisionConflict)
    }
}
