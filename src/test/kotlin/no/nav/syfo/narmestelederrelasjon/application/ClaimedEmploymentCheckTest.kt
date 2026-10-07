package no.nav.syfo.narmestelederrelasjon.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import java.time.Instant
import java.util.UUID

class ClaimedEmploymentCheckTest :
    FunSpec({
        test("toString includes technical claim details but omits employee and organization") {
            val claim = ClaimedEmploymentCheck(
                narmesteLederId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
                organizationNumber = OrganizationNumber("123456789"),
                employeeIdent = PersonIdent("12345678901"),
                claimToken = UUID.fromString("00000000-0000-0000-0000-000000000002"),
                claimedAt = Instant.parse("2026-06-15T12:00:00Z"),
            )

            val rendered = claim.toString()
            rendered shouldNotContain claim.employeeIdent.value
            rendered shouldNotContain claim.organizationNumber.value
            rendered shouldBe "ClaimedEmploymentCheck(" +
                "narmesteLederId=${claim.narmesteLederId}, claimToken=${claim.claimToken}, claimedAt=${claim.claimedAt})"
        }
    })
