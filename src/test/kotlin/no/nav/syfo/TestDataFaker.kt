package no.nav.syfo

import java.time.LocalDate
import kotlin.random.Random

/** Seeded generator for synthetic test data, so a given seed always gives the same values. */
class TestDataFaker(seed: Long) {
    private val random = Random(seed)

    fun fnr(): String = randomDigits(11)

    fun orgnummer(): String = randomDigits(9)

    fun mobilnummer(): String = "4${randomDigits(7)}"

    fun email(): String = "test+${randomDigits(5)}@example.com"

    fun firstName(): String = FIRST_NAMES.random(random)

    fun middleName(): String? = MIDDLE_NAMES.random(random)

    fun lastName(): String = LAST_NAMES.random(random)

    fun birthday(): LocalDate = LocalDate.of(1960, 1, 1).plusDays(random.nextLong(0, 365L * 45))

    private fun randomDigits(length: Int): String = (1..length).joinToString("") { random.nextInt(10).toString() }

    private companion object {
        val FIRST_NAMES = listOf("Kari", "Ola", "Ingrid", "Per", "Sigrid", "Lars", "Astrid", "Knut", "Liv", "Jon")
        val MIDDLE_NAMES = listOf(null, null, "Marie", "Andre", "Elise", "Johan")
        val LAST_NAMES = listOf("Nordmann", "Hansen", "Johansen", "Olsen", "Larsen", "Berg", "Haugen", "Dahl")
    }
}

val faker = TestDataFaker(seed = 760)
