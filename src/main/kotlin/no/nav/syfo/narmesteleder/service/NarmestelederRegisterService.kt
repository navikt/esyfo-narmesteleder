package no.nav.syfo.narmesteleder.service

import no.nav.syfo.narmesteleder.exposed.InsertedPerson
import no.nav.syfo.narmesteleder.exposed.PersonBatchInsertRow
import no.nav.syfo.narmesteleder.exposed.narmestelederTable
import no.nav.syfo.narmesteleder.exposed.personTable
import no.nav.syfo.narmesteleder.kafka.LeesahNarmestelederRecord
import no.nav.syfo.narmesteleder.kafka.model.NarmestelederLeesahKafkaMessage
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory

class NarmestelederRegisterService(
    private val database: Database,
) {
    fun upsertBatch(records: List<LeesahNarmestelederRecord>) {
        if (records.isEmpty()) {
            return
        }

        val validRecords = records.filter(::isValidForRegister)
        if (validRecords.isEmpty()) {
            return
        }

        transaction(database) {
            validRecords.forEach { record ->
                narmestelederTable.upsertFromLeesahKafkaMessage(record.message)
            }
        }

        COUNT_NARMESTELEDER_REGISTER_UPSERTED.increment(validRecords.size.toDouble())
    }

    fun insertPersons(records: List<LeesahNarmestelederRecord>): List<InsertedPerson> {
        val validRecords = records.filter(::isValidForPersonRegister)
        if (validRecords.isEmpty()) {
            return emptyList()
        }
        val personFnrs = mutableSetOf<PersonBatchInsertRow>()
        validRecords.forEach { record ->
            personFnrs.add(
                PersonBatchInsertRow(
                    fnr = record.message.fnr,
                    status = "PENDING"
                )
            )
            personFnrs.add(
                PersonBatchInsertRow(
                    fnr = record.message.narmesteLederFnr,
                    status = "PENDING"
                )
            )
        }

        val insertedPersons = transaction(database) {
            personTable.batchInsertIgnoreExisting(
                personFnrs
                    .filter { it.fnr.isDigitsWithLength(FNR_LENGTH) }
                    .distinctBy { it.fnr }
            )
        }
        return insertedPersons
    }

    private fun isValidForRegister(record: LeesahNarmestelederRecord): Boolean {
        val validationError = record.message.validateForRegister() ?: return true

        logger.warn(
            "Skipping invalid leesah record for register with narmesteLederId={} and offset={}: {}",
            record.message.narmesteLederId,
            record.offset,
            validationError
        )
        COUNT_NARMESTELEDER_REGISTER_INVALID_MESSAGE.increment()
        return false
    }

    private fun NarmestelederLeesahKafkaMessage.validateForRegister(): String? = when {
        !fnr.isDigitsWithLength(FNR_LENGTH) -> "fnr must be exactly $FNR_LENGTH digits"
        !orgnummer.isDigitsWithLength(ORGNUMMER_LENGTH) -> "orgnummer must be exactly $ORGNUMMER_LENGTH digits"
        !narmesteLederFnr.isDigitsWithLength(FNR_LENGTH) -> "narmesteLederFnr must be exactly $FNR_LENGTH digits"
        narmesteLederTelefonnummer.length > TEXT_FIELD_MAX_LENGTH -> "narmesteLederTelefonnummer exceeds max length"
        narmesteLederEpost.length > TEXT_FIELD_MAX_LENGTH -> "narmesteLederEpost exceeds max length"
        else -> null
    }

    private fun isValidForPersonRegister(record: LeesahNarmestelederRecord): Boolean {
        record.message.validateForPersonRegister() ?: return true
        return false
    }

    private fun NarmestelederLeesahKafkaMessage.validateForPersonRegister(): String? = when {
        !fnr.isDigitsWithLength(FNR_LENGTH) -> "fnr must be exactly $FNR_LENGTH digits"
        !narmesteLederFnr.isDigitsWithLength(FNR_LENGTH) -> "narmesteLederFnr must be exactly $FNR_LENGTH digits"
        else -> null
    }

    private fun String.isDigitsWithLength(length: Int): Boolean = this.length == length && all(Char::isDigit)

    companion object {
        private val logger = LoggerFactory.getLogger(NarmestelederRegisterService::class.java)
        private const val FNR_LENGTH = 11
        private const val ORGNUMMER_LENGTH = 9
        private const val TEXT_FIELD_MAX_LENGTH = 255
    }
}
