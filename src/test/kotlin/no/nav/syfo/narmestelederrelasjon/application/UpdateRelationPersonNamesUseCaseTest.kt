package no.nav.syfo.narmestelederrelasjon.application

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederrelasjon.application.InMemoryRelationPersonRepository.Row
import no.nav.syfo.narmestelederrelasjon.application.InMemoryRelationPersonRepository.Status
import org.slf4j.LoggerFactory

class UpdateRelationPersonNamesUseCaseTest :
    FunSpec({
        val kari = "12345678901"
        val ola = "10987654321"
        val per = "11111111111"
        val ada = registeredPerson("Ada", "Lovelace")
        val grace = registeredPerson("Grace", "Hopper")
        val logAppender = ListAppender<ILoggingEvent>()
        val logger = LoggerFactory.getLogger(UpdateRelationPersonNamesUseCase::class.java.name) as Logger

        lateinit var repository: InMemoryRelationPersonRepository
        lateinit var lookup: FakeBulkPersonLookup
        lateinit var metrics: RecordingRelationPersonNameUpdateMetrics

        beforeSpec {
            logger.level = Level.INFO
            logAppender.start()
            logger.addAppender(logAppender)
        }

        afterSpec {
            logger.detachAppender(logAppender)
        }

        beforeTest {
            repository = InMemoryRelationPersonRepository()
            lookup = FakeBulkPersonLookup()
            metrics = RecordingRelationPersonNameUpdateMetrics()
            logAppender.list.clear()
        }

        fun lookupAnswers(answer: (List<PersonIdent>) -> Map<PersonIdent, BulkPersonLookupResult>) {
            lookup = FakeBulkPersonLookup(answer)
        }

        suspend fun execute(vararg personidenter: String) =
            UpdateRelationPersonNamesUseCase(repository, lookup, metrics).execute(personidenter.toList())

        test("updates existing persons with one lookup of distinct, valid personidenter") {
            repository.add(kari, Status.ENRICHED)
            repository.add(ola)
            lookupAnswers { mapOf(PersonIdent(kari) to found(ada), PersonIdent(ola) to found(grace)) }

            val result = execute(kari, per, ola, kari, "not-an-ident")

            result shouldBe RelationPersonNameUpdateResult(updatedCount = 2, notFoundInRegisterCount = 1)
            lookup.requests shouldBe listOf(listOf(PersonIdent(kari), PersonIdent(ola)))
            repository.row(kari) shouldBe Row(Status.ENRICHED, ada)
            repository.row(ola) shouldBe Row(Status.PENDING, grace)
            repository.updateRegisteredDetailsCalls shouldBe 1
            metrics.lookupFailed.shouldBeEmpty()
        }

        test("skips the batch without relevant personidenter") {
            val result = execute("not-an-ident")

            result shouldBe RelationPersonNameUpdateResult()
            lookup.requests.shouldBeEmpty()
            repository.updateRegisteredDetailsCalls shouldBe 0
            metrics.lookupFailed.shouldBeEmpty()
        }

        test("does not look up persons that are not in the projection") {
            val result = execute(kari)

            result shouldBe RelationPersonNameUpdateResult(notFoundInRegisterCount = 1)
            lookup.requests.shouldBeEmpty()
            repository.updateRegisteredDetailsCalls shouldBe 0
            metrics.lookupFailed.shouldBeEmpty()
        }

        test("counts persons not found in PDL without updating them") {
            repository.add(kari)
            lookupAnswers { mapOf(PersonIdent(kari) to BulkPersonLookupResult.NotFound) }

            val result = execute(kari)

            result shouldBe RelationPersonNameUpdateResult(pdlNotFoundCount = 1)
            lookup.requests shouldBe listOf(listOf(PersonIdent(kari)))
            repository.row(kari) shouldBe Row(Status.PENDING)
            repository.updateRegisteredDetailsCalls shouldBe 0
            metrics.lookupFailed.shouldBeEmpty()
        }

        test("counts persons removed from the projection before the update as not found in register") {
            repository.add(kari)
            repository.add(ola)
            repository.disappearBeforeUpdate += PersonIdent(ola)
            lookupAnswers { idents -> idents.associateWith { found(ada) } }

            val result = execute(kari, ola)

            result shouldBe RelationPersonNameUpdateResult(updatedCount = 1, notFoundInRegisterCount = 1)
            repository.row(kari) shouldBe Row(Status.PENDING, ada)
            repository.rows shouldNotContainKey PersonIdent(ola)
            metrics.lookupFailed.shouldBeEmpty()
        }

        test("fails without updating or logging when some persons could not be looked up") {
            listOf(kari, ola, per).forEach(repository::add)
            lookupAnswers { mapOf(PersonIdent(kari) to found(ada), PersonIdent(ola) to BulkPersonLookupResult.NotFound) }

            val failure = shouldThrow<IncompleteBulkPersonLookupException> { execute(kari, ola, per) }

            failure.requestedCount shouldBe 3
            failure.missingCount shouldBe 1
            metrics.lookupFailed shouldBe listOf(1)
            repository.updateRegisteredDetailsCalls shouldBe 0
            listOf(kari, ola, per).map(repository::row) shouldBe List(3) { Row(Status.PENDING) }
            logAppender.list.shouldBeEmpty() // The consumer owns the terminal retry event.
        }
    })
