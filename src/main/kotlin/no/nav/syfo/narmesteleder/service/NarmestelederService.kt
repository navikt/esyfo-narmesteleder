package no.nav.syfo.narmesteleder.service

import java.util.*
import no.nav.syfo.aareg.AaregService
import no.nav.syfo.dinesykmeldte.DinesykmeldteService
import no.nav.syfo.narmesteleder.db.INarmestelederDb
import no.nav.syfo.narmesteleder.db.NarmestelederBehovEntity
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmesteleder.domain.Employee
import no.nav.syfo.narmesteleder.domain.LinemanagerRequirementRead
import no.nav.syfo.narmesteleder.domain.LinemanagerRequirementWrite
import no.nav.syfo.narmesteleder.domain.Manager
import no.nav.syfo.narmesteleder.domain.Name
import no.nav.syfo.narmesteleder.exception.HovedenhetNotFoundException
import no.nav.syfo.narmesteleder.exception.LinemanagerRequirementNotFoundException
import no.nav.syfo.narmesteleder.exception.MissingIDException
import no.nav.syfo.pdl.PdlService
import org.slf4j.LoggerFactory

class NarmestelederService(
    private val nlDb: INarmestelederDb,
    private val persistLeesahNlBehov: Boolean,
    private val aaregService: AaregService,
    private val pdlService: PdlService,
    private val dinesykmeldteService: DinesykmeldteService,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun getLinemanagerRequirementReadById(id: UUID): LinemanagerRequirementRead =
        with(findBehovEntityById(id)) {
            val name = if (fornavn != null && etternavn != null) {
                Name(
                    firstName = fornavn,
                    lastName = etternavn,
                    middleName = mellomnavn,
                )
            } else {
                val details = pdlService.getPersonFor(sykmeldtFnr)
                val updated = this.copy(
                    fornavn = details.name.fornavn,
                    mellomnavn = details.name.mellomnavn,
                    etternavn = details.name.etternavn,
                )
                nlDb.updateNlBehov(updated)
                Name(
                    firstName = details.name.fornavn,
                    lastName = details.name.etternavn,
                    middleName = details.name.mellomnavn,
                )
            }
            toEmployeeLinemanagerRead(name)
        }


    private suspend fun findBehovEntityById(id: UUID): NarmestelederBehovEntity =
        nlDb.findBehovById(id)
            ?: throw LinemanagerRequirementNotFoundException("NarmestelederBehovEntity not found for id: $id")

    suspend fun updateNlBehov(
        manager: Manager,
        requirementId: UUID,
        behovStatus: BehovStatus
    ) = with(findBehovEntityById(requirementId)) {
        val updatedBehov = copy(
            narmestelederFnr = manager.nationalIdentificationNumber,
            behovStatus = behovStatus,
        )
        nlDb.updateNlBehov(updatedBehov)
        logger.info("Updated NarmestelederBehovEntity with id: $id with status: $behovStatus")
    }

    private suspend fun findHovedenhetOrgnummer(personIdent: String, orgNumber: String): String {
        val arbeidsforholdMap = aaregService.findOrgNumbersByPersonIdent(personIdent)
        return arbeidsforholdMap[orgNumber]
            ?: throw HovedenhetNotFoundException(
                "Could not find main entity for employee on sick leave and orgnumber in aareg"
            )
    }

    suspend fun createNewNlBehov(nlBehov: LinemanagerRequirementWrite): UUID? {
        if (!persistLeesahNlBehov) {
            logger.info("Skipping persistence of LinemanagerRequirement as configured.")
            return null // TODO: Fjern nullable når vi begynner å lagre
        }
        val hasActiveSykmelding =
            dinesykmeldteService.getIsActiveSykmelding(nlBehov.employeeIdentificationNumber, nlBehov.orgnumber)
        return if (hasActiveSykmelding) {
            nlDb.insertNlBehov(
                NarmestelederBehovEntity.fromLinemanagerRequirementWrite(
                    nlBehov, findHovedenhetOrgnummer(
                        nlBehov.employeeIdentificationNumber,
                        nlBehov.orgnumber
                    ), BehovStatus.RECEIVED
                )
            ).also {
                logger.info("Inserted NarmestelederBehovEntity with id: $it.id")
            }
        } else {
            logger.info("Not inserting NarmestelederBehovEntity as there is no active sick leave for employee with narmestelederId ${nlBehov.revokedLinemanagerId} in org ${nlBehov.orgnumber}")
            null
        }

    }

    suspend fun getEmployeeByRequirementId(id: UUID): Employee {
        val behovEntity = findBehovEntityById(id)
        return Employee(
            nationalIdentificationNumber = behovEntity.sykmeldtFnr,
            orgnumber = behovEntity.orgnummer
        )
    }
}

fun NarmestelederBehovEntity.toEmployeeLinemanagerRead(name: Name): LinemanagerRequirementRead =
    LinemanagerRequirementRead(
        id = this.id ?: throw MissingIDException("NarmestelederBehovEntity entity id is null"),
        employeeIdentificationNumber = this.sykmeldtFnr,
        orgnumber = this.orgnummer,
        mainOrgnumber = this.hovedenhetOrgnummer,
        managerIdentificationNumber = this.narmestelederFnr,
        name = name,
    )
