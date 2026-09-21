package no.nav.syfo.narmestelederbehov.domain

import org.apache.commons.text.similarity.JaroWinklerSimilarity
import java.text.Normalizer

fun PersonNameDetails.matchManagerLastName(lastName: String): ManagerLastNameMatch {
    val hasParallelNames = registeredNames.size > 1
    val normalizedName = lastName.normalizeName()
    val registeredLastNames = registeredNames.flatMap { registeredName ->
        listOf(registeredName.lastName) + listOfNotNull(
            registeredName.middleName
                ?.takeIf { it.isNotBlank() }
                ?.let { "$it ${registeredName.lastName}" },
        )
    }.map(String::normalizeName)

    if (registeredLastNames.any { it == normalizedName }) {
        return ManagerLastNameMatch.Exact(hasParallelNames)
    }

    val orthographicName = normalizedName.canonicalizeOrthographicVariants()
    if (registeredLastNames.any { it.canonicalizeOrthographicVariants() == orthographicName }) {
        return ManagerLastNameMatch.OrthographicVariant(hasParallelNames)
    }

    val bestFuzzyScore = registeredLastNames.mapNotNull { normalizedName.fuzzySimilarityTo(it) }.maxOrNull()
    return bestFuzzyScore
        ?.takeIf { it >= FUZZY_MATCH_THRESHOLD }
        ?.let { ManagerLastNameMatch.Fuzzy(it, hasParallelNames) }
        ?: ManagerLastNameMatch.NoMatch(bestFuzzyScore, hasParallelNames)
}

private const val FUZZY_MATCH_THRESHOLD = 0.93
private const val MINIMUM_FUZZY_MATCH_LETTERS = 4
private val jaroWinklerSimilarity = JaroWinklerSimilarity()

private fun String.normalizeName(): String = Normalizer.normalize(this, Normalizer.Form.NFC)
    .trim()
    .replace("\\s+".toRegex(), " ")
    .replace(APOSTROPHE_VARIANTS.toRegex(), "'")
    .replace(HYPHEN_VARIANTS.toRegex(), "-")
    .uppercase()

private fun String.canonicalizeOrthographicVariants(): String = replace("AA", "Å")
    .replace('Ö', 'Ø')
    .replace('Ä', 'Æ')
    .replace('É', 'E')

private fun String.fuzzySimilarityTo(other: String): Double? = if (
    isFuzzyEligible() && other.isFuzzyEligible()
) {
    jaroWinklerSimilarity.apply(this, other)
} else {
    null
}

private fun String.isFuzzyEligible(): Boolean = letterCount() >= MINIMUM_FUZZY_MATCH_LETTERS &&
    all { it.isLetter() || it.isWhitespace() || it == '\'' || it == '-' }

private fun String.letterCount(): Int = codePoints().filter(Character::isLetter).count().toInt()

private const val APOSTROPHE_VARIANTS = "[\u2018\u2019\u201B\uFF07]"
private const val HYPHEN_VARIANTS = "[\u2010\u2011\u2012\u2013\u2014\u2015\u2212]"
