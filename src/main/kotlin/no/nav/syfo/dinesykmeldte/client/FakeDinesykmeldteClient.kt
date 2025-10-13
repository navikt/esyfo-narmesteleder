package no.nav.syfo.dinesykmeldte.client

import java.util.concurrent.atomic.AtomicReference

/**
 * @see ORG_NUMBER_PREFIX
 * */
class FakeDinesykmeldteClient() : IDinesykmeldteClient {
    val arbeidsForholdForIdent = defaultArbeidsforhold.toMutableMap()
    private val failureRef = AtomicReference<Throwable?>(null)

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
        return true
        TODO("Not yet implemented")
    }

    companion object {
        val defaultArbeidsforhold = mapOf(
            "15436803416" to listOf("310667633" to "215649202", "972674818" to "963743254"),
            "13468329780" to listOf("310667633" to "215649202"),
            "01518721689" to listOf("310667633" to "315649196")
        )

        const
        val ORG_NUMBER_PREFIX = "0192:"
    }
}
