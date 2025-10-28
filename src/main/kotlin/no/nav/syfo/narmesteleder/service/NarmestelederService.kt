package no.nav.syfo.narmesteleder.service

import java.util.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import no.nav.syfo.aareg.AaregService
import no.nav.syfo.narmesteleder.db.INarmestelederDb
import no.nav.syfo.narmesteleder.db.NarmestelederBehovEntity
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmesteleder.domain.Name
import no.nav.syfo.narmesteleder.domain.EmployeeLeaderConnectionRead
import no.nav.syfo.narmesteleder.domain.EmployeeLeaderConnectionUpdate
import no.nav.syfo.narmesteleder.domain.EmployeeLeaderConnectionWrite
import no.nav.syfo.narmesteleder.exception.EmployeeLeaderConnectionRequirementNotFoundException
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
    suspend fun getNlBehovById(id: UUID): EmployeeLeaderConnectionRead =
        withContext(ioDispatcher) {
            nlDb.findBehovById(id)?.let {
                val details = pdlService.getPersonFor(it.sykmeldtFnr)
                // TODO: Kan vurdere valkey her eller å lagre siste kjente navndetaljer ved insert/update av behov
                val name = Name(
                    firstName = details.name.fornavn,
                    lastName = details.name.etternavn,
                    middleName = details.name.mellomnavn,
                )
                it.toEmployeeLeaderConnctionRead(name)
            }
        } ?: throw EmployeeLeaderConnectionRequirementNotFoundException("NarmestelederBehovEntity not found for id: $id")

    suspend fun updateNlBehov(
        employeeLeaderConnectionUpdate: EmployeeLeaderConnectionUpdate,
        behovStatus: BehovStatus
    ) = withContext(ioDispatcher) {
        val behov = nlDb.findBehovById(employeeLeaderConnectionUpdate.id)
            ?: throw EmployeeLeaderConnectionRequirementNotFoundException("NarmestelederBehovEntity not found for id: ${employeeLeaderConnectionUpdate.id}")

        val updatedBehov = behov.copy(
            orgnummer = employeeLeaderConnectionUpdate.orgnumber,
            hovedenhetOrgnummer = findHovedenhetOrgnummer(employeeLeaderConnectionUpdate.employeeIdentificationNumber, employeeLeaderConnectionUpdate.orgnumber),
            sykmeldtFnr = employeeLeaderConnectionUpdate.employeeIdentificationNumber,
            narmestelederFnr = employeeLeaderConnectionUpdate.leaderIdentificationNumber,
            behovStatus = behovStatus,
        )

        nlDb.updateNlBehov(updatedBehov)
        logger.info("Updated NarmestelederBehovEntity with id: ${employeeLeaderConnectionUpdate.id} with status: $behovStatus")
    }

    private suspend fun findHovedenhetOrgnummer(personIdent: String, orgNumber: String): String {
        val arbeidsforholdMap = aaregService.findOrgNumbersByPersonIdent(personIdent)
        return arbeidsforholdMap[orgNumber]
            ?: throw HovedenhetNotFoundException("Could not find main entity for employee on sick leave and orgnumber in aareg")
    }

    suspend fun createNewNlBehov(nlBehov: EmployeeLeaderConnectionWrite) {
        if (!persistLeesahNlBehov) {
            logger.info("Skipping persistence of EmployeeLeaderConnectionRequirement as configured.")
            return
        }

        withContext(ioDispatcher) {
            val id = nlDb.insertNlBehov(
                NarmestelederBehovEntity(
                    sykmeldtFnr = nlBehov.employeeIdentificationNumber,
                    orgnummer = nlBehov.orgnumber,
                    hovedenhetOrgnummer = findHovedenhetOrgnummer(nlBehov.employeeIdentificationNumber, nlBehov.orgnumber),
                    narmestelederFnr = nlBehov.leaderIdentificationNumber,
                    leesahStatus = nlBehov.leesahStatus,
                    behovStatus = BehovStatus.RECEIVED
                )
            )
            logger.info("Inserted NarmestelederBehovEntity with id: $id")
        }
    }
}

fun NarmestelederBehovEntity.toEmployeeLeaderConnctionRead(name: Name): EmployeeLeaderConnectionRead = EmployeeLeaderConnectionRead(
    id = this.id ?: throw MissingIDException("NarmestelederBehovEntity entity id is null"),
    employeeIdentificationNumber = this.sykmeldtFnr,
    orgnumber = this.orgnummer,
    mainOrgnumber = this.hovedenhetOrgnummer,
    leaderIdentificationNumber = this.narmestelederFnr,
    name = name
)
