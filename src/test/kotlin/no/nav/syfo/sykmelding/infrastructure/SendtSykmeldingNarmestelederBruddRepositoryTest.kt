package no.nav.syfo.sykmelding.infrastructure

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import no.nav.syfo.TestDB
import no.nav.syfo.sykmelding.application.SendtSykmeldingNarmestelederBrudd
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class SendtSykmeldingNarmestelederBruddRepositoryTest :
    FunSpec({
        val repository = PostgresSendtSykmeldingNarmestelederBruddRepository(TestDB.exposedDatabase)

        beforeTest {
            TestDB.clearAllData()
        }

        test("stores and finds a tracked NL relation revoke") {
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
