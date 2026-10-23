package no.nav.syfo.narmestelederrelasjon.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

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
            fuzzyMatch.score shouldBeGreaterThanOrEqual 0.93
            fuzzyMatch.hasParallelNames shouldBe false
        }

        test("accepts the inclusive fuzzy threshold and rejects scores below it") {
            val accepted = personWithNames(RegisteredName("ABCDEFGHIJKLMNOPQRST"))
                .matchLastName("ABCZZZGHIJKLMNOPQRST") as LastNameMatch.Fuzzy
            accepted.score shouldBe 0.93

            val rejected = personWithNames(RegisteredName("ABCDEFGHIJKLMNOPQRST"))
                .matchLastName("ABZZZFGHIJKLMNOPQRST") as LastNameMatch.NoMatch
            rejected.bestFuzzyScore.shouldNotBeNull() shouldBeLessThan 0.93
        }

        test("retains no-match fuzzy score and rejects scores below the threshold") {
            val fuzzyNoMatch = personWithNames(RegisteredName("Hansen")).matchLastName("Olsen")
                as LastNameMatch.NoMatch

            fuzzyNoMatch.bestFuzzyScore.shouldNotBeNull() shouldBeLessThan 0.93
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

        listOf(
            Triple("Unicode, apostrophes, hyphens and surrounding whitespace", "  A\u030Astr\u00F6m\u2011O\u2019Connor  ", "Åström-O'Connor"),
            Triple("inner whitespace", "van\t\nHansen", "Van Hansen"),
            Triple("letter case", "Hansen", "hansen"),
            Triple("digits", "Hansen1", "Hansen1"),
        ).forEach { (case, submitted, registered) ->
            test("normalizes $case for exact matches") {
                personWithNames(RegisteredName(registered)).matchLastName(submitted) shouldBe
                    LastNameMatch.Exact(hasParallelNames = false)
            }
        }

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
            test("matches the approved orthographic variant $submitted of $registered") {
                personWithNames(RegisteredName(registered)).matchLastName(submitted) shouldBe
                    LastNameMatch.OrthographicVariant(hasParallelNames = false)
            }
        }

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
            test("does not broaden orthographic variants to match $submitted with $registered") {
                val match = personWithNames(RegisteredName(registered)).matchLastName(submitted)
                match.shouldBeInstanceOf<LastNameMatch.NoMatch>()
                match.isAccepted() shouldBe false
            }
        }

        listOf(
            "O*Connor" to "OConnor",
            "Hansen1" to "Hansen",
            "Aas" to "Aar",
            "Li" to "Lu",
        ).forEach { (submitted, registered) ->
            test("rejects $submitted against $registered for fuzzy matching because of unknown characters or fewer than four Unicode letters") {
                val match = personWithNames(RegisteredName(registered)).matchLastName(submitted)
                match shouldBe LastNameMatch.NoMatch(bestFuzzyScore = null, hasParallelNames = false)
            }
        }

        test("fuzzy-matches names of known characters with at least four Unicode letters") {
            personWithNames(RegisteredName("Hanson")).matchLastName("Hansen").shouldBeInstanceOf<LastNameMatch.Fuzzy>()
        }

        listOf(
            "Hansen" to "Hanson",
            "Anderssen" to "Andersen",
        ).forEach { (submitted, registered) ->
            test("accepts $submitted as a fuzzy match of $registered") {
                val match = personWithNames(RegisteredName(registered)).matchLastName(submitted)
                match.shouldBeInstanceOf<LastNameMatch.Fuzzy>()
                match.isAccepted() shouldBe true
            }
        }

        listOf(
            "Hansen" to "Haugland",
            "Hansen" to "Olsen",
            "Andréssen" to "Andersen",
        ).forEach { (submitted, registered) ->
            test("rejects $submitted as clearly different from $registered") {
                val match = personWithNames(RegisteredName(registered)).matchLastName(submitted)
                match.shouldBeInstanceOf<LastNameMatch.NoMatch>()
                match.isAccepted() shouldBe false
            }
        }
    })

private fun personWithNames(vararg names: RegisteredName) = PersonNameDetails(
    firstName = "Manager",
    lastName = names.first().lastName,
    registeredNames = names.toList(),
)
