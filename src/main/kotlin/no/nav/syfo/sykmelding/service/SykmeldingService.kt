package no.nav.syfo.sykmelding.service

import no.nav.syfo.sykmelding.db.SykmeldingDb
import no.nav.syfo.sykmelding.model.SendtSykmeldingKafkaMessage
import no.nav.syfo.sykmelding.model.toDbEntity

class SykmeldingService(private val sykmeldingDb: SykmeldingDb) {
    suspend fun insertSykmelding(
        sendtSykmelding: SendtSykmeldingKafkaMessage
    ) = sykmeldingDb.insertSykmelding(sendtSykmelding.toDbEntity())

}
