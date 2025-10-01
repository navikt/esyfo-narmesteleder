package no.nav.syfo.aareg.client

import java.util.*
import java.util.concurrent.atomic.*
import net.datafaker.Faker
import no.nav.syfo.aareg.client.FakeAaregClient.Companion.ORG_NUMBER_PREFIX

/**
 * @see ORG_NUMBER_PREFIX
 * */
class FakeAaregClient() : IAaregClient {
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
        val personIdentSeed = Random(personIdent.toLong())
        val faker = Faker(personIdentSeed)
        val arbeidsforhold = arbeidsForholdForIdent[personIdent]

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
                                    ident = arbeidsforhold?.first ?: faker.regexify("${ORG_NUMBER_PREFIX}[0-9]{9}")
                                )
                            )
                        ),
                        arbeidssted = Arbeidssted(
                            type = ArbeidsstedType.Underenhet,
                            identer = listOf(
                                Ident(
                                    type = IdentType.ORGANISASJONSNUMMER,
                                    gjeldende = true,
                                    ident = arbeidsforhold?.second?: faker.regexify("${ORG_NUMBER_PREFIX}[0-9]{9}")
                                )
                            )
                        )
                    )
                )
            )

        return aaregArbeidsforholdOversikt
    }

    companion object {
        val defaultArbeidsforhold = mapOf(
            "15436803416" to Pair("310667633", "215649202"),
            "13468329780" to Pair("310667633", "215649202"),
            "01518721689" to Pair("310667633", "315649196")
        )

        const
        val ORG_NUMBER_PREFIX = "0192:"
    }
}
