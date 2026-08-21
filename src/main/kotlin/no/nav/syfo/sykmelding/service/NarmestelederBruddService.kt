package no.nav.syfo.sykmelding.service

import no.nav.syfo.narmesteleder.domain.OrganizationNumber
import no.nav.syfo.narmesteleder.domain.PersonalIdentificationNumber
import no.nav.syfo.narmesteleder.kafka.model.NlResponseSource
import no.nav.syfo.narmesteleder.service.NarmestelederKafkaService
import no.nav.syfo.sykmelding.exposed.ISendtSykmeldingNarmestelederBruddRepository
import no.nav.syfo.sykmelding.exposed.SendtSykmeldingNarmestelederBrudd
import no.nav.syfo.sykmelding.kafka.SENDT_SYKMELDING_TOPIC
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class NarmestelederBruddService(
    private val narmestelederKafkaService: NarmestelederKafkaService,
    private val bruddRepository: ISendtSykmeldingNarmestelederBruddRepository,
) {
    suspend fun revokeFromSendtSykmelding(
        sykmeldingId: UUID,
        fnr: String,
        orgnummer: String,
        kafkaPartition: Int,
        kafkaOffset: Long,
    ) {
        if (bruddRepository.findBySykmeldingId(sykmeldingId) != null) return

        val source = NlResponseSource.ARBEIDSTAGER_SYKMELDING_REVOKE

        narmestelederKafkaService.avbrytNarmesteLederRelation(
            employeeIdentificationNumber = PersonalIdentificationNumber(fnr),
            orgNumber = OrganizationNumber(orgnummer),
            source = source,
        )

        bruddRepository.insert(
            SendtSykmeldingNarmestelederBrudd(
                sykmeldingId = sykmeldingId,
                fnr = fnr,
                orgnummer = orgnummer,
                kafkaTopic = SENDT_SYKMELDING_TOPIC,
                kafkaPartition = kafkaPartition,
                kafkaOffset = kafkaOffset,
                kilde = source.source,
                created = OffsetDateTime.now(ZoneOffset.UTC),
            )
        )
        COUNT_NARMESTELEDER_BRUDD_FROM_SENDT_SYKMELDING.increment()
    }
}
