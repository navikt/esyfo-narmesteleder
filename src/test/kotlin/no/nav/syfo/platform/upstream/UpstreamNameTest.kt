package no.nav.syfo.platform.upstream

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class UpstreamNameTest :
    FunSpec({
        test("accepts lowercase log names") {
            UpstreamName("altinn-tilganger_v2").value shouldBe "altinn-tilganger_v2"
        }

        test("prints only the name") {
            UpstreamName("aareg").toString() shouldBe "aareg"
        }

        listOf("", "Aareg", "1aareg", "aa reg", "aareg.no").forEach { invalid ->
            test("rejects '$invalid'") {
                shouldThrow<IllegalArgumentException> { UpstreamName(invalid) }
            }
        }
    })
