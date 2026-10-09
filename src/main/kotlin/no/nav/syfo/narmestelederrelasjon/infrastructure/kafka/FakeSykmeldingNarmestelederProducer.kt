package no.nav.syfo.narmestelederrelasjon.infrastructure.kafka

import no.nav.syfo.util.logger

class FakeSykmeldingNarmestelederProducer : SykmeldingNarmestelederProducer {
    val logger = logger()
    override fun sendSykmeldingNLRelasjon(sykmeldingNL: NlResponse, source: NlResponseSource) {
        logger.info("FakeSykemeldingNLKafkaProducer sendSykemeldingNLRelasjon on behalf of source: ${source.name}")
    }

    override fun sendSykmldingNLBrudd(nlAvbrutt: NlAvbrutt, source: NlResponseSource) {
        logger.info("FakeSykemeldingNLKafkaProducer sendSykemeldingNLBrudd on behalf of source: ${source.name}")
    }
}
