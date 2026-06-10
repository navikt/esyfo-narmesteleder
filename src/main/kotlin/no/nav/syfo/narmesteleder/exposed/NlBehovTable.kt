package no.nav.syfo.narmesteleder.exposed

import no.nav.syfo.narmesteleder.domain.BehovStatus
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.postgresql.util.PGobject

object NlBehovTable : Table("nl_behov") {
    val id = javaUUID("id")
    val behovStatus = customEnumeration(
        name = "behov_status",
        sql = null,
        fromDb = { value ->
            val enumValue = when (value) {
                is PGobject -> value.value
                else -> value.toString()
            } ?: error("Missing enum value for nl_behov.behov_status")
            BehovStatus.valueOf(enumValue)
        },
        toDb = { value ->
            PGobject().apply {
                type = "BEHOV_STATUS"
                this.value = value.name
            }
        },
    )
}
