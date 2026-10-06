package no.nav.syfo.narmestelederstatistikk.api

import no.nav.syfo.narmestelederstatistikk.application.Narmestelederstatistikk

data class LinemanagerStatisticsResponse(
    val employeesOnSickLeaveWithoutLinemanager: Long,
    val employeesOnSickLeaveWithLinemanager: Long,
    val employeesNotOnSickLeaveWithLinemanager: Long,
)

fun Narmestelederstatistikk.toResponse(): LinemanagerStatisticsResponse = LinemanagerStatisticsResponse(
    employeesOnSickLeaveWithoutLinemanager = employeesOnSickLeaveWithoutNarmesteleder,
    employeesOnSickLeaveWithLinemanager = employeesOnSickLeaveWithNarmesteleder,
    employeesNotOnSickLeaveWithLinemanager = employeesNotOnSickLeaveWithNarmesteleder,
)
