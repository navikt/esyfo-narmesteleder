package no.nav.syfo.narmestelederbehov.application

import ch.qos.logback.classic.Level
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.CancellationException
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.logging.ProductionLogs
import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.platform.upstream.UpstreamError
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class SendPendingNarmestelederbehovDialogsUseCaseTest :
    FunSpec({
        val logs = extension(ProductionLogs(SendPendingNarmestelederbehovDialogsUseCase::class.java, Level.WARN))
        val mapper = jacksonObjectMapper()

        test("no pending behov means no person lookup, dialog creation or marking") {
            val fixture = SendDialogsFixture(emptyList())

            fixture.useCase.execute()

            fixture.queries shouldBe listOf(SEND_NOW.minusSeconds(10) to 100)
            fixture.effects shouldBe emptyList()
        }

        listOf(1, 3).forEach { count ->
            test("creates and conditionally marks $count behov in query order with the enriched person") {
                val batch = (1..count).map { pendingDialog(it) }
                val fixture = SendDialogsFixture(batch)

                logs.capture().use { capture ->
                    fixture.useCase.execute()
                    capture.records shouldBe emptyList()
                }

                fixture.dialogs shouldBe batch.map { Triple(it.id, it.employee, SEND_PERSON) }
                fixture.marked shouldBe batch.map { Triple(it.id, SEND_DIALOG_ID, SEND_PERSON.name) }
                fixture.effects shouldBe batch.flatMap { listOf("person", "create", "mark") }
                fixture.queries.size shouldBe 1
            }
        }

        test("PDL NotFound creates without a name and stays silent") {
            val fixture = SendDialogsFixture(listOf(pendingDialog()))
            fixture.lookup = { EmployeePersonLookupResult.NotFound }

            logs.capture().use { capture ->
                fixture.useCase.execute()
                capture.records shouldBe emptyList()
            }

            fixture.dialogs.single().third shouldBe null
            fixture.marked.single().third shouldBe null
        }

        test("PDL Unavailable creates without a name and logs only the enrichment failure") {
            val behov = pendingDialog().copy(dialogId = UUID.randomUUID())
            val fixture = SendDialogsFixture(listOf(behov))
            fixture.lookup = { EmployeePersonLookupResult.Unavailable(sendFailure("pdl", UpstreamFailureStage.RESPONSE, 502)) }

            logs.capture().use { capture ->
                fixture.useCase.execute()
                val output = capture.records.single()
                val record = mapper.readTree(output)
                record["event_type"].asText() shouldBe "dialog_person_enrichment_failed"
                record["behov_id"].asText() shouldBe behov.id.value.toString()
                record["dialog_id"].asText() shouldBe behov.dialogId.toString()
                record["upstream"].asText() shouldBe "pdl"
                record["failure_stage"].asText() shouldBe "response"
                record["upstream_status"].asInt() shouldBe 502
                output shouldNotContain "private-diagnostic-canary"
            }

            fixture.dialogs.single().third shouldBe null
            fixture.marked.single().third shouldBe null
        }

        listOf("dialogporten", "texas", "altinn-authentication").forEach { upstream ->
            test("CREATE preserves $upstream failure metadata, does not mark and continues with the next behov") {
                val first = pendingDialog().copy(dialogId = UUID.randomUUID())
                val second = pendingDialog(2)
                val fixture = SendDialogsFixture(listOf(first, second))
                val stage = if (upstream == "dialogporten") UpstreamFailureStage.RESPONSE else UpstreamFailureStage.TOKEN_EXCHANGE
                fixture.create = { id ->
                    if (id == first.id) {
                        NarmestelederbehovDialogCreationResult.Unavailable(sendFailure(upstream, stage, 503))
                    } else {
                        NarmestelederbehovDialogCreationResult.Created(SEND_DIALOG_ID)
                    }
                }

                logs.capture().use { capture ->
                    fixture.useCase.execute()
                    val output = capture.records.single()
                    val record = mapper.readTree(output)
                    record["event_type"].asText() shouldBe "dialogporten_dialog_failed"
                    record["action"].asText() shouldBe "CREATE"
                    record["behov_id"].asText() shouldBe first.id.value.toString()
                    record["dialog_id"].asText() shouldBe first.dialogId.toString()
                    record["upstream"].asText() shouldBe upstream
                    record["failure_stage"].asText() shouldBe stage.name.lowercase()
                    record["upstream_status"].asInt() shouldBe 503
                    record["upstream_errors"].toString().contains("server_error") shouldBe true
                    output shouldNotContain "private-diagnostic-canary"
                    output shouldNotContain first.employee.personIdent.value
                    output shouldNotContain SEND_PERSON.name.firstName
                }

                fixture.dialogs.map { it.first } shouldBe listOf(first.id, second.id)
                fixture.marked.map { it.first } shouldBe listOf(second.id)
            }
        }

        test("logs the newly created dialog when the behov has left BEHOV_CREATED") {
            val behov = pendingDialog()
            val fixture = SendDialogsFixture(listOf(behov))
            fixture.markResult = MarkDialogCreatedResult.NotCreated

            logs.capture().use { capture ->
                fixture.useCase.execute()
                val record = mapper.readTree(capture.records.single())
                record["event_type"].asText() shouldBe "dialog_created_for_closed_behov"
                record["behov_id"].asText() shouldBe behov.id.value.toString()
                record["dialog_id"].asText() shouldBe SEND_DIALOG_ID.toString()
            }
        }

        listOf(100, 101, 200).forEach { count ->
            test("refetches after a full batch of 100 for $count pending behov and stops after a smaller batch") {
                val batch = (1..count).map { pendingDialog(it) }
                val fixture = SendDialogsFixture(batch)

                fixture.useCase.execute()

                fixture.dialogs.map { it.first } shouldBe batch.map { it.id }
                fixture.marked.size shouldBe count
                fixture.queries shouldBe List(count / 100 + 1) { SEND_NOW.minusSeconds(10) to 100 }
            }
        }

        test("preserves legacy refetch of the same failed behov after a full batch until cancellation") {
            val batch = (1..100).map { pendingDialog(it) }
            val fixture = SendDialogsFixture(batch)
            fixture.create = { NarmestelederbehovDialogCreationResult.Unavailable(sendFailure("dialogporten", UpstreamFailureStage.RESPONSE, 503)) }
            fixture.fetch = { query ->
                if (query == 3) throw CancellationException("stop test")
                batch
            }

            logs.capture().use {
                shouldThrow<CancellationException> { fixture.useCase.execute() }
            }

            fixture.dialogs.map { it.first } shouldBe (batch + batch).map { it.id }
            fixture.marked shouldBe emptyList()
            fixture.queries.size shouldBe 3
        }

        test("unexpected person exceptions enrich without a name and do not stop the batch") {
            val fixture = SendDialogsFixture(listOf(pendingDialog(), pendingDialog(2)))
            fixture.lookup = { throw IllegalStateException("private-diagnostic-canary") }

            logs.capture().use { capture ->
                fixture.useCase.execute()
                capture.records.size shouldBe 2
                capture.records.forEach { output ->
                    mapper.readTree(output)["event_type"].asText() shouldBe "dialog_person_enrichment_failed"
                    output shouldNotContain "private-diagnostic-canary"
                }
            }
            fixture.marked.size shouldBe 2
            fixture.dialogs.map { it.third } shouldBe listOf(null, null)
        }

        listOf("create", "mark").forEach { step ->
            test("unexpected $step exceptions log CREATE and continue the batch") {
                val fixture = SendDialogsFixture(listOf(pendingDialog(), pendingDialog(2)))
                if (step == "create") {
                    fixture.create = { throw IllegalStateException("private-diagnostic-canary") }
                }
                if (step == "mark") {
                    fixture.mark = { throw IllegalStateException("private-diagnostic-canary") }
                }

                logs.capture().use { capture ->
                    fixture.useCase.execute()
                    capture.records.size shouldBe 2
                    capture.records.forEach { output ->
                        val record = mapper.readTree(output)
                        record["event_type"].asText() shouldBe "dialogporten_dialog_failed"
                        record["action"].asText() shouldBe "CREATE"
                        output shouldNotContain "private-diagnostic-canary"
                    }
                }
                fixture.dialogs.size shouldBe 2
                fixture.marked shouldBe emptyList()
            }
        }

        listOf("fetch", "person", "create", "mark").forEach { step ->
            test("cancellation from $step is rethrown without failure logging or processing the next behov") {
                val cancellation = CancellationException("cancel test")
                val fixture = SendDialogsFixture(listOf(pendingDialog(), pendingDialog(2)))
                when (step) {
                    "fetch" -> fixture.fetch = { throw cancellation }
                    "person" -> fixture.lookup = { throw cancellation }
                    "create" -> fixture.create = { throw cancellation }
                    "mark" -> fixture.mark = { throw cancellation }
                }

                logs.capture().use { capture ->
                    shouldThrow<CancellationException> { fixture.useCase.execute() } shouldBe cancellation
                    capture.records shouldBe emptyList()
                }
                fixture.dialogs.size shouldBe if (step in listOf("create", "mark")) 1 else 0
                fixture.marked shouldBe emptyList()
            }
        }
    })

