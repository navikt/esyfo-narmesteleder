package no.nav.syfo.integration.ereg

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.syfo.platform.upstream.UpstreamResult

class FakeEregClientTest :
    FunSpec({
        test("loads the local development fixtures") {
            val organisasjon = FakeEregClient().getOrganisasjon("310667633")
                .shouldBeInstanceOf<UpstreamResult.Success<Organisasjon?>>()
                .value
                .shouldNotBeNull()

            organisasjon.organisasjonsnummer shouldBe "310667633"
            organisasjon.driverVirksomheter.shouldNotBeNull() shouldHaveSize 3
        }
    })
