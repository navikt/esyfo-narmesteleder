package no.nav.syfo.narmestelederrelasjon.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PersonNameMatchTest :
    FunSpec({
        test("classifies exact matches, including middle names and parallel registered names") {
            personWithNames(RegisteredName("Hansen")).matchLastName("hansen") shouldBe
                LastNameMatch.Exact(hasParallelNames = false)
            personWithNames(RegisteredName("Hansen", middleName = "Berg")).matchLastName("Berg Hansen") shouldBe
                LastNameMatch.Exact(hasParallelNames = false)
            personWithNames(RegisteredName("Hansen"), RegisteredName("Johansen")).matchLastName("Johansen") shouldBe
                LastNameMatch.Exact(hasParallelNames = true)
        }

        test("classifies orthographic variants") {
            personWithNames(RegisteredName("Aasen")).matchLastName("Åsen") shouldBe
                LastNameMatch.OrthographicVariant(hasParallelNames = false)
        }

        test("classifies fuzzy matches with score above the threshold") {
            val match = personWithNames(RegisteredName("Andersen")).matchLastName("Anderssen")
            val fuzzyMatch = match as LastNameMatch.Fuzzy

            match shouldBe fuzzyMatch
            (fuzzyMatch.score >= 0.93) shouldBe true
            fuzzyMatch.hasParallelNames shouldBe false
        }

        test("accepts the inclusive fuzzy threshold and rejects scores below it") {
            val accepted = personWithNames(RegisteredName("ABCDEFGHIJKLMNOPQRST"))
                .matchLastName("ABCZZZGHIJKLMNOPQRST") as LastNameMatch.Fuzzy
            accepted.score shouldBe 0.93

            val rejected = personWithNames(RegisteredName("ABCDEFGHIJKLMNOPQRST"))
                .matchLastName("ABZZZFGHIJKLMNOPQRST") as LastNameMatch.NoMatch
            (requireNotNull(rejected.bestFuzzyScore) < 0.93) shouldBe true
        }

        test("retains no-match fuzzy score and rejects scores below the threshold") {
            val fuzzyNoMatch = personWithNames(RegisteredName("Hansen")).matchLastName("Olsen")
                as LastNameMatch.NoMatch

            (requireNotNull(fuzzyNoMatch.bestFuzzyScore) < 0.93) shouldBe true
            fuzzyNoMatch.hasParallelNames shouldBe false
            personWithNames(RegisteredName("Li")).matchLastName("Lu") shouldBe
                LastNameMatch.NoMatch(bestFuzzyScore = null, hasParallelNames = false)
        }

        test("prefers exact matches in any registered name over orthographic and fuzzy matches") {
            personWithNames(RegisteredName("Hanson"), RegisteredName("Hansen")).matchLastName("Hansen") shouldBe
                LastNameMatch.Exact(hasParallelNames = true)
            personWithNames(RegisteredName("Ström"), RegisteredName("Strøm")).matchLastName("Strøm") shouldBe
                LastNameMatch.Exact(hasParallelNames = true)
        }

        test("prefers orthographic matches in any registered name over fuzzy matches") {
            personWithNames(RegisteredName("Andree"), RegisteredName("André")).matchLastName("Andre") shouldBe
                LastNameMatch.OrthographicVariant(hasParallelNames = true)
        }

        test("preserves the best accepted and rejected fuzzy scores across parallel names") {
            val accepted = personWithNames(RegisteredName("Hanson")).matchLastName("Hansen") as LastNameMatch.Fuzzy
            personWithNames(RegisteredName("Haugland"), RegisteredName("Hanson")).matchLastName("Hansen") shouldBe
                accepted.copy(hasParallelNames = true)

            val rejected = personWithNames(RegisteredName("Haugland")).matchLastName("Hansen") as LastNameMatch.NoMatch
            personWithNames(RegisteredName("Li"), RegisteredName("Haugland")).matchLastName("Hansen") shouldBe
                rejected.copy(hasParallelNames = true)
        }

        test("does not treat middle name candidates as parallel registered names") {
            personWithNames(RegisteredName("Hansen", middleName = "Berg")).matchLastName("Berg Hansen") shouldBe
                LastNameMatch.Exact(hasParallelNames = false)
            personWithNames(RegisteredName("Hansen", middleName = " \t ")).matchLastName("Hansen") shouldBe
                LastNameMatch.Exact(hasParallelNames = false)
        }

        test("matches combined middle and last names in non-primary parallel names") {
            personWithNames(RegisteredName("Olsen"), RegisteredName("Hansen", middleName = "Berg"))
                .matchLastName("Berg Hansen") shouldBe LastNameMatch.Exact(hasParallelNames = true)
        }

        test("preserves no-match telemetry when registered names are missing") {
            PersonNameDetails(firstName = "Manager", lastName = "Hansen", registeredNames = emptyList())
                .matchLastName("Hansen") shouldBe LastNameMatch.NoMatch(
                bestFuzzyScore = null,
                hasParallelNames = false,
            )
        }

        test("normalizes Unicode, whitespace, apostrophes, and hyphens for exact matches") {
            listOf(
                "  A\u030Astr\u00F6m\u2011O\u2019Connor  " to "Åström-O'Connor",
                "van\t\nHansen" to "Van Hansen",
                "Hansen" to "hansen",
                "Hansen1" to "Hansen1",
            ).forEach { (submitted, registered) ->
                personWithNames(RegisteredName(registered)).matchLastName(submitted) shouldBe
                    LastNameMatch.Exact(hasParallelNames = false)
            }
        }

        test("matches only approved orthographic variants, in both directions") {
            listOf(
                "Åsen" to "Aasen",
                "Aasen" to "Åsen",
                "Strøm" to "Ström",
                "Ström" to "Strøm",
                "Sæther" to "Säther",
                "Säther" to "Sæther",
                "Sæter" to "Säter",
                "Säter" to "Sæter",
                "Fåberg" to "Faaberg",
                "Faaberg" to "Fåberg",
                "André" to "Andre",
                "Andre" to "André",
            ).forEach { (submitted, registered) ->
                personWithNames(RegisteredName(registered)).matchLastName(submitted) shouldBe
                    LastNameMatch.OrthographicVariant(hasParallelNames = false)
            }
        }

        test("does not broaden orthographic variants to plain letters or digraphs") {
            listOf(
                "Osterud" to "Østerud",
                "Ost" to "Øst",
                "Ost" to "Öst",
                "Aer" to "Ær",
                "Aer" to "Är",
                "Ar" to "Ær",
                "Ar" to "Är",
                "Ase" to "Åse",
            ).forEach { (submitted, registered) ->
                val match = personWithNames(RegisteredName(registered)).matchLastName(submitted)
                (match is LastNameMatch.NoMatch) shouldBe true
                match.isAccepted() shouldBe false
            }
        }

        test("rejects unknown characters and names shorter than four Unicode letters for fuzzy matching") {
            listOf(
                "O*Connor" to "OConnor",
                "Hansen1" to "Hansen",
                "Aas" to "Aar",
                "Li" to "Lu",
            ).forEach { (submitted, registered) ->
                val match = personWithNames(RegisteredName(registered)).matchLastName(submitted)
                match shouldBe LastNameMatch.NoMatch(bestFuzzyScore = null, hasParallelNames = false)
            }
            (personWithNames(RegisteredName("Hanson")).matchLastName("Hansen") is LastNameMatch.Fuzzy) shouldBe true
        }

        test("accepts fuzzy matches and rejects clearly different names") {
            listOf(
                "Hansen" to "Hanson",
                "Anderssen" to "Andersen",
            ).forEach { (submitted, registered) ->
                val match = personWithNames(RegisteredName(registered)).matchLastName(submitted)
                (match is LastNameMatch.Fuzzy) shouldBe true
                match.isAccepted() shouldBe true
            }
            listOf(
                "Hansen" to "Haugland",
                "Hansen" to "Olsen",
                "Andréssen" to "Andersen",
            ).forEach { (submitted, registered) ->
                val match = personWithNames(RegisteredName(registered)).matchLastName(submitted)
                (match is LastNameMatch.NoMatch) shouldBe true
                match.isAccepted() shouldBe false
            }
        }
    })

private fun personWithNames(vararg names: RegisteredName) = PersonNameDetails(
    firstName = "Manager",
    lastName = names.first().lastName,
    registeredNames = names.toList(),
)