internal val SEND_NOW: Instant = Instant.parse("2026-10-01T10:00:00Z")
private val SEND_DIALOG_ID = UUID.fromString("00000000-0000-0000-0000-000000000044")
private val SEND_PERSON = EmployeePerson(BehovPersonName("Synthetic", "Middle", "Employee"), LocalDate.of(1990, 1, 2))

private fun pendingDialog(number: Int = 1) = PendingNarmestelederbehovDialog(
    id = NarmestelederbehovId(UUID(0, number.toLong())),
    employee = Employee(PersonalIdentificationNumber("12345678901"), OrganizationNumber("910000001")),
    created = SEND_NOW.minusSeconds(300),
    dialogId = null,
)

private fun sendFailure(upstream: String, stage: UpstreamFailureStage, status: Int) = UpstreamFailure(
    UpstreamName(upstream),
    stage,
    status,
    IllegalStateException("private-diagnostic-canary"),
    errors = listOf(UpstreamError("server_error")),
)

internal class SendDialogsFixture(batch: List<PendingNarmestelederbehovDialog>) {
    private val pending = batch.toMutableList()
    val queries = mutableListOf<Pair<Instant, Int>>()
    val effects = mutableListOf<String>()
    val dialogs = mutableListOf<Triple<NarmestelederbehovId, Employee, EmployeePerson?>>()
    val marked = mutableListOf<Triple<NarmestelederbehovId, UUID, BehovPersonName?>>()
    var lookup: suspend () -> EmployeePersonLookupResult = { EmployeePersonLookupResult.Found(SEND_PERSON) }
    var create: suspend (NarmestelederbehovId) -> NarmestelederbehovDialogCreationResult = { NarmestelederbehovDialogCreationResult.Created(SEND_DIALOG_ID) }
    var markResult: MarkDialogCreatedResult = MarkDialogCreatedResult.Marked
    var mark: suspend () -> MarkDialogCreatedResult = { markResult }
    var fetch: suspend (Int) -> List<PendingNarmestelederbehovDialog> = { pending.take(100) }
    private val repository = object : NarmestelederbehovRepository {
        override suspend fun findPendingDialogs(createdBefore: Instant, limit: Int): List<PendingNarmestelederbehovDialog> {
            queries += createdBefore to limit
            return fetch(queries.size)
        }

        override suspend fun findDetails(id: NarmestelederbehovId) = error("Unexpected findDetails")
        override suspend fun saveEmployeeName(id: NarmestelederbehovId, name: BehovPersonName) = error("Unexpected saveEmployeeName")
        override suspend fun findForFulfillment(id: NarmestelederbehovId) = error("Unexpected findForFulfillment")
        override suspend fun findOpenFor(employee: Employee) = error("Unexpected findOpenFor")
        override suspend fun markFulfilled(id: NarmestelederbehovId) = error("Unexpected markFulfilled")
        override suspend fun markDialogCompleted(id: NarmestelederbehovId) = error("Unexpected markDialogCompleted")
        override suspend fun create(behov: NewNarmestelederbehov) = error("Unexpected create")

        override suspend fun markDialogCreated(id: NarmestelederbehovId, dialogId: UUID, name: BehovPersonName?): MarkDialogCreatedResult {
            effects += "mark"
            val result = mark()
            marked += Triple(id, dialogId, name)
            pending.removeAll { it.id == id }
            return result
        }
    }
    val useCase = SendPendingNarmestelederbehovDialogsUseCase(
        repository = repository,
        personLookup = {
            effects += "person"
            lookup()
        },
        dialog = { id, employee, person ->
            effects += "create"
            dialogs += Triple(id, employee, person)
            create(id)
        },
        clock = Clock.fixed(SEND_NOW, ZoneOffset.UTC),
    )
}
