package no.nav.syfo.sykmelding.exposed

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import no.nav.syfo.TestDB
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class SendtSykmeldingNarmestelederBruddRepositoryTest :
    DescribeSpec({
        val repository = SendtSykmeldingNarmestelederBruddRepository(TestDB.exposedDatabase)

        beforeTest {
            transaction(TestDB.exposedDatabase) {
                SendtSykmeldingNarmestelederBruddTable.deleteAll()
            }
        }

        it("stores and finds a tracked NL relation revoke") {
            val brudd = SendtSykmeldingNarmestelederBrudd(
                sykmeldingId = UUID.randomUUID(),
                fnr = "12345678901",
                orgnummer = "123456789",
                kafkaTopic = "teamsykmelding.syfo-sendt-sykmelding",
                kafkaPartition = 1,
                kafkaOffset = 123,
                kilde = "esyo-narmesteleder.arbeidstager.sykmelding.deaktivert",
                created = OffsetDateTime.of(2026, 8, 14, 8, 0, 0, 0, ZoneOffset.UTC),
            )

            repository.insert(brudd)

            val persistedBrudd = repository.findBySykmeldingId(brudd.sykmeldingId).shouldNotBeNull()
            persistedBrudd.sykmeldingId shouldBe brudd.sykmeldingId
            persistedBrudd.fnr shouldBe brudd.fnr
            persistedBrudd.orgnummer shouldBe brudd.orgnummer
            persistedBrudd.kafkaTopic shouldBe brudd.kafkaTopic
            persistedBrudd.kafkaPartition shouldBe brudd.kafkaPartition
            persistedBrudd.kafkaOffset shouldBe brudd.kafkaOffset
            persistedBrudd.kilde shouldBe brudd.kilde
            persistedBrudd.created shouldBe brudd.created
        }
    })
