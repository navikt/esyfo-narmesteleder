package no.nav.syfo.aareg.client

import no.nav.syfo.aareg.client.FakeAaregClient.Companion.ORG_NUMBER_PREFIX
import java.util.concurrent.atomic.*

/**
 * @see ORG_NUMBER_PREFIX
 * */
class FakeAaregClient : IAaregClient {
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
    override suspend fun getArbeidsforhold(
        personIdent: String
    ): AaregArbeidsforholdOversikt {
        if (failureRef.get() != null) {
            throw failureRef.get()!!
        }
        val arbeidsforhold = arbeidsForholdForIdent[personIdent]

        val aaregArbeidsforholdOversikt =
            AaregArbeidsforholdOversikt(
                arbeidsforholdoversikter = arbeidsforhold?.map { arbeidsforhold ->
                    Arbeidsforholdoversikt(
                        opplysningspliktig = Opplysningspliktig(
                            type = OpplysningspliktigType.Hovedenhet,
                            identer = listOf(
                                Ident(
                                    type = IdentType.ORGANISASJONSNUMMER,
                                    gjeldende = true,
                                    ident = arbeidsforhold.second
                                )
                            ),
                        ),
                        arbeidssted = Arbeidssted(
                            type = ArbeidsstedType.Underenhet,
                            identer = listOf(
                                Ident(
                                    type = IdentType.ORGANISASJONSNUMMER,
                                    gjeldende = true,
                                    ident = arbeidsforhold.first
                                )
                            ),
                        )
                    )
                } ?: emptyList()
            )

        return aaregArbeidsforholdOversikt
    }

    companion object {
        val defaultArbeidsforhold = mapOf(
            "15436803416" to listOf("215649202" to "310667633", "972674818" to "963743254"),
            "13468329780" to listOf("315649196" to "310667633"),
            "01518721689" to listOf("215649202" to "310667633"),
        )

        const val ORG_NUMBER_PREFIX = "0192:"
    }
}
