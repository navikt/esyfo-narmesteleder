package no.nav.syfo.platform.database

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import kotlin.coroutines.CoroutineContext

/** Runs blocking Exposed JDBC work on [context] while preserving Exposed transaction behavior. */
internal suspend fun <T> Database.ioTransaction(
    context: CoroutineContext = Dispatchers.IO,
    block: suspend JdbcTransaction.() -> T,
): T = withContext(context) {
    suspendTransaction(
        db = this@ioTransaction,
        statement = block,
    )
}

internal suspend fun <T> Database.ioTransaction(
    context: CoroutineContext = Dispatchers.IO,
    transactionIsolation: Int,
    block: suspend JdbcTransaction.() -> T,
): T = withContext(context) {
    suspendTransaction(
        db = this@ioTransaction,
        transactionIsolation = transactionIsolation,
        statement = block,
    )
}
