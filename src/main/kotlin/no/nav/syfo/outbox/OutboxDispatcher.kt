package no.nav.syfo.outbox

import com.fasterxml.jackson.databind.ObjectMapper
import no.nav.syfo.narmesteleder.kafka.ISykmeldingNLKafkaProducer

interface OutboxEventHandler {
    val destination: OutboxDestination
    val eventType: OutboxEventType
    fun dispatch(payload: String, payloadVersion: Int)
}

class SykmeldingNlRelasjonOutboxHandler(
    private val producer: ISykmeldingNLKafkaProducer,
    private val objectMapper: ObjectMapper,
) : OutboxEventHandler {
    override val destination = OutboxDestination.SYKMELDING_NL
    override val eventType = OutboxEventType.NL_RELASJON

    override fun dispatch(payload: String, payloadVersion: Int) {
        require(payloadVersion == CURRENT_PAYLOAD_VERSION) {
            "Unsupported payload version $payloadVersion for $destination/$eventType"
        }
        val event = objectMapper.readValue(payload, OutboxNlRelasjonPayload::class.java)
        producer.sendSykmeldingNLRelasjon(event.nlResponse, event.source)
    }

    companion object {
        const val CURRENT_PAYLOAD_VERSION = 1
    }
}

class SykmeldingNlAvbruttOutboxHandler(
    private val producer: ISykmeldingNLKafkaProducer,
    private val objectMapper: ObjectMapper,
) : OutboxEventHandler {
    override val destination = OutboxDestination.SYKMELDING_NL
    override val eventType = OutboxEventType.NL_AVBRUTT

    override fun dispatch(payload: String, payloadVersion: Int) {
        require(payloadVersion == CURRENT_PAYLOAD_VERSION) {
            "Unsupported payload version $payloadVersion for $destination/$eventType"
        }
        val event = objectMapper.readValue(payload, OutboxNlAvbruttPayload::class.java)
        producer.sendSykmldingNLBrudd(event.nlAvbrutt, event.source)
    }

    companion object {
        const val CURRENT_PAYLOAD_VERSION = 1
    }
}

class UnknownOutboxRouteException(
    destination: String,
    eventType: String,
) : IllegalStateException("No outbox handler configured for destination=$destination eventType=$eventType")

class OutboxDispatcher(
    handlers: List<OutboxEventHandler>,
) {
    private val handlersByRoute = handlers.associateBy { it.destination to it.eventType }

    fun dispatch(event: OutboxEvent) {
        val destination = event.parsedDestination()
        val eventType = event.parsedEventType()

        val handler = destination?.let { resolvedDestination ->
            eventType?.let { resolvedEventType ->
                handlersByRoute[resolvedDestination to resolvedEventType]
            }
        } ?: throw UnknownOutboxRouteException(event.destination, event.eventType)

        handler.dispatch(
            payload = event.payload,
            payloadVersion = event.payloadVersion,
        )
    }
}
