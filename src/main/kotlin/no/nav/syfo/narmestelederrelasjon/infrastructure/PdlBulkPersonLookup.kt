package no.nav.syfo.narmestelederrelasjon.infrastructure

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.integration.pdl.GetPersonBolkResponse
import no.nav.syfo.integration.pdl.HentPersonBolk
import no.nav.syfo.integration.pdl.PdlClient
import no.nav.syfo.integration.pdl.PdlLookupDegradedDetails
import no.nav.syfo.integration.pdl.PdlLookupDegradedReason
import no.nav.syfo.integration.pdl.PdlRequestException
import no.nav.syfo.integration.pdl.pdlLookupDegraded
import no.nav.syfo.logging.logEvent
import no.nav.syfo.narmestelederrelasjon.application.BulkPersonLookup
import no.nav.syfo.narmestelederrelasjon.application.BulkPersonLookupResult
import no.nav.syfo.narmestelederrelasjon.application.RegisteredPerson
import org.slf4j.LoggerFactory

private const val PDL_CHUNK_SIZE = 100

/** Looks up persons in PDL in parallel chunks. A failed chunk leaves its persons out of the result. */
class PdlBulkPersonLookup(
    private val pdlClient: PdlClient,
) : BulkPersonLookup {
    override suspend fun findAll(personIdents: List<PersonIdent>): Map<PersonIdent, BulkPersonLookupResult> {
        if (personIdents.isEmpty()) {
            return emptyMap()
        }

        val token = pdlClient.getSystemToken()
        val responses = coroutineScope {
            personIdents.map(PersonIdent::value)
                .chunked(PDL_CHUNK_SIZE)
                .map { fnrChunk -> async { getPersonBolkChunk(fnrChunk, token) } }
                .awaitAll()
                .filterNotNull()
        }
        val resultsByFnr = responses
            .onEach { response ->
                val errors = response.errors
                if (!errors.isNullOrEmpty()) {
                    logDegraded(PdlLookupDegradedDetails(PdlLookupDegradedReason.GRAPHQL_ERRORS, errors, errors.size))
                }
            }
            .flatMap { it.data?.hentPersonBolk.orEmpty() }
            .associate { it.ident to it.toResult() }

        return personIdents
            .mapNotNull { personIdent -> resultsByFnr[personIdent.value]?.let { personIdent to it } }
            .toMap()
    }

    private suspend fun getPersonBolkChunk(fnrChunk: List<String>, token: String): GetPersonBolkResponse? = try {
        pdlClient.getPersonBolk(fnrChunk, token)
    } catch (e: CancellationException) {
        throw e
    } catch (e: PdlRequestException) {
        logDegraded(PdlLookupDegradedDetails(PdlLookupDegradedReason.BATCH_FAILED, recordCount = fnrChunk.size), e)
        null
    }

    private fun logDegraded(details: PdlLookupDegradedDetails, cause: Throwable? = null) {
        logger.logEvent(pdlLookupDegraded, details, cause = cause)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(PdlBulkPersonLookup::class.java)
    }
}

private fun HentPersonBolk.toResult(): BulkPersonLookupResult {
    val navn = person?.navn?.firstOrNull()
    if (code != "ok" || navn == null) {
        return BulkPersonLookupResult.NotFound
    }
    return BulkPersonLookupResult.Found(
        RegisteredPerson(
            firstName = navn.fornavn,
            middleName = navn.mellomnavn,
            lastName = navn.etternavn,
            birthDate = person?.foedselsdato?.firstOrNull()?.foedselsdato,
        ),
    )
}
