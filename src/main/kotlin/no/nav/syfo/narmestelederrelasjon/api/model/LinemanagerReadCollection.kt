package no.nav.syfo.narmestelederrelasjon.api.model

import no.nav.syfo.narmestelederrelasjon.application.SearchActiveNarmestelederrelasjonerResult
import no.nav.syfo.narmestelederrelasjon.application.SearchName
import java.time.Instant
import java.util.UUID

data class LinemanagerReadCollection(
    val linemanagers: List<LinemanagerRead>,
    val meta: LinemanagerSearchPageInfo,
)

data class LinemanagerSearchPageInfo(
    val size: Int,
    val pageSize: Int,
    val hasMore: Boolean,
    val nextPageToken: String?,
)

data class LinemanagerRead(
    val id: UUID,
    val orgNumber: String,
    val activeFrom: Instant,
    val employee: LinemanagerPersonRead,
    val manager: LinemanagerManagerRead,
)

data class LinemanagerPersonRead(val nationalIdentificationNumber: String, val name: Name?)

data class LinemanagerManagerRead(
    val nationalIdentificationNumber: String,
    val name: Name?,
    val email: String,
    val mobile: String,
)

data class Name(val firstName: String, val lastName: String, val middleName: String?)

fun SearchActiveNarmestelederrelasjonerResult.Success.toResponse() = LinemanagerReadCollection(
    linemanagers = linemanagers.map {
        LinemanagerRead(
            id = it.id,
            orgNumber = it.orgNumber.value,
            activeFrom = it.activeFrom,
            employee = LinemanagerPersonRead(it.employee.nationalIdentificationNumber.value, it.employee.name.toResponse()),
            manager = LinemanagerManagerRead(
                it.manager.nationalIdentificationNumber.value,
                it.manager.name.toResponse(),
                it.manager.email,
                it.manager.mobile,
            ),
        )
    },
    meta = LinemanagerSearchPageInfo(linemanagers.size, pageSize, hasMore, nextPageToken),
)

private fun SearchName?.toResponse(): Name? = this?.let { Name(it.firstName, it.lastName, it.middleName) }
