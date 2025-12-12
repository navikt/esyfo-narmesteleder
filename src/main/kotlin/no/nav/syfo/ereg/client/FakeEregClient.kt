package no.nav.syfo.ereg.client

import java.util.concurrent.atomic.*

class FakeEregClient() : IEaregClient {
    val organisasjoner = defaultOrganisasjoner.toMutableMap()
    private val failureRef = AtomicReference<Throwable?>(null)

    fun setFailure(failure: Throwable) {
        failureRef.set(failure)
    }

    fun clearFailure() = failureRef.set(null)

    override suspend fun getOrganisasjon(
        orgnummer: String
    ): Organisasjon? {
        if (failureRef.get() != null) {
            throw failureRef.get()!!
        }
        return organisasjoner[orgnummer]?.let {
            return it
        }
    }

    companion object {
        val defaultOrganisasjoner = mapOf(
            "310667633" to Organisasjon(
                organisasjonsnummer = "310667633",
                driverVirksomheter = listOf(
                    Organisasjon(organisasjonsnummer = "215649202"),
                    Organisasjon(organisasjonsnummer = "315649196"),
                    Organisasjon(organisasjonsnummer = "315649218"),
                )
            ),
            "215649202" to Organisasjon(
                organisasjonsnummer = "215649202",
                inngaarIJuridiskEnheter = listOf(
                    Organisasjon(organisasjonsnummer = "310667633"),
                ),
            ),
            "315649196" to Organisasjon(
                organisasjonsnummer = "315649196",
                inngaarIJuridiskEnheter = listOf(
                    Organisasjon(organisasjonsnummer = "310667633"),
                ),
            ),
            "315649218" to Organisasjon(
                organisasjonsnummer = "315649218",
                inngaarIJuridiskEnheter = listOf(
                    Organisasjon(organisasjonsnummer = "310667633"),
                )
            )
        )
    }
}
