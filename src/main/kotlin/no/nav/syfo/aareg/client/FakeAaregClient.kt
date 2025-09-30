package no.nav.syfo.aareg.client

import java.util.*
import java.util.concurrent.atomic.*
import net.datafaker.Faker
import no.nav.syfo.aareg.client.FakeAaregClient.Companion.ORG_NUMBER_PREFIX

/**
 * @param juridiskOrgnummer org.number for `hovedenhet`. If none specified, a random number
 *  with the pattern `ORG_NUMBER_PREFIX[0-9]{9}` will be generated.
 *
 * @param arbeidsstedOrgnummer org.number for `underenhet`. If none specified, a random number
 *  with the pattern `ORG_NUMBER_PREFIX[0-9]{9}` will be generated.
 *
 * @see ORG_NUMBER_PREFIX
 * */
class FakeAaregClient(
    private val juridiskOrgnummer: String? = null,
    private val arbeidsstedOrgnummer: String? = null,
) : IAaregClient {
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
        val personIdentSeed = Random(personIdent.toLong())
        val faker = Faker(personIdentSeed)

        val aaregArbeidsforholdOversikt =
            AaregArbeidsforholdOversikt(
                arbeidsforholdoversikter = listOf(
                    Arbeidsforholdoversikt(
                        opplysningspliktig = Opplysningspliktig(
                            type = OpplysningspliktigType.Hovedenhet,
                            identer = listOf(
                                Ident(
                                    type = IdentType.ORGANISASJONSNUMMER,
                                    gjeldende = true,
                                    ident = juridiskOrgnummer ?: faker.regexify("${ORG_NUMBER_PREFIX}[0-9]{9}")
                                )
                            )
                        ),
                        arbeidssted = Arbeidssted(
                            type = ArbeidsstedType.Underenhet,
                            identer = listOf(
                                Ident(
                                    type = IdentType.ORGANISASJONSNUMMER,
                                    gjeldende = true,
                                    ident = arbeidsstedOrgnummer ?: faker.regexify("${ORG_NUMBER_PREFIX}[0-9]{9}")
                                )
                            )
                        )
                    )
                )
            )

        return aaregArbeidsforholdOversikt
    }

    companion object {
        const val ORG_NUMBER_PREFIX = "0192:"
    }
}
