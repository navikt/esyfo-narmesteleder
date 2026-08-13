package no.nav.syfo.narmesteleder.domain

data class LinemanagerStatistics(
    val employeesOnSickLeaveWithoutLinemanager: Long,
    val employeesOnSickLeaveWithLinemanager: Long,
    val employeesNotOnSickLeaveWithLinemanager: Long,
)
