package no.nav.syfo.application.exception

import kotlinx.coroutines.CancellationException

/**
 * Hovedsaklig tenkt for `runCatchingCancellable`, men kan også brukes for å eksplisitt kaste en bestemt type unntak
 * uten å måtte håndtere det i en onFailure-blokk.
 *
 * eks:
 * ```
 * runCatchingOrThrow<Person, ClientException> {
 *   fetchPerson()
 * }
 * ```
 * */
inline fun <T, reified E> runCatchingOrThrow(block: () -> T): Result<T> = runCatching { block() }.onFailure {
    if (it is E) throw it
}

/**
 * Kaster alltid CancellationException for å ikke svelge disse ved et uhell dersom man trenger en catch-all
 *
 * eks:
 * ```
 *  runCatchingCancellable {
 *      doSomething()
 *  }
 *  ```
 * */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = runCatchingOrThrow<T, CancellationException> { block() }
