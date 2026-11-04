package no.nav.syfo.narmestelederbehov.infrastructure

import no.nav.syfo.narmestelederbehov.application.DialogScanFlow
import no.nav.syfo.narmestelederbehov.application.DialogScanPosition
import no.nav.syfo.narmestelederbehov.application.DialogScanProgress
import no.nav.syfo.narmestelederbehov.application.DialogScanProgressRepository
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.platform.database.ioTransaction
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.ZoneOffset

/** Durable scan position and cycle high-water mark per Dialogporten scan flow (V32). */
private object DialogScanProgressTable : Table("nl_dialog_scan_progress") {
    val flowKey = text("flow_key")
    val lastCreated = timestampWithTimeZone("last_created")
    val lastId = javaUUID("last_id")
    val highWaterMark = timestampWithTimeZone("high_water_mark")
    val updated = timestampWithTimeZone("updated")
    override val primaryKey = PrimaryKey(flowKey, name = "nl_dialog_scan_progress_pkey")
}

class ExposedDialogScanProgressRepository(
    private val database: Database,
) : DialogScanProgressRepository {
    override suspend fun find(flow: DialogScanFlow): DialogScanProgress? = database.ioTransaction {
        DialogScanProgressTable.select(
            DialogScanProgressTable.lastCreated,
            DialogScanProgressTable.lastId,
            DialogScanProgressTable.highWaterMark,
        ).where { DialogScanProgressTable.flowKey eq flow.name }
            .singleOrNull()
            ?.let { row ->
                DialogScanProgress(
                    position = DialogScanPosition(
                        created = row[DialogScanProgressTable.lastCreated].toInstant(),
                        id = NarmestelederbehovId(row[DialogScanProgressTable.lastId]),
                    ),
                    highWaterMark = row[DialogScanProgressTable.highWaterMark].toInstant(),
                )
            }
    }

    override suspend fun save(flow: DialogScanFlow, progress: DialogScanProgress) {
        database.ioTransaction {
            DialogScanProgressTable.upsert(
                DialogScanProgressTable.flowKey,
                onUpdate = { update ->
                    update[DialogScanProgressTable.lastCreated] = insertValue(DialogScanProgressTable.lastCreated)
                    update[DialogScanProgressTable.lastId] = insertValue(DialogScanProgressTable.lastId)
                    update[DialogScanProgressTable.highWaterMark] = insertValue(DialogScanProgressTable.highWaterMark)
                    update[DialogScanProgressTable.updated] = CurrentTimestampWithTimeZone
                },
            ) {
                it[DialogScanProgressTable.flowKey] = flow.name
                it[DialogScanProgressTable.lastCreated] = progress.position.created.atOffset(ZoneOffset.UTC)
                it[DialogScanProgressTable.lastId] = progress.position.id.value
                it[DialogScanProgressTable.highWaterMark] = progress.highWaterMark.atOffset(ZoneOffset.UTC)
            }
        }
    }

    override suspend fun reset(flow: DialogScanFlow) {
        database.ioTransaction {
            DialogScanProgressTable.deleteWhere { flowKey eq flow.name }
        }
    }
}
