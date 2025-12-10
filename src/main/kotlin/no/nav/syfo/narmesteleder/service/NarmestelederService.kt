package no.nav.syfo.narmesteleder.service

import java.time.Instant
import java.util.*
import no.nav.syfo.aareg.AaregService
import no.nav.syfo.altinn.dialogporten.service.DialogportenService
import no.nav.syfo.dinesykmeldte.DinesykmeldteService
import no.nav.syfo.narmesteleder.db.INarmestelederDb
import no.nav.syfo.narmesteleder.db.NarmestelederBehovEntity
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmesteleder.domain.Employee
import no.nav.syfo.narmesteleder.domain.LineManagerRequirementStatus
import no.nav.syfo.narmesteleder.domain.LinemanagerRequirementRead
import no.nav.syfo.narmesteleder.domain.LinemanagerRequirementWrite
import no.nav.syfo.narmesteleder.domain.Name
import no.nav.syfo.narmesteleder.domain.RevokedBy
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
    private val dialogportenService: DialogportenService,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun getLinemanagerRequirementReadById(id: UUID): LinemanagerRequirementRead =
        with(findBehovEntityById(id)) {
            val name = getName()
            toEmployeeLinemanagerRead(name)
        }

    private suspend fun NarmestelederBehovEntity.getName(): Name =
        if (fornavn != null && etternavn != null) {
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

    private suspend fun findBehovEntityById(id: UUID): NarmestelederBehovEntity =
        nlDb.findBehovById(id)
            ?: throw LinemanagerRequirementNotFoundException("NarmestelederBehovEntity not found for id: $id")

    suspend fun updateNlBehov(
        requirementId: UUID,
        behovStatus: BehovStatus
    ) {
        val narmestelederBehovEntity = findBehovEntityById(requirementId)
        updateNlBehov(narmestelederBehovEntity, behovStatus)
    }

    suspend fun updateNlBehov(
        behovEntity: NarmestelederBehovEntity,
        behovStatus: BehovStatus
    ) {
        val updatedBehov = behovEntity.copy(
            behovStatus = behovStatus,
        )
        nlDb.updateNlBehov(updatedBehov)
        logger.info("Updated NarmestelederBehovEntity with id: $updatedBehov.id with status: $behovStatus")
        dialogportenService.setToCompletedInDialogportenIfFulfilled(updatedBehov)
    }

    suspend fun findClosableBehovs(sykmeldtFnr: String, orgnummer: String)
    : List<NarmestelederBehovEntity> {
        return nlDb.findBehovByParameters(sykmeldtFnr =  sykmeldtFnr, orgnummer =  orgnummer, behovStatus = listOf(
            BehovStatus.BEHOV_CREATED, BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION))
    }

    private suspend fun findHovedenhetOrgnummer(personIdent: String, orgNumber: String): String {
        val arbeidsforholdMap = aaregService.findOrgNumbersByPersonIdent(personIdent)
        return arbeidsforholdMap[orgNumber]
            ?: throw HovedenhetNotFoundException(
                "Could not find main entity for employee on sick leave and orgnumber in aareg"
            )
    }

    suspend fun createNewNlBehov(
        nlBehov: LinemanagerRequirementWrite,
        hovedenhetOrgnummer: String? = null,
        skipSykmeldingCheck: Boolean = false
    ): UUID? {
        if (!persistLeesahNlBehov) {
            logger.info("Skipping persistence of LinemanagerRequirement as configured.")
            return null // TODO: Fjern nullable når vi begynner å lagre
        }
        val isActiveSykmelding = skipSykmeldingCheck ||
            dinesykmeldteService.getIsActiveSykmelding(nlBehov.employeeIdentificationNumber, nlBehov.orgNumber)

        return if (isActiveSykmelding) {
            val hovededenhet = hovedenhetOrgnummer ?: findHovedenhetOrgnummer(
                nlBehov.employeeIdentificationNumber,
                nlBehov.orgNumber
            )
            val entity = NarmestelederBehovEntity.fromLinemanagerRequirementWrite(
                nlBehov,
                hovedenhetOrgnummer = hovededenhet,
                behovStatus = BehovStatus.BEHOV_CREATED,
            )
            val insertedEntity = nlDb.insertNlBehov(entity).also {
                logger.info("Inserted NarmestelederBehovEntity with id: $it")
            }
            return insertedEntity.id
        } else {
            logger.info("Not inserting NarmestelederBehovEntity as there is no active sick leave for employee with narmestelederId ${nlBehov.revokedLinemanagerId} in org ${nlBehov.orgNumber}")
            null
        }
    }

    suspend fun getEmployeeByRequirementId(id: UUID): Employee {
        val behovEntity = findBehovEntityById(id)
        return Employee(
            nationalIdentificationNumber = behovEntity.sykmeldtFnr,
            orgNumber = behovEntity.orgnummer,
            lastName = behovEntity.etternavn ?: "",
        )
    }

    suspend fun getNlBehovList(
        orgNumber: String,
        createdAfter: Instant,
        pageSize: Int
    ): List<LinemanagerRequirementRead> =
        nlDb.findBehovByParameters(
            orgNumber = orgNumber,
            createdAfter = createdAfter,
            status = listOf(BehovStatus.BEHOV_CREATED, BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION),
            limit = pageSize + 1,
        ).map { it.toEmployeeLinemanagerRead(it.getName()) }
}

fun NarmestelederBehovEntity.toEmployeeLinemanagerRead(name: Name): LinemanagerRequirementRead =
    LinemanagerRequirementRead(
        id = this.id ?: throw MissingIDException("NarmestelederBehovEntity entity id is null"),
        employeeIdentificationNumber = this.sykmeldtFnr,
        orgNumber = this.orgnummer,
        mainOrgNumber = this.hovedenhetOrgnummer,
        managerIdentificationNumber = this.narmestelederFnr,
        name = name,
        created = this.created,
        updated = this.updated,
        status = LineManagerRequirementStatus.from(this.behovStatus),
        revokedBy = RevokedBy.from(this.behovReason),
    )
