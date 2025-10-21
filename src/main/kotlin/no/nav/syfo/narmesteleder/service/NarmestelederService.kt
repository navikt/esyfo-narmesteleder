package no.nav.syfo.narmesteleder.service

import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.syfo.aareg.AaregService
import no.nav.syfo.narmesteleder.api.v1.toNlBehovRead
import no.nav.syfo.narmesteleder.db.INarmestelederDb
import no.nav.syfo.narmesteleder.db.NarmestelederBehovEntity
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmesteleder.domain.NlBehovRead
import no.nav.syfo.narmesteleder.domain.NlBehovUpdate
import no.nav.syfo.narmesteleder.domain.NlBehovWrite
import org.slf4j.LoggerFactory

class HovedenhetNotFoundException(message: String) : RuntimeException(message)
class BehovNotFoundException(message: String) : RuntimeException(message)

class NarmesteLederService(
    private val nlDb: INarmestelederDb,
    private val persistLeesahNlBehov: Boolean,
    private val aaregService: AaregService,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private suspend fun <T> withDefaultIOContext(block: suspend () -> T) = withContext(Dispatchers.IO) { block() }

    suspend fun getNlBehovById(id: UUID): NlBehovRead =
        withDefaultIOContext {
            nlDb.findBehovById(id)?.toNlBehovRead()
        } ?: throw BehovNotFoundException("Narmesteleder-behov not found for id: $id")


    suspend fun updateNlBehov(
        nlBehovUpdate: NlBehovUpdate,
        behovStatus: BehovStatus
    ) = withDefaultIOContext {
        val behov = nlDb.findBehovById(nlBehovUpdate.id)
            ?: throw BehovNotFoundException("Narmesteleder-behov not found for id: ${nlBehovUpdate.id}")

        val updatedBehov = behov.copy(
            orgnummer = nlBehovUpdate.orgnummer,
            hovedenhetOrgnummer = findHovedenhetOrgnummer(nlBehovUpdate.sykmeldtFnr),
            sykmeldtFnr = nlBehovUpdate.sykmeldtFnr,
            narmestelederFnr = nlBehovUpdate.narmesteLederFnr,
            behovStatus = behovStatus,
        )

        nlDb.updateNlBehov(updatedBehov)
        logger.info("Updated NL-Behov id: ${nlBehovUpdate.id} with status: $behovStatus")
    }


    private suspend fun findHovedenhetOrgnummer(personIdent: String): String =
        aaregService.findOrgNumbersByPersonIdent(personIdent).getOrElse(personIdent) {
            throw HovedenhetNotFoundException("Could not find hovedenhet for sykemeldt")
        }

    suspend fun createNewNlBehov(nlBehov: NlBehovWrite) {
        if (!persistLeesahNlBehov) {
            logger.info("Skipping persistence of NL Behov as configured.")
            return
        }

        withDefaultIOContext {
            val id = nlDb.insertNlBehov(
                NarmestelederBehovEntity(
                    sykmeldtFnr = nlBehov.sykmeldtFnr,
                    orgnummer = nlBehov.orgnummer,
                    hovedenhetOrgnummer = findHovedenhetOrgnummer(nlBehov.sykmeldtFnr),
                    narmestelederFnr = nlBehov.narmesteLederFnr,
                    leesahStatus = nlBehov.leesahStatus,
                    behovStatus = BehovStatus.RECEIVED
                )
            )
            logger.info("Inserted nærmeste leder-behov with id: $id")
        }
    }

    suspend fun findAllNlBehov(personIdent: String, orgNumber: String): Set<NlBehovRead> {
        val hovedenhetNummer = aaregService.findOrgNumbersByPersonIdent(personIdent).getOrElse(orgNumber) {
            throw HovedenhetNotFoundException("Could not find hovedenhet")
        }

        return nlDb.findAllBehovByHovedenhetOrgnummer(hovedenhetNummer).map { it.toNlBehovRead() }.toSet()
    }
}
