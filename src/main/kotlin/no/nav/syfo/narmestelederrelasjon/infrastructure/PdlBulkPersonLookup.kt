package no.nav.syfo.narmestelederrelasjon.infrastructure

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.integration.pdl.GetPersonBolkResponse
import no.nav.syfo.integration.pdl.HentPersonBolk
import no.nav.syfo.integration.pdl.PdlClient
import no.nav.syfo.integration.pdl.PdlLookupDegradedDetails
import no.nav.syfo.integration.pdl.PdlLookupDegradedReason
import no.nav.syfo.integration.pdl.pdlLookupDegraded
import no.nav.syfo.integration.pdl.toUpstreamError
import no.nav.syfo.narmestelederrelasjon.application.relationperson.BulkPersonLookup
import no.nav.syfo.narmestelederrelasjon.application.relationperson.BulkPersonLookupResult
import no.nav.syfo.narmestelederrelasjon.application.relationperson.RegisteredPerson
import no.nav.syfo.platform.observability.applicationLogger
import no.nav.syfo.platform.observability.logEvent
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamResult

private const val PDL_CHUNK_SIZE = 100

/** Looks up persons in PDL in parallel chunks. A failed chunk leaves its persons out of the result. */
class PdlBulkPersonLookup(
    private val pdlClient: PdlClient,
) : BulkPersonLookup {
    override suspend fun findAll(personIdents: List<PersonalIdentificationNumber>): Map<PersonalIdentificationNumber, BulkPersonLookupResult> {
        if (personIdents.isEmpty()) {
            return emptyMap()
        }

        val responses = coroutineScope {
            personIdents.map(PersonalIdentificationNumber::value)
                .chunked(PDL_CHUNK_SIZE)
                .map { fnrChunk -> async { getPersonBolkChunk(fnrChunk) } }
                .awaitAll()
                .filterNotNull()
        }
        val resultsByFnr = responses
            .onEach { response ->
                val errors = response.errors
                if (!errors.isNullOrEmpty()) {
                    logDegraded(PdlLookupDegradedDetails(PdlLookupDegradedReason.GRAPHQL_ERRORS, errors.map { it.toUpstreamError() }, errors.size))
                }
            }
            .flatMap { it.data?.hentPersonBolk.orEmpty() }
            .associate { it.ident to it.toResult() }

        return personIdents
            .mapNotNull { personIdent -> resultsByFnr[personIdent.value]?.let { personIdent to it } }
            .toMap()
    }

    private suspend fun getPersonBolkChunk(fnrChunk: List<String>): GetPersonBolkResponse? = when (val result = pdlClient.getPersonBolk(fnrChunk)) {
        is UpstreamResult.Success -> result.value

        is UpstreamResult.Failure -> {
            logDegraded(PdlLookupDegradedDetails(PdlLookupDegradedReason.BATCH_FAILED, recordCount = fnrChunk.size), result.failure)
            null
        }
    }

    private fun logDegraded(details: PdlLookupDegradedDetails, upstreamFailure: UpstreamFailure? = null) {
        logger.logEvent(pdlLookupDegraded, details, upstreamFailure = upstreamFailure)
    }

    companion object {
        private val logger = applicationLogger(PdlBulkPersonLookup::class.java)
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
            birthDate = person.foedselsdato?.firstOrNull()?.foedselsdato,
        ),
    )
}
