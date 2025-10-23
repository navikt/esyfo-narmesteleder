package no.nav.syfo.dinesykmeldte.client

import java.util.concurrent.atomic.AtomicReference

/**
 * @see ORG_NUMBER_PREFIX
 * */
class FakeDinesykmeldteClient() : IDinesykmeldteClient {
    private val failureRef = AtomicReference<Throwable?>(null)
    private val FNR_WITH_ACTIVE_SYKMELDING ="12345678901"

    /**
     * getArbeidsforhold will return a failure if this property is set.
     *
     * @param failure a `Throwable` to wrap in `Result.failure`
     * */
    fun setFailure(failure: Throwable) {
        failureRef.set(failure)
    }

    fun clearFailure() = failureRef.set(null)

    /**
     * @param personIdent is used as a seed for `Faker`. Must be parsable to an integer.
     * @Returns a stub with `Faker` data, or a failure if the `failure` property is set
     * */
    override suspend fun getIsActiveSykmelding(fnr: String, orgnummer: String): Boolean {
        return fnr == FNR_WITH_ACTIVE_SYKMELDING
    }
}
