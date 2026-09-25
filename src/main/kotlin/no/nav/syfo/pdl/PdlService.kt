package no.nav.syfo.pdl

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.application.valkey.PdlCache
import no.nav.syfo.logging.logEvent
import no.nav.syfo.narmesteleder.domain.PersonalIdentificationNumber
import no.nav.syfo.pdl.client.GetPersonBolkResponse
import no.nav.syfo.pdl.client.Ident.Companion.GRUPPE_IDENT_FNR
import no.nav.syfo.pdl.client.PdlClient
import no.nav.syfo.pdl.exception.PdlRequestException
import no.nav.syfo.pdl.exception.PdlResourceNotFoundException
import no.nav.syfo.util.logger

private const val PDL_CHUNK_SIZE = 100

class PdlService(
    private val pdlClient: PdlClient,
    private val pdlCache: PdlCache
) {
    private val logger = logger()

    private fun logDegraded(details: PdlLookupDegradedDetails, cause: Throwable? = null) {
        logger.logEvent(pdlLookupDegraded, details, cause = cause)
    }

    suspend fun getPersonFor(fnr: String): Person {
        val response = pdlClient.getPerson(fnr)
        val data = response.data ?: throw PdlRequestException("Unexpected response from upstream service")

        with(data) {
            val navn = person?.navn?.firstOrNull()
                ?: throw PdlResourceNotFoundException("Could not find name for person")
            val fnr = identer?.identer?.firstOrNull { it.gruppe == GRUPPE_IDENT_FNR }?.ident
                ?: throw PdlResourceNotFoundException("Could not find national identification number for person")
            val foedselsdato = person.foedselsdato?.firstOrNull()

            return Person(
                name = navn,
                names = person.navn,
                nationalIdentificationNumber = PersonalIdentificationNumber(fnr),
                dateOfBirth = foedselsdato,
            )
        }
    }

    suspend fun getPersonOrThrowApiError(fnr: String): Person {
        pdlCache.getPerson(fnr).let { cachedPerson ->
            if (cachedPerson != null) {
                return cachedPerson
            }
        }
        return try {
            val person: Person = getPersonFor(fnr)
            pdlCache.putPerson(fnr, person)
            person
        } catch (e: PdlResourceNotFoundException) {
            throw ApiErrorException.BadRequestException("Could not find person in PDL", e)
        } catch (e: PdlRequestException) {
            throw ApiErrorException.InternalServerErrorException("Error when fetching person from PDL", e)
        }
    }

    suspend fun getPersonsBolk(fnrs: List<String>): Map<String, Person?> {
        if (fnrs.isEmpty()) {
            return emptyMap()
        }

        val token = pdlClient.getSystemToken()
        val responses = coroutineScope {
            fnrs.chunked(PDL_CHUNK_SIZE)
                .map { fnrChunk ->
                    async {
                        getPersonBolkChunk(fnrChunk = fnrChunk, token = token)
                    }
                }
                .awaitAll().filterNotNull()
        }
        return responses
            .onEach { response ->
                val errors = response.errors
                if (!errors.isNullOrEmpty()) {
                    logDegraded(PdlLookupDegradedDetails(PdlLookupDegradedReason.GRAPHQL_ERRORS, errors, errors.size))
                }
            }
            .flatMap { it.toPersonMap().entries }
            .associate { (fnr, person) -> fnr to person }
    }

    private suspend fun getPersonBolkChunk(fnrChunk: List<String>, token: String): GetPersonBolkResponse? = try {
        pdlClient.getPersonBolk(fnrChunk, token)
    } catch (e: CancellationException) {
        throw e
    } catch (e: PdlRequestException) {
        logDegraded(PdlLookupDegradedDetails(PdlLookupDegradedReason.BATCH_FAILED, recordCount = fnrChunk.size), e)
        null
    }

    private fun GetPersonBolkResponse.toPersonMap(): Map<String, Person?> = data?.hentPersonBolk
        ?.associateBy { it.ident }
        ?.mapValues { (_, bolk) ->
            if (bolk.code == "ok" && bolk.person != null) {
                val navn = bolk.person.navn?.firstOrNull() ?: return@mapValues null
                Person(
                    name = navn,
                    nationalIdentificationNumber = PersonalIdentificationNumber(bolk.ident),
                    dateOfBirth = bolk.person.foedselsdato?.firstOrNull(),
                )
            } else {
                null
            }
        }
        ?: emptyMap()
}
