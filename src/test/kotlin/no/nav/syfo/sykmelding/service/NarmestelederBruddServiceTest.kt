package no.nav.syfo.sykmelding.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederrelasjon.application.RevokeNarmestelederrelasjonFromSendtSykmelding
import no.nav.syfo.narmestelederrelasjon.application.RevokeNarmestelederrelasjonFromSendtSykmeldingCommand
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.NlResponseSource
import no.nav.syfo.sykmelding.exposed.PersistedSendtSykmeldingNarmestelederBrudd
import no.nav.syfo.sykmelding.exposed.SendtSykmeldingNarmestelederBrudd
import no.nav.syfo.sykmelding.exposed.SendtSykmeldingNarmestelederBruddRepository
import no.nav.syfo.sykmelding.kafka.SENDT_SYKMELDING_TOPIC
import java.time.OffsetDateTime
import java.util.UUID

class NarmestelederBruddServiceTest :
    FunSpec({
        test("revokes the relation and stores the processed Kafka record") {
            val fixture = BruddFixture()
            val sykmeldingId = UUID.randomUUID()

            fixture.service.revokeFromSendtSykmelding(sykmeldingId, EMPLOYEE_IDENT, ORGANIZATION_NUMBER, 1, 123)

            fixture.revoked shouldBe listOf(
                RevokeNarmestelederrelasjonFromSendtSykmeldingCommand(
                    PersonIdent(EMPLOYEE_IDENT),
                    OrganizationNumber(ORGANIZATION_NUMBER),
                ),
            )
            val stored = fixture.repository.inserted.single()
            stored.sykmeldingId shouldBe sykmeldingId
            stored.fnr shouldBe EMPLOYEE_IDENT
            stored.orgnummer shouldBe ORGANIZATION_NUMBER
            stored.kafkaTopic shouldBe SENDT_SYKMELDING_TOPIC
            stored.kafkaPartition shouldBe 1
            stored.kafkaOffset shouldBe 123L
            stored.kilde shouldBe SENDT_SYKMELDING_BRUDD_KILDE
        }

        test("does not revoke an already tracked sykmelding") {
            val sykmeldingId = UUID.randomUUID()
            val fixture = BruddFixture(trackedSykmeldingId = sykmeldingId)

            fixture.service.revokeFromSendtSykmelding(sykmeldingId, EMPLOYEE_IDENT, ORGANIZATION_NUMBER, 1, 123)

            fixture.revoked.shouldBeEmpty()
            fixture.repository.inserted.shouldBeEmpty()
        }

        test("does not store the record when the revocation fails") {
            val fixture = BruddFixture(failRevocation = true)

            runCatching {
                fixture.service.revokeFromSendtSykmelding(UUID.randomUUID(), EMPLOYEE_IDENT, ORGANIZATION_NUMBER, 1, 123)
            }.isFailure shouldBe true

            fixture.repository.inserted.shouldBeEmpty()
        }

        test("stored kilde matches the Kafka source of the revocation") {
            SENDT_SYKMELDING_BRUDD_KILDE shouldBe NlResponseSource.ARBEIDSTAGER_SYKMELDING_REVOKE.source
        }
    })

private const val EMPLOYEE_IDENT = "12345678901"
private const val ORGANIZATION_NUMBER = "123456789"

private class BruddFixture(
    trackedSykmeldingId: UUID? = null,
    failRevocation: Boolean = false,
) {
    val revoked = mutableListOf<RevokeNarmestelederrelasjonFromSendtSykmeldingCommand>()
    val repository = FakeBruddRepository(trackedSykmeldingId)
    val service = NarmestelederBruddService(
        revokeNarmestelederrelasjon = RevokeNarmestelederrelasjonFromSendtSykmelding { command ->
            if (failRevocation) error("Kafka unavailable")
            revoked += command
        },
        bruddRepository = repository,
    )
}

private class FakeBruddRepository(
    private val trackedSykmeldingId: UUID?,
) : SendtSykmeldingNarmestelederBruddRepository {
    val inserted = mutableListOf<SendtSykmeldingNarmestelederBrudd>()

    override suspend fun findBySykmeldingId(sykmeldingId: UUID): PersistedSendtSykmeldingNarmestelederBrudd? = if (sykmeldingId == trackedSykmeldingId) {
        PersistedSendtSykmeldingNarmestelederBrudd(
            id = UUID.randomUUID(),
            sykmeldingId = sykmeldingId,
            fnr = EMPLOYEE_IDENT,
            orgnummer = ORGANIZATION_NUMBER,
            kafkaTopic = SENDT_SYKMELDING_TOPIC,
            kafkaPartition = 0,
            kafkaOffset = 0,
            kilde = SENDT_SYKMELDING_BRUDD_KILDE,
            created = OffsetDateTime.now(),
        )
    } else {
        null
    }

    override suspend fun insert(brudd: SendtSykmeldingNarmestelederBrudd) {
        inserted += brudd
    }
}
