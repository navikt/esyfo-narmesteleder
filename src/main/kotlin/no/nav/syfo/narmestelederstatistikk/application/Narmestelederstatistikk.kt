package no.nav.syfo.narmestelederstatistikk.application

data class Narmestelederstatistikk(
    val employeesOnSickLeaveWithoutNarmesteleder: Long,
    val employeesOnSickLeaveWithNarmesteleder: Long,
    val employeesNotOnSickLeaveWithNarmesteleder: Long,
)
