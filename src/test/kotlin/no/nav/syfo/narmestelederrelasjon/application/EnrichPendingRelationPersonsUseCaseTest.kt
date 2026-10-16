package no.nav.syfo.narmestelederrelasjon.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederrelasjon.application.EnrichPendingRelationPersonsUseCase.Companion.BATCH_SIZE
import no.nav.syfo.narmestelederrelasjon.application.InMemoryRelationPersonRepository.Row
import no.nav.syfo.narmestelederrelasjon.application.InMemoryRelationPersonRepository.Status
import java.time.LocalDate

class EnrichPendingRelationPersonsUseCaseTest :
    FunSpec({
        val kari = "12345678901"
        val ola = "98765432109"

        lateinit var repository: InMemoryRelationPersonRepository

        beforeTest {
            repository = InMemoryRelationPersonRepository()
        }

        fun addPending(count: Int) = (1..count).forEach { repository.add(it.toString().padStart(11, '0')) }

        suspend fun enrich(lookup: FakeBulkPersonLookup = FakeBulkPersonLookup()) = lookup.also {
            EnrichPendingRelationPersonsUseCase(repository, it).execute()
        }

        test("does not look up persons when none are pending") {
            repository.add(kari, Status.ENRICHED)
            repository.add(ola, Status.NOT_FOUND)

            enrich().requests.shouldBeEmpty()
        }

        test("stores found persons as enriched and unknown persons as not found") {
            repository.add(kari)
            repository.add(ola)
            val person = RegisteredPerson("Kari", "Marte", "Nordmann", LocalDate.of(1990, 1, 15))

            enrich(
                FakeBulkPersonLookup {
                    mapOf(PersonIdent(kari) to found(person), PersonIdent(ola) to BulkPersonLookupResult.NotFound)
                },
            )

            repository.row(kari) shouldBe Row(Status.ENRICHED, person)
            repository.row(ola) shouldBe Row(Status.NOT_FOUND)
            repository.findPendingLimits shouldBe listOf(BATCH_SIZE)
        }

        test("leaves persons that could not be looked up pending") {
            repository.add(kari)
            repository.add(ola)

            val lookup = enrich(FakeBulkPersonLookup { mapOf(PersonIdent(kari) to found()) })

            lookup.requests shouldBe listOf(listOf(PersonIdent(kari), PersonIdent(ola)))
            repository.row(kari).status shouldBe Status.ENRICHED
            repository.row(ola).status shouldBe Status.PENDING
        }

        test("stops when a full batch makes no progress") {
            addPending(BATCH_SIZE)

            val lookup = enrich()

            lookup.requests.map { it.size } shouldBe listOf(BATCH_SIZE)
            repository.count(Status.PENDING) shouldBe BATCH_SIZE
        }

        test("continues with the next batch while each full batch makes progress") {
            addPending(BATCH_SIZE + 1)

            val lookup = enrich(FakeBulkPersonLookup { idents -> idents.associateWith { found() } })

            lookup.requests.map { it.size } shouldBe listOf(BATCH_SIZE, 1)
            repository.count(Status.ENRICHED) shouldBe BATCH_SIZE + 1
        }
    })
