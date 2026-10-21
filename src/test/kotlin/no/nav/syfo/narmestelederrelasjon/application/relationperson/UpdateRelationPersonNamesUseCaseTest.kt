package no.nav.syfo.narmestelederrelasjon.application.relationperson

import ch.qos.logback.classic.Level
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmestelederrelasjon.application.relationperson.InMemoryRelationPersonRepository.Row
import no.nav.syfo.narmestelederrelasjon.application.relationperson.InMemoryRelationPersonRepository.Status
import no.nav.syfo.narmestelederrelasjon.application.relationperson.RelationPersonNameUpdateResult.Completed
import no.nav.syfo.narmestelederrelasjon.application.relationperson.RelationPersonNameUpdateResult.LookupIncomplete
import no.nav.syfo.platform.observability.ProductionLogCapture
import no.nav.syfo.platform.observability.ProductionLogs

class UpdateRelationPersonNamesUseCaseTest :
    FunSpec({
        val kari = "12345678901"
        val ola = "10987654321"
        val per = "11111111111"
        val ada = registeredPerson("Ada", "Lovelace")
        val grace = registeredPerson("Grace", "Hopper")
        val productionLogs = extension(ProductionLogs(UpdateRelationPersonNamesUseCase::class.java, Level.INFO))
        lateinit var logs: ProductionLogCapture

        lateinit var repository: InMemoryRelationPersonRepository
        lateinit var lookup: FakeBulkPersonLookup
        lateinit var metrics: RecordingRelationPersonNameUpdateMetrics

        beforeTest {
            repository = InMemoryRelationPersonRepository()
            lookup = FakeBulkPersonLookup()
            metrics = RecordingRelationPersonNameUpdateMetrics()
            logs = productionLogs.capture()
        }

        afterTest { logs.close() }

        fun lookupAnswers(answer: (List<PersonalIdentificationNumber>) -> Map<PersonalIdentificationNumber, BulkPersonLookupResult>) {
            lookup = FakeBulkPersonLookup(answer)
        }

        suspend fun execute(vararg personidenter: String) = UpdateRelationPersonNamesUseCase(repository, lookup, metrics).execute(personidenter.toList())

        test("updates existing persons with one lookup of distinct, valid personidenter") {
            repository.add(kari, Status.ENRICHED)
            repository.add(ola)
            lookupAnswers { mapOf(PersonalIdentificationNumber(kari) to found(ada), PersonalIdentificationNumber(ola) to found(grace)) }

            val result = execute(kari, per, ola, kari, "not-an-ident")

            result shouldBe Completed(updatedCount = 2, notFoundInRegisterCount = 1)
            lookup.requests shouldBe listOf(listOf(PersonalIdentificationNumber(kari), PersonalIdentificationNumber(ola)))
            repository.row(kari) shouldBe Row(Status.ENRICHED, ada)
            repository.row(ola) shouldBe Row(Status.PENDING, grace)
            repository.updateRegisteredDetailsCalls shouldBe 1
            metrics.lookupFailed.shouldBeEmpty()
        }

        test("skips the batch without relevant personidenter") {
            val result = execute("not-an-ident")

            result shouldBe Completed()
            lookup.requests.shouldBeEmpty()
            repository.updateRegisteredDetailsCalls shouldBe 0
            metrics.lookupFailed.shouldBeEmpty()
        }

        test("does not look up persons that are not in the projection") {
            val result = execute(kari)

            result shouldBe Completed(notFoundInRegisterCount = 1)
            lookup.requests.shouldBeEmpty()
            repository.updateRegisteredDetailsCalls shouldBe 0
            metrics.lookupFailed.shouldBeEmpty()
        }

        test("counts persons not found in PDL without updating them") {
            repository.add(kari)
            lookupAnswers { mapOf(PersonalIdentificationNumber(kari) to BulkPersonLookupResult.NotFound) }

            val result = execute(kari)

            result shouldBe Completed(pdlNotFoundCount = 1)
            lookup.requests shouldBe listOf(listOf(PersonalIdentificationNumber(kari)))
            repository.row(kari) shouldBe Row(Status.PENDING)
            repository.updateRegisteredDetailsCalls shouldBe 0
            metrics.lookupFailed.shouldBeEmpty()
        }

        test("counts persons removed from the projection before the update as not found in register") {
            repository.add(kari)
            repository.add(ola)
            repository.disappearBeforeUpdate += PersonalIdentificationNumber(ola)
            lookupAnswers { idents -> idents.associateWith { found(ada) } }

            val result = execute(kari, ola)

            result shouldBe Completed(updatedCount = 1, notFoundInRegisterCount = 1)
            repository.row(kari) shouldBe Row(Status.PENDING, ada)
            repository.rows shouldNotContainKey PersonalIdentificationNumber(ola)
            metrics.lookupFailed.shouldBeEmpty()
        }

        test("returns an incomplete lookup without updating or logging when some persons could not be looked up") {
            listOf(kari, ola, per).forEach(repository::add)
            lookupAnswers { mapOf(PersonalIdentificationNumber(kari) to found(ada), PersonalIdentificationNumber(ola) to BulkPersonLookupResult.NotFound) }

            val result = execute(kari, ola, per)

            result shouldBe LookupIncomplete(requestedCount = 3, missingCount = 1)
            metrics.lookupFailed shouldBe listOf(1)
            repository.updateRegisteredDetailsCalls shouldBe 0
            listOf(kari, ola, per).map(repository::row) shouldBe List(3) { Row(Status.PENDING) }
            logs.logs.shouldBeEmpty() // The consumer owns the terminal retry event.
        }
    })
