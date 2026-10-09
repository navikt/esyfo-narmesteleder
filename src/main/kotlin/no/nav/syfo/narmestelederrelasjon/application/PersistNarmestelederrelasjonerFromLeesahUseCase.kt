package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.logging.applicationEvent
import no.nav.syfo.logging.logEvent
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

private data class NlRegisterRecordInvalidDetails(
    val narmestelederId: String,
    val partition: Int,
    val offset: Long,
    val validationReason: String,
)

private val nlRegisterRecordInvalid = applicationEvent<NlRegisterRecordInvalidDetails>(
    name = "nl_register_record_invalid",
    level = Level.WARN,
    message = "Nearest leader register record failed validation and was skipped",
    fields = mapOf(
        "narmesteleder_id" to { it.narmestelederId },
        "partition" to { it.partition },
        "offset" to { it.offset },
        "validation_reason" to { it.validationReason },
    ),
)

/**
 * Stores the valid relations from a batch of Leesah records in the local relation register and
 * registers their persons for enrichment. Invalid records are logged and skipped.
 *
 * Returns the valid records, which are the ones that may be republished.
 */
class PersistNarmestelederrelasjonerFromLeesahUseCase(
    private val repository: LeesahNarmestelederrelasjonRepository,
    private val metrics: NarmestelederRegisterMetrics,
) {
    fun execute(records: List<LeesahNarmestelederrelasjonRecord>): List<LeesahNarmestelederrelasjonRecord> {
        val validRecords = records.filter(::isValid)
        if (validRecords.isEmpty()) {
            return emptyList()
        }

        val relasjoner = validRecords.map { it.relasjon }
        val personFnrs = relasjoner
            .flatMap { listOf(it.sykmeldtFnr, it.narmestelederFnr) }
            .distinct()
        repository.upsertAll(relasjoner, personFnrs)
        metrics.recordUpserted(relasjoner.size)

        return validRecords
    }

    private fun isValid(record: LeesahNarmestelederrelasjonRecord): Boolean {
        val validationError = record.relasjon.validationError() ?: return true

        logger.logEvent(
            nlRegisterRecordInvalid,
            NlRegisterRecordInvalidDetails(
                narmestelederId = record.relasjon.narmestelederId.toString(),
                partition = record.partition,
                offset = record.offset,
                validationReason = validationError,
            ),
        )
        metrics.recordInvalid()
        return false
    }

    private fun LeesahNarmestelederrelasjon.validationError(): String? = when {
        !PersonIdent.isValid(sykmeldtFnr) -> "fnr must be exactly 11 digits"
        !OrganizationNumber.isValid(orgnummer) -> "orgnummer must be exactly 9 digits"
        !PersonIdent.isValid(narmestelederFnr) -> "narmesteLederFnr must be exactly 11 digits"
        narmestelederTelefonnummer.length > TEXT_FIELD_MAX_LENGTH -> "narmesteLederTelefonnummer exceeds max length"
        narmestelederEpost.length > TEXT_FIELD_MAX_LENGTH -> "narmesteLederEpost exceeds max length"
        else -> null
    }

    companion object {
        private val logger = LoggerFactory.getLogger(PersistNarmestelederrelasjonerFromLeesahUseCase::class.java)
        private const val TEXT_FIELD_MAX_LENGTH = 255
    }
}
