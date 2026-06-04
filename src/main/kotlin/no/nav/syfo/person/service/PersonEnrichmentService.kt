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

private const val PERSON_ENRICHMENT_BATCH_SIZE = 500

class PersonEnrichmentService(
    private val database: Database,
    private val pdlService: PdlService,
) {
    private val logger = LoggerFactory.getLogger(PersonEnrichmentService::class.java)

    suspend fun enrichPendingPersons() {
        while (true) {
            val personEntities = transaction(database) {
                PersonEntity
                    .find { PersonTable.status eq PersonStatus.PENDING.name }
                    .orderBy(PersonTable.created to SortOrder.ASC)
                    .limit(PERSON_ENRICHMENT_BATCH_SIZE).toSet()
            }
            val pendingFnr = personEntities.map { it.fnr }
            if (pendingFnr.isEmpty()) break

            logger.info("Enriching ${personEntities.size} pending persons from PDL")

            val pdlPersons = pdlService.getPersonsBolk(pendingFnr)

            transaction(database) {
                personEntities.forEach { entity ->
                    val pdlPerson = pdlPersons[entity.fnr]
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
            val notFoundCount = pendingFnr.size - enrichedCount
            logger.info("Enrichment done: $enrichedCount enriched, $notFoundCount not found")

            if (pendingFnr.size < PERSON_ENRICHMENT_BATCH_SIZE) break
        }
    }
}
