package no.nav.syfo.narmesteleder.service

import com.fasterxml.jackson.databind.ObjectMapper
import no.nav.syfo.application.kafka.jacksonMapper
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmesteleder.domain.Linemanager
import no.nav.syfo.narmesteleder.domain.LinemanagerActors
import no.nav.syfo.narmesteleder.domain.LinemanagerRevoke
import no.nav.syfo.narmesteleder.exposed.NlBehovTable
import no.nav.syfo.narmesteleder.kafka.ISykmeldingNLKafkaProducer
import no.nav.syfo.narmesteleder.kafka.model.NlAvbrutt
import no.nav.syfo.narmesteleder.kafka.model.NlResponse
import no.nav.syfo.narmesteleder.kafka.model.NlResponseSource
import no.nav.syfo.narmesteleder.kafka.model.Sykmeldt
import no.nav.syfo.outbox.OutboxDestination
import no.nav.syfo.outbox.OutboxDirectSender
import no.nav.syfo.outbox.OutboxEventRepository
import no.nav.syfo.outbox.OutboxEventType
import no.nav.syfo.outbox.OutboxNlAvbruttPayload
import no.nav.syfo.outbox.OutboxNlRelasjonPayload
import no.nav.syfo.outbox.PersistOutboxEvent
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

class NarmestelederKafkaService(
    private val mode: DispatchMode,
    private val objectMapper: ObjectMapper = jacksonMapper(),
) {
    constructor(kafkaSykemeldingProducer: ISykmeldingNLKafkaProducer) : this(
        mode = DispatchMode.LegacyDirect(kafkaSykemeldingProducer),
    )

    constructor(
        outboxEventRepository: OutboxEventRepository,
        outboxDirectSender: OutboxDirectSender,
        database: Database,
        objectMapper: ObjectMapper = jacksonMapper(),
    ) : this(
        mode = DispatchMode.Outbox(
            outboxEventRepository = outboxEventRepository,
            outboxDirectSender = outboxDirectSender,
            database = database,
        ),
        objectMapper = objectMapper,
    )

    suspend fun sendNarmesteLederRelasjon(
        linemanager: Linemanager,
        linemanagerActors: LinemanagerActors,
        source: NlResponseSource,
    ) {
        val nlResponse = createNlResponse(
            linemanager = linemanager,
            linemanagerActors = linemanagerActors,
        )

        when (mode) {
            is DispatchMode.LegacyDirect -> mode.kafkaSykemeldingProducer.sendSykmeldingNLRelasjon(nlResponse, source)
            is DispatchMode.Outbox -> {
                val eventId = mode.outboxEventRepository.persist(
                    PersistOutboxEvent(
                        destination = OutboxDestination.SYKMELDING_NL,
                        eventType = OutboxEventType.NL_RELASJON,
                        kafkaKey = linemanager.orgNumber,
                        payload = objectMapper.writeValueAsString(
                            OutboxNlRelasjonPayload(
                                nlResponse = nlResponse,
                                source = source,
                            ),
                        ),
                        payloadVersion = CURRENT_PAYLOAD_VERSION,
                    ),
                )
                mode.outboxDirectSender.sendById(eventId)
            }
        }
    }

    suspend fun avbrytNarmesteLederRelation(
        linemanagerRevoke: LinemanagerRevoke,
        source: NlResponseSource,
    ) {
        val nlAvbrutt = NlAvbrutt(
            sykmeldtFnr = linemanagerRevoke.employeeIdentificationNumber,
            orgnummer = linemanagerRevoke.orgNumber,
        )

        when (mode) {
            is DispatchMode.LegacyDirect -> mode.kafkaSykemeldingProducer.sendSykmldingNLBrudd(nlAvbrutt, source)
            is DispatchMode.Outbox -> {
                val eventId = mode.outboxEventRepository.persist(
                    PersistOutboxEvent(
                        destination = OutboxDestination.SYKMELDING_NL,
                        eventType = OutboxEventType.NL_AVBRUTT,
                        kafkaKey = linemanagerRevoke.orgNumber,
                        payload = objectMapper.writeValueAsString(
                            OutboxNlAvbruttPayload(
                                nlAvbrutt = nlAvbrutt,
                                source = source,
                            ),
                        ),
                        payloadVersion = CURRENT_PAYLOAD_VERSION,
                    ),
                )
                mode.outboxDirectSender.sendById(eventId)
            }
        }
    }

    suspend fun fulfillRequirementAndSendNarmesteLederRelasjon(
        requirementId: UUID,
        linemanager: Linemanager,
        linemanagerActors: LinemanagerActors,
        source: NlResponseSource,
    ) {
        val outboxMode = mode as? DispatchMode.Outbox
            ?: error("fulfillRequirementAndSendNarmesteLederRelasjon requires outbox mode")

        val nlResponse = createNlResponse(
            linemanager = linemanager,
            linemanagerActors = linemanagerActors,
        )

        val eventId = outboxMode.outboxEventRepository.inTransaction {
            updateRequirementStatus(
                requirementId = requirementId,
                behovStatus = BehovStatus.BEHOV_FULFILLED,
            )
            outboxMode.outboxEventRepository.persistInTransaction(
                PersistOutboxEvent(
                    destination = OutboxDestination.SYKMELDING_NL,
                    eventType = OutboxEventType.NL_RELASJON,
                    kafkaKey = linemanager.orgNumber,
                    payload = objectMapper.writeValueAsString(
                        OutboxNlRelasjonPayload(
                            nlResponse = nlResponse,
                            source = source,
                        ),
                    ),
                    payloadVersion = CURRENT_PAYLOAD_VERSION,
                ),
            )
        }

        outboxMode.outboxDirectSender.sendById(eventId)
    }

    private fun createNlResponse(
        linemanager: Linemanager,
        linemanagerActors: LinemanagerActors,
    ): NlResponse = NlResponse(
        sykmeldt = Sykmeldt.from(linemanagerActors.employee),
        leder = linemanager.manager.toLeder(linemanagerActors.manager),
        orgnummer = linemanager.orgNumber,
        // I den tidligere nærmeste leder-løsningen ble det rapportert hvorvidt arbeidsgiver forskutterer lønn i samme skjema som
        // nærmeste leder. Ved overgangen til Altinn 3 og overføringen av NL til esyfo, gikk man bort fra dette.
        // Denne settes til true for bakoverkompabilitet på Kafka-meldingene fram til helseytelser er ute av Altinn 2.
        utbetalesLonn = true,
    )

    private fun updateRequirementStatus(
        requirementId: UUID,
        behovStatus: BehovStatus,
    ) {
        val updatedRows = NlBehovTable.update({ NlBehovTable.id eq requirementId }) {
            it[NlBehovTable.behovStatus] = behovStatus
        }
        require(updatedRows == 1) {
            "Expected to update one nl_behov row for id=$requirementId, but updated $updatedRows"
        }
    }

    sealed interface DispatchMode {
        data class LegacyDirect(
            val kafkaSykemeldingProducer: ISykmeldingNLKafkaProducer,
        ) : DispatchMode

        data class Outbox(
            val outboxEventRepository: OutboxEventRepository,
            val outboxDirectSender: OutboxDirectSender,
            val database: Database,
        ) : DispatchMode
    }

    companion object {
        private const val CURRENT_PAYLOAD_VERSION = 1
    }
}
