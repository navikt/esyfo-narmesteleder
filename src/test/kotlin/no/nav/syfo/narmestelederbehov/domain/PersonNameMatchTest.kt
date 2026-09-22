package no.nav.syfo.narmestelederbehov.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.narmesteleder.service.validators.NameMatchType
import no.nav.syfo.narmesteleder.service.validators.NameValidator

class PersonNameMatchTest :
    FunSpec({
        test("classifies exact matches, including middle names and parallel registered names") {
            personWithNames(RegisteredName("Hansen")).matchManagerLastName("hansen") shouldBe
                ManagerLastNameMatch.Exact(hasParallelNames = false)
            personWithNames(RegisteredName("Hansen", middleName = "Berg")).matchManagerLastName("Berg Hansen") shouldBe
                ManagerLastNameMatch.Exact(hasParallelNames = false)
            personWithNames(RegisteredName("Hansen"), RegisteredName("Johansen")).matchManagerLastName("Johansen") shouldBe
                ManagerLastNameMatch.Exact(hasParallelNames = true)
        }

        test("classifies orthographic variants") {
            personWithNames(RegisteredName("Aasen")).matchManagerLastName("Åsen") shouldBe
                ManagerLastNameMatch.OrthographicVariant(hasParallelNames = false)
        }

        test("classifies fuzzy matches with score above the threshold") {
            val match = personWithNames(RegisteredName("Andersen")).matchManagerLastName("Anderssen")
            val fuzzyMatch = match as ManagerLastNameMatch.Fuzzy

            match shouldBe fuzzyMatch
            (fuzzyMatch.score >= 0.93) shouldBe true
            fuzzyMatch.hasParallelNames shouldBe false
        }

        test("retains no-match fuzzy score and rejects scores below the threshold") {
            val fuzzyNoMatch = personWithNames(RegisteredName("Hansen")).matchManagerLastName("Olsen")
                as ManagerLastNameMatch.NoMatch

            (requireNotNull(fuzzyNoMatch.bestFuzzyScore) < 0.93) shouldBe true
            fuzzyNoMatch.hasParallelNames shouldBe false
            personWithNames(RegisteredName("Li")).matchManagerLastName("Lu") shouldBe
                ManagerLastNameMatch.NoMatch(bestFuzzyScore = null, hasParallelNames = false)
        }

        test("prefers exact matches in any registered name over orthographic and fuzzy matches") {
            personWithNames(RegisteredName("Hanson"), RegisteredName("Hansen")).matchManagerLastName("Hansen") shouldBe
                ManagerLastNameMatch.Exact(hasParallelNames = true)
            personWithNames(RegisteredName("Ström"), RegisteredName("Strøm")).matchManagerLastName("Strøm") shouldBe
                ManagerLastNameMatch.Exact(hasParallelNames = true)
        }

        test("prefers orthographic matches in any registered name over fuzzy matches") {
            personWithNames(RegisteredName("Andree"), RegisteredName("André")).matchManagerLastName("Andre") shouldBe
                ManagerLastNameMatch.OrthographicVariant(hasParallelNames = true)
        }

        test("preserves the best accepted and rejected fuzzy scores across parallel names") {
            val accepted = personWithNames(RegisteredName("Hanson")).matchManagerLastName("Hansen") as ManagerLastNameMatch.Fuzzy
            personWithNames(RegisteredName("Haugland"), RegisteredName("Hanson")).matchManagerLastName("Hansen") shouldBe
                accepted.copy(hasParallelNames = true)

            val rejected = personWithNames(RegisteredName("Haugland")).matchManagerLastName("Hansen") as ManagerLastNameMatch.NoMatch
            personWithNames(RegisteredName("Li"), RegisteredName("Haugland")).matchManagerLastName("Hansen") shouldBe
                rejected.copy(hasParallelNames = true)
        }

        test("does not treat middle name candidates as parallel registered names") {
            personWithNames(RegisteredName("Hansen", middleName = "Berg")).matchManagerLastName("Berg Hansen") shouldBe
                ManagerLastNameMatch.Exact(hasParallelNames = false)
            personWithNames(RegisteredName("Hansen", middleName = " \t ")).matchManagerLastName("Hansen") shouldBe
                ManagerLastNameMatch.Exact(hasParallelNames = false)
        }

        test("matches combined middle and last names in non-primary parallel names") {
            personWithNames(RegisteredName("Olsen"), RegisteredName("Hansen", middleName = "Berg"))
                .matchManagerLastName("Berg Hansen") shouldBe ManagerLastNameMatch.Exact(hasParallelNames = true)
        }

        test("preserves no-match telemetry when registered names are missing") {
            PersonNameDetails(firstName = "Manager", primaryLastName = "Hansen", registeredNames = emptyList())
                .matchManagerLastName("Hansen") shouldBe ManagerLastNameMatch.NoMatch(
                bestFuzzyScore = null,
                hasParallelNames = false,
            )
        }

        listOf(
            "  A\u030Astr\u00F6m\u2011O\u2019Connor  " to "Åström-O'Connor",
            "van\t\nHansen" to "Van Hansen",
            "Hansen" to "hansen",
            "Åsen" to "Aasen",
            "Aasen" to "Åsen",
            "Strøm" to "Ström",
            "Ström" to "Strøm",
            "Sæter" to "Säter",
            "Säter" to "Sæter",
            "André" to "Andre",
            "Andre" to "André",
            "Osterud" to "Østerud",
            "Ost" to "Øst",
            "Ost" to "Öst",
            "Aer" to "Ær",
            "Aer" to "Är",
            "Ar" to "Ær",
            "Ar" to "Är",
            "Ase" to "Åse",
            "O*Connor" to "OConnor",
            "Hansen1" to "Hansen",
            "Hansen1" to "Hansen1",
            "Aas" to "Aar",
            "Li" to "Lu",
            "Hansen" to "Hanson",
            "Anderssen" to "Andersen",
            "Hansen" to "Haugland",
            "Hansen" to "Olsen",
            "Andréssen" to "Andersen",
        ).forEachIndexed { index, (submitted, registered) ->
            test("preserves legacy name classification and acceptance for case ${index + 1}") {
                val legacyMatch = NameValidator.determineMatchType(submitted, listOf(registered), "domain-parity")
                val match = personWithNames(RegisteredName(registered)).matchManagerLastName(submitted)

                match.legacyMatchType() shouldBe legacyMatch
                match.isAccepted() shouldBe legacyMatch.isAccepted
                match.hasParallelNames shouldBe false
            }
        }
    })

private fun personWithNames(vararg names: RegisteredName) = PersonNameDetails(
    firstName = "Manager",
    primaryLastName = names.first().lastName,
    registeredNames = names.toList(),
)

private fun ManagerLastNameMatch.legacyMatchType(): NameMatchType = when (this) {
    is ManagerLastNameMatch.Exact -> NameMatchType.EXACT
    is ManagerLastNameMatch.OrthographicVariant -> NameMatchType.ORTHOGRAPHIC_VARIANT
    is ManagerLastNameMatch.Fuzzy -> NameMatchType.FUZZY
    is ManagerLastNameMatch.NoMatch -> NameMatchType.NONE
}
