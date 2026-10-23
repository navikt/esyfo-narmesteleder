package no.nav.syfo.platform.database

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

/** Runs blocking Exposed JDBC work on [Dispatchers.IO] while preserving Exposed transaction behavior. */
internal suspend fun <T> Database.ioTransaction(
    transactionIsolation: Int? = null,
    block: suspend JdbcTransaction.() -> T,
): T = withContext(Dispatchers.IO) {
    suspendTransaction(
        db = this@ioTransaction,
        transactionIsolation = transactionIsolation,
        statement = block,
    )
}
