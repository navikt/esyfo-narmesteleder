package no.nav.syfo.narmestelederbehov.infrastructure

import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.sql.Connection
import java.util.UUID

/** Call after a relevant behov change, using the writer's existing transaction and row lock. */
internal fun JdbcTransaction.scheduleDialogDispatch(behovId: UUID) {
    (connection.connection as Connection).prepareStatement(
        """
        INSERT INTO nl_dialog_dispatch (behov_id, available_at)
        SELECT b.id, GREATEST(clock_timestamp(), b.created + interval '10 seconds')
        FROM nl_behov b
        WHERE b.id = ?
          AND (b.behov_status = 'BEHOV_CREATED'
               OR b.dialog_id IS NOT NULL
               OR EXISTS (SELECT 1 FROM nl_dialog_dispatch WHERE behov_id = b.id))
        ON CONFLICT (behov_id) DO UPDATE SET
            revision = nl_dialog_dispatch.revision + 1,
            status = CASE WHEN nl_dialog_dispatch.status = 'CLAIMED' THEN 'CLAIMED' ELSE 'READY' END,
            available_at = CASE WHEN nl_dialog_dispatch.status = 'CLAIMED'
                                THEN nl_dialog_dispatch.available_at ELSE clock_timestamp() END,
            pending_since = COALESCE(nl_dialog_dispatch.pending_since, clock_timestamp()),
            attempts = 0,
            last_failure = NULL
        """.trimIndent(),
    ).use {
        it.setObject(1, behovId)
        it.executeUpdate()
    }
}
