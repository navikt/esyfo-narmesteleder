package no.nav.syfo.narmestelederrelasjon.api.model

import no.nav.syfo.narmestelederrelasjon.application.ActiveNarmesteleder
import java.util.UUID

data class LinemanagerLookupResponse(
    val lineManager: LinemanagerResponse?,
)

data class LinemanagerResponse(
    val id: UUID,
    val nationalIdentificationNumber: String,
    val emailAddresses: List<String>,
)

fun ActiveNarmesteleder.toResponse() = LinemanagerResponse(
    id = id,
    nationalIdentificationNumber = managerIdent.value,
    emailAddresses = emailAddresses.map { it.value },
)
