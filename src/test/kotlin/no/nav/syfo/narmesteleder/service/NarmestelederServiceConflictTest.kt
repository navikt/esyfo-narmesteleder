package no.nav.syfo.narmesteleder.service

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import no.nav.syfo.TestDB
import no.nav.syfo.aareg.AaregService
import no.nav.syfo.altinn.dialogporten.client.DialogportenClient
import no.nav.syfo.altinn.dialogporten.client.HttpDialogportenClient
import no.nav.syfo.altinn.dialogporten.domain.Dialog
import no.nav.syfo.altinn.dialogporten.domain.ExtendedDialog
import no.nav.syfo.altinn.dialogporten.service.DialogportenService
import no.nav.syfo.application.environment.OtherEnvironmentProperties
import no.nav.syfo.integration.aareg.TestAaregClient
import no.nav.syfo.integration.pdl.FakePdlClient
import no.nav.syfo.narmesteleder.db.ActiveNarmestelederbehovAlreadyExistsException
import no.nav.syfo.narmesteleder.db.FakeNarmestelederDb
import no.nav.syfo.narmesteleder.db.NarmestelederBehovEntity
import no.nav.syfo.narmesteleder.db.NarmestelederDb
import no.nav.syfo.narmesteleder.db.PostgresNarmestelederDb
import no.nav.syfo.narmesteleder.domain.BehovReason
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmesteleder.domain.LinemanagerRequirementWrite
import no.nav.syfo.narmesteleder.domain.OrganizationNumber
import no.nav.syfo.narmesteleder.domain.PersonalIdentificationNumber
import no.nav.syfo.pdl.PdlService
import no.nav.syfo.sykmelding.model.Arbeidsgiver
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

private class RecordingDialogportenClient : DialogportenClient {
    var creates = 0

    override suspend fun createDialog(dialog: Dialog): UUID {
        creates++
        return UUID.randomUUID()
    }

    override suspend fun getDialogById(dialogId: UUID): ExtendedDialog = error("Unexpected lookup")
    override suspend fun patchDialog(dialogId: UUID, revisionNumber: UUID, patch: List<HttpDialogportenClient.DialogportenPatch>): Unit = error("Unexpected patch")
}

private fun conflictTestService(db: NarmestelederDb, dialog: RecordingDialogportenClient): NarmestelederService {
    val pdl = PdlService(FakePdlClient())
    return NarmestelederService(
        nlDb = db,
        persistLeesahNlBehov = true,
        aaregService = AaregService(TestAaregClient()),
        dinesykmeldteService = { _, _ -> error("Unexpected sykmelding lookup") },
        dialogportenService = DialogportenService(dialog, db, OtherEnvironmentProperties.createForLocal(), pdl),
    )
}

