package no.nav.syfo.narmesteleder.service

import java.util.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import no.nav.syfo.aareg.AaregService
import no.nav.syfo.narmesteleder.db.INarmestelederDb
import no.nav.syfo.narmesteleder.db.NarmestelederBehovEntity
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmesteleder.domain.Name
import no.nav.syfo.narmesteleder.domain.NlBehovRead
import no.nav.syfo.narmesteleder.domain.NlBehovUpdate
import no.nav.syfo.narmesteleder.domain.NlBehovWrite
import no.nav.syfo.narmesteleder.exception.BehovNotFoundException
import no.nav.syfo.narmesteleder.exception.HovedenhetNotFoundException
import no.nav.syfo.narmesteleder.exception.MissingIDException
import no.nav.syfo.pdl.PdlService
import org.slf4j.LoggerFactory

class NarmestelederService(
    private val nlDb: INarmestelederDb,
    private val persistLeesahNlBehov: Boolean,
    private val aaregService: AaregService,
    private val pdlService: PdlService,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    suspend fun getNlBehovById(id: UUID): NlBehovRead =
        withContext(ioDispatcher) {
            nlDb.findBehovById(id)?.let {
                val details = pdlService.getPersonFor(it.sykmeldtFnr)
                // TODO: Kan vurdere valkey her eller å lagre siste kjente navndetaljer ved insert/update av behov
                val name = Name(
                    firstName = details.name.fornavn,
                    lastName = details.name.etternavn,
                    middleName = details.name.mellomnavn,
                )
                it.toNlBehovRead(name)
            }
        } ?: throw BehovNotFoundException("Narmesteleder-behov not found for id: $id")

    suspend fun updateNlBehov(
        nlBehovUpdate: NlBehovUpdate,
        behovStatus: BehovStatus
    ) = withContext(ioDispatcher) {
        val behov = nlDb.findBehovById(nlBehovUpdate.id)
            ?: throw BehovNotFoundException("Narmesteleder-behov not found for id: ${nlBehovUpdate.id}")

        val updatedBehov = behov.copy(
            orgnummer = nlBehovUpdate.orgnummer,
            hovedenhetOrgnummer = findHovedenhetOrgnummer(nlBehovUpdate.sykmeldtFnr, nlBehovUpdate.orgnummer),
            sykmeldtFnr = nlBehovUpdate.sykmeldtFnr,
            narmestelederFnr = nlBehovUpdate.narmesteLederFnr,
            behovStatus = behovStatus,
        )

        nlDb.updateNlBehov(updatedBehov)
        logger.info("Updated NL-Behov id: ${nlBehovUpdate.id} with status: $behovStatus")
    }

    private suspend fun findHovedenhetOrgnummer(personIdent: String, orgNumber: String): String {
        val arbeidsforholdMap = aaregService.findOrgNumbersByPersonIdent(personIdent)
        return arbeidsforholdMap[orgNumber]
            ?: throw HovedenhetNotFoundException("Could not find hovedenhet for sykemeldt and orgnummer")
    }

    suspend fun createNewNlBehov(nlBehov: NlBehovWrite) {
        if (!persistLeesahNlBehov) {
            logger.info("Skipping persistence of NL Behov as configured.")
            return
        }

        withContext(ioDispatcher) {
            val id = nlDb.insertNlBehov(
                NarmestelederBehovEntity(
                    sykmeldtFnr = nlBehov.sykmeldtFnr,
                    orgnummer = nlBehov.orgnummer,
                    hovedenhetOrgnummer = findHovedenhetOrgnummer(nlBehov.sykmeldtFnr, nlBehov.orgnummer),
                    narmestelederFnr = nlBehov.narmesteLederFnr,
                    leesahStatus = nlBehov.leesahStatus,
                    behovStatus = BehovStatus.RECEIVED
                )
            )
            logger.info("Inserted nærmeste leder-behov with id: $id")
        }
    }
}

fun NarmestelederBehovEntity.toNlBehovRead(name: Name): NlBehovRead = NlBehovRead(
    id = this.id ?: throw MissingIDException("NL Behov entity id is null"),
    sykmeldtFnr = this.sykmeldtFnr,
    orgnummer = this.orgnummer,
    hovedenhetOrgnummer = this.hovedenhetOrgnummer,
    narmesteLederFnr = this.narmestelederFnr,
    name = name
)
