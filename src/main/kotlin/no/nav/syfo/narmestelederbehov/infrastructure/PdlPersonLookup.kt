package no.nav.syfo.narmestelederbehov.infrastructure

import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederbehov.application.PersonDetails
import no.nav.syfo.narmestelederbehov.application.PersonLookup
import no.nav.syfo.narmestelederbehov.domain.PersonNameDetails
import no.nav.syfo.narmestelederbehov.domain.RegisteredName
import no.nav.syfo.pdl.PdlService

class PdlPersonLookup(private val service: PdlService) : PersonLookup {
    override suspend fun find(personIdent: PersonIdent): PersonDetails? {
        val person = try {
            service.getPersonOrThrowApiError(personIdent.value)
        } catch (e: ApiErrorException.BadRequestException) {
            if (e.errorMessage == "Could not find person in PDL") return null
            throw e
        }
        return PersonDetails(
            PersonIdent(person.nationalIdentificationNumber.value),
            PersonNameDetails(
                firstName = person.name.fornavn,
                middleName = person.name.mellomnavn,
                lastName = person.name.etternavn,
                registeredNames = person.names.map { RegisteredName(it.etternavn, it.mellomnavn) },
            ),
        )
    }
}