class NarmestelederServiceConflictTest :
    FunSpec({
        val write = LinemanagerRequirementWrite(
            employeeIdentificationNumber = PersonalIdentificationNumber("12345678910"),
            orgNumber = OrganizationNumber("123456789"),
            behovReason = BehovReason.DEAKTIVERT_LEDER,
        )
        val source = BehovSource(UUID.randomUUID().toString(), "test")
        val arbeidsgiver = Arbeidsgiver(orgnummer = "123456789", orgNavn = "Test", juridiskOrgnummer = "987654321")

        beforeTest {
            TestDB.clearAllData()
        }

        test("skips with the existing metric and safe log message without calling Dialogporten") {
            val db = object : NarmestelederDb by FakeNarmestelederDb() {
                override suspend fun insertNlBehov(nlBehov: NarmestelederBehovEntity): NarmestelederBehovEntity = throw ActiveNarmestelederbehovAlreadyExistsException()
            }
            val dialog = RecordingDialogportenClient()
            val logger = LoggerFactory.getLogger(NarmestelederService::class.java) as Logger
            val previousLevel = logger.level
            val appender = ListAppender<ILoggingEvent>().also { it.start() }
            logger.level = Level.INFO
            logger.addAppender(appender)
            try {
                val before = COUNT_CREATE_BEHOV_SKIPPED_HAS_PRE_EXISTING.count()
                conflictTestService(db, dialog).createNewNlBehov(write, true, source, arbeidsgiver) shouldBe null
                COUNT_CREATE_BEHOV_SKIPPED_HAS_PRE_EXISTING.count() shouldBe before + 1
                dialog.creates shouldBe 0
                appender.list.map { it.formattedMessage } shouldBe listOf(
                    "Not inserting NarmestelederBehovEntity since one already for employee and org",
                )

                val conflictMessages = appender.list.map { it.formattedMessage }
                appender.list.clear()
                val precheckDb = FakeNarmestelederDb()
                precheckDb.insertNlBehov(NarmestelederBehovEntity.fromLinemanagerRequirementWrite(write, "987654321", BehovStatus.BEHOV_CREATED))
                conflictTestService(precheckDb, dialog).createNewNlBehov(write, true, source, arbeidsgiver) shouldBe null
                COUNT_CREATE_BEHOV_SKIPPED_HAS_PRE_EXISTING.count() shouldBe before + 2
                dialog.creates shouldBe 0
                appender.list.map { it.formattedMessage } shouldBe conflictMessages
            } finally {
                logger.detachAppender(appender)
                appender.stop()
                logger.level = previousLevel
            }
        }

        test("stores exactly one active row when both concurrent callers pass the pre-check and only the winner calls Dialogporten") {
            val postgres = PostgresNarmestelederDb(TestDB.database)
            val prechecks = AtomicInteger()
            val bothPassed = CompletableDeferred<Unit>()
            val racingDb = object : NarmestelederDb by postgres {
                override suspend fun findBehovByParameters(
                    sykmeldtFnr: String,
                    orgnummer: String,
                    behovStatus: List<BehovStatus>,
                ): List<NarmestelederBehovEntity> {
                    if (prechecks.incrementAndGet() == 2) bothPassed.complete(Unit)
                    bothPassed.await()
                    return emptyList()
                }
            }
            val dialogs = listOf(RecordingDialogportenClient(), RecordingDialogportenClient())
            val services = dialogs.map { conflictTestService(racingDb, it) }
            val before = COUNT_CREATE_BEHOV_SKIPPED_HAS_PRE_EXISTING.count()

            val results = withTimeout(10_000.milliseconds) {
                coroutineScope {
                    services.map { service ->
                        async { service.createNewNlBehov(write, true, source, arbeidsgiver) }
                    }.awaitAll()
                }
            }

            prechecks.get() shouldBe 2
            results.count { it != null } shouldBe 1
            results.count { it == null } shouldBe 1
            results.forEachIndexed { index, result -> dialogs[index].creates shouldBe if (result == null) 0 else 1 }
            COUNT_CREATE_BEHOV_SKIPPED_HAS_PRE_EXISTING.count() shouldBe before + 1
            val stored = postgres.findBehovByParameters(
                write.employeeIdentificationNumber.value,
                write.orgNumber.value,
                listOf(BehovStatus.BEHOV_CREATED, BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION),
            )
            stored.size shouldBe 1
            stored.single().id shouldBe results.single { it != null }
            stored.single().behovStatus shouldBe BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION
        }

        test("propagates other database errors unchanged without a skip metric or Dialogporten call") {
            val failure = SQLException("Synthetic database failure", "08006")
            val db = object : NarmestelederDb by FakeNarmestelederDb() {
                override suspend fun insertNlBehov(nlBehov: NarmestelederBehovEntity): NarmestelederBehovEntity = throw failure
            }
            val dialog = RecordingDialogportenClient()
            val before = COUNT_CREATE_BEHOV_SKIPPED_HAS_PRE_EXISTING.count()

            val propagated = shouldThrow<SQLException> {
                conflictTestService(db, dialog).createNewNlBehov(write, true, source, arbeidsgiver)
            }

            (propagated === failure) shouldBe true
            COUNT_CREATE_BEHOV_SKIPPED_HAS_PRE_EXISTING.count() shouldBe before
            dialog.creates shouldBe 0
        }
    })
