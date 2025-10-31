package no.nav.syfo.narmesteleder.service

import java.util.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import no.nav.syfo.aareg.AaregService
import no.nav.syfo.narmesteleder.db.INarmestelederDb
import no.nav.syfo.narmesteleder.db.NarmestelederBehovEntity
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmesteleder.domain.Name
import no.nav.syfo.narmesteleder.domain.LinemanagerRequirementRead
import no.nav.syfo.narmesteleder.domain.LinemanagerRequirementWrite
import no.nav.syfo.narmesteleder.domain.Manager
import no.nav.syfo.narmesteleder.exception.LinemanagerRequirementNotFoundException
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

    suspend fun getNlBehovById(id: UUID): LinemanagerRequirementRead =
        withContext(ioDispatcher) {
            with(findBehovEntityById(id)) {
                val details = pdlService.getPersonFor(sykmeldtFnr)
                // TODO: Kan vurdere valkey her eller å lagre siste kjente navndetaljer ved insert/update av behov
                val name = Name(
                    firstName = details.name.fornavn,
                    lastName = details.name.etternavn,
                    middleName = details.name.mellomnavn,
                )
                toEmployeeLinemanagerRead(name)
            }
        }

    private suspend fun findBehovEntityById(id: UUID): NarmestelederBehovEntity =
        withContext(ioDispatcher) {
            nlDb.findBehovById(id)
        } ?: throw LinemanagerRequirementNotFoundException("NarmestelederBehovEntity not found for id: $id")

    suspend fun updateNlBehov(
        manager: Manager,
        requirementId: UUID,
        behovStatus: BehovStatus
    ) = withContext(ioDispatcher) {
        with(findBehovEntityById(requirementId)) {
            val updatedBehov = copy(
                narmestelederFnr = manager.nationalIdentificationNumber,
                behovStatus = behovStatus,
            )
            nlDb.updateNlBehov(updatedBehov)
            logger.info("Updated NarmestelederBehovEntity with id: $id with status: $behovStatus")
        }
    }

    private suspend fun findHovedenhetOrgnummer(personIdent: String, orgNumber: String): String {
        val arbeidsforholdMap = aaregService.findOrgNumbersByPersonIdent(personIdent)
        return arbeidsforholdMap[orgNumber]
            ?: throw HovedenhetNotFoundException("Could not find main entity for employee on sick leave and orgnumber in aareg")
    }

    suspend fun createNewNlBehov(nlBehov: LinemanagerRequirementWrite) {
        if (!persistLeesahNlBehov) {
            logger.info("Skipping persistence of LinemanagerRequirement as configured.")
            return
        }

        withContext(ioDispatcher) {
            val id = nlDb.insertNlBehov(
                NarmestelederBehovEntity(
                    sykmeldtFnr = nlBehov.employeeIdentificationNumber,
                    orgnummer = nlBehov.orgnumber,
                    hovedenhetOrgnummer = findHovedenhetOrgnummer(
                        nlBehov.employeeIdentificationNumber,
                        nlBehov.orgnumber
                    ),
                    narmestelederFnr = nlBehov.managerIdentificationNumber,
                    leesahStatus = nlBehov.leesahStatus,
                    behovStatus = BehovStatus.RECEIVED
                )
            )
            logger.info("Inserted NarmestelederBehovEntity with id: $id")
        }
    }
}

fun NarmestelederBehovEntity.toEmployeeLinemanagerRead(name: Name): LinemanagerRequirementRead =
    LinemanagerRequirementRead(
        id = this.id ?: throw MissingIDException("NarmestelederBehovEntity entity id is null"),
        employeeIdentificationNumber = this.sykmeldtFnr,
        orgnumber = this.orgnummer,
        mainOrgnumber = this.hovedenhetOrgnummer,
        managerIdentificationNumber = this.narmestelederFnr,
        name = name
    )
