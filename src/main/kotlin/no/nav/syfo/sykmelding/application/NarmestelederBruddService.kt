package no.nav.syfo.sykmelding.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmestelederrelasjon.application.revoke.RevokeNarmestelederrelasjonFromSendtSykmelding
import no.nav.syfo.narmestelederrelasjon.application.revoke.RevokeNarmestelederrelasjonFromSendtSykmeldingCommand
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

internal const val SENDT_SYKMELDING_BRUDD_KILDE = "esyo-narmesteleder.arbeidstager.sykmelding.deaktivert"

data class SendtSykmeldingNarmestelederBrudd(
    val sykmeldingId: UUID,
    val fnr: String,
    val orgnummer: String,
    val kafkaTopic: String,
    val kafkaPartition: Int,
    val kafkaOffset: Long,
    val kilde: String,
    val created: OffsetDateTime,
)

data class PersistedSendtSykmeldingNarmestelederBrudd(
    val id: UUID,
    val sykmeldingId: UUID,
    val fnr: String,
    val orgnummer: String,
    val kafkaTopic: String,
    val kafkaPartition: Int,
    val kafkaOffset: Long,
    val kilde: String,
    val created: OffsetDateTime,
)

interface SendtSykmeldingNarmestelederBruddRepository {
    suspend fun findBySykmeldingId(sykmeldingId: UUID): PersistedSendtSykmeldingNarmestelederBrudd?
    suspend fun insert(brudd: SendtSykmeldingNarmestelederBrudd)
}

class NarmestelederBruddService(
    private val revokeNarmestelederrelasjon: RevokeNarmestelederrelasjonFromSendtSykmelding,
    private val bruddRepository: SendtSykmeldingNarmestelederBruddRepository,
) {
    suspend fun revokeFromSendtSykmelding(
        sykmeldingId: UUID,
        fnr: String,
        orgnummer: String,
        kafkaPartition: Int,
        kafkaOffset: Long,
    ) {
        if (bruddRepository.findBySykmeldingId(sykmeldingId) != null) return

        revokeNarmestelederrelasjon.execute(
            RevokeNarmestelederrelasjonFromSendtSykmeldingCommand(
                employeeIdent = PersonalIdentificationNumber(fnr),
                organizationNumber = OrganizationNumber(orgnummer),
            ),
        )

        bruddRepository.insert(
            SendtSykmeldingNarmestelederBrudd(
                sykmeldingId = sykmeldingId,
                fnr = fnr,
                orgnummer = orgnummer,
                kafkaTopic = SENDT_SYKMELDING_TOPIC,
                kafkaPartition = kafkaPartition,
                kafkaOffset = kafkaOffset,
                kilde = SENDT_SYKMELDING_BRUDD_KILDE,
                created = OffsetDateTime.now(ZoneOffset.UTC),
            )
        )
        COUNT_NARMESTELEDER_BRUDD_FROM_SENDT_SYKMELDING.increment()
    }
}
