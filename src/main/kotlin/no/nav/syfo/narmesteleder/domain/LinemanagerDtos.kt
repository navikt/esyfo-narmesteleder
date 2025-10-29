package no.nav.syfo.narmesteleder.domain

import java.util.UUID

data class EmployeeLeaderConnectionWrite(
    val employeeIdentificationNumber: String,
    val orgnumber: String,
    val leaderIdentificationNumber: String,
    val leesahStatus: String,
)

data class LinemanagerUpdate(
    val id: UUID,
    val employeeIdentificationNumber: String,
    val orgnumber: String,
    val leaderIdentificationNumber: String,
)

data class LinemanaberRead(
    val id: UUID,
    val employeeIdentificationNumber: String,
    val orgnumber: String,
    val mainOrgnumber: String,
    val leaderIdentificationNumber: String,
    val name: Name
)

data class Name(
    val firstName: String,
    val lastName: String,
    val middleName: String?,
)
