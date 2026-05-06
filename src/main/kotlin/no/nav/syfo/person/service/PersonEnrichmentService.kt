package no.nav.syfo.person.service

import no.nav.syfo.narmesteleder.exposed.PersonEntity
import no.nav.syfo.narmesteleder.exposed.PersonTable
import no.nav.syfo.pdl.PdlService
import no.nav.syfo.person.domain.PersonStatus
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.util.UUID

private const val PERSON_ENRICHMENT_BATCH_SIZE = 100

class PersonEnrichmentService(
    private val database: Database,
    private val pdlService: PdlService,
) {
    private val logger = LoggerFactory.getLogger(PersonEnrichmentService::class.java)

    suspend fun enrichPendingPersons() {
        val pendingIdToFnr: Map<UUID, String> = transaction(database) {
            PersonEntity
                .find { PersonTable.status eq PersonStatus.PENDING.name }
                .orderBy(PersonTable.created to SortOrder.ASC)
                .limit(PERSON_ENRICHMENT_BATCH_SIZE)
                .associate { it.id.value to it.fnr }
        }

        if (pendingIdToFnr.isEmpty()) return

        logger.info("Enriching ${pendingIdToFnr.size} pending persons from PDL")

        val pdlPersons = pdlService.getPersonsBolk(pendingIdToFnr.values.toList())

        transaction(database) {
            pendingIdToFnr.forEach { (id, fnr) ->
                val entity = PersonEntity.findById(id) ?: return@forEach
                val pdlPerson = pdlPersons[fnr]
                if (pdlPerson != null) {
                    entity.fornavn = pdlPerson.name.fornavn
                    entity.mellomnavn = pdlPerson.name.mellomnavn
                    entity.etternavn = pdlPerson.name.etternavn
                    entity.foedselsdato = pdlPerson.foedselsdato?.foedselsdato
                    entity.status = PersonStatus.ENRICHED.name
                } else {
                    entity.status = PersonStatus.NOT_FOUND.name
                }
            }
        }

        val enrichedCount = pdlPersons.values.count { it != null }
        val notFoundCount = pendingIdToFnr.size - enrichedCount
        logger.info("Enrichment done: $enrichedCount enriched, $notFoundCount not found")
    }
}
