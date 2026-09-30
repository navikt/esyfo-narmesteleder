package no.nav.syfo.platform.database

import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.java.UUIDColumnType
import org.jetbrains.exposed.v1.javatime.JavaOffsetDateTimeColumnType
import java.time.OffsetDateTime
import java.util.UUID

// Database-side defaults written exactly as in the Flyway migrations, so Exposed mappings match the schema.
val PostgresNow = CustomFunction<OffsetDateTime>("now", JavaOffsetDateTimeColumnType())
val PostgresUuidV7 = CustomFunction<UUID>("uuidv7", UUIDColumnType())
val PostgresGenRandomUuid = CustomFunction<UUID>("gen_random_uuid", UUIDColumnType())
