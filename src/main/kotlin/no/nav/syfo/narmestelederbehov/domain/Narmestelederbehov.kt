package no.nav.syfo.narmestelederbehov.domain

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import org.apache.commons.text.similarity.JaroWinklerSimilarity
import java.text.Normalizer
import java.util.UUID

@JvmInline
value class NarmestelederbehovId(val value: UUID)

data class Narmestelederbehov(
    val id: NarmestelederbehovId,
    val employee: Employee,
)

data class Employee(
    val personIdent: PersonIdent,
    val organizationNumber: OrganizationNumber,
)

data class ManagerContactInput(
    val personIdent: PersonIdent,
    val lastName: String,
    val email: String,
    val mobile: String,
)

data class NormalizedManagerContact(
    val personIdent: PersonIdent,
    val lastName: String,
    val email: EmailAddress,
    val mobile: PhoneNumber,
)

sealed interface ManagerContactNormalization {
    data class Valid(val manager: NormalizedManagerContact) : ManagerContactNormalization

    data class Invalid(val issues: List<ManagerContactValidationIssue>) : ManagerContactNormalization
}

data class ManagerContactValidationIssue(
    val field: ManagerContactField,
    val reason: ManagerContactValidationReason,
)

enum class ManagerContactField {
    MOBILE,
    EMAIL,
}

enum class ManagerContactValidationReason(val message: String) {
    PHONE_NUMBER_MUST_NOT_BE_BLANK("PhoneNumber must not be blank"),
    PHONE_NUMBER_MUST_CONTAIN_ONLY_DIGITS("PhoneNumber must contain only digits, with an optional leading plus sign"),
    EMAIL_ADDRESS_MUST_NOT_BE_BLANK("EmailAddress must not be blank"),
    EMAIL_ADDRESS_MUST_NOT_CONTAIN_EMPTY_ENTRIES("EmailAddress must not contain empty email entries"),
    EMAIL_ADDRESS_MUST_NOT_CONTAIN_WHITESPACE("EmailAddress must not contain whitespace"),
    EMAIL_ADDRESS_MUST_BE_VALID("EmailAddress must be a valid email address"),
}

data class PersonNameDetails(
    val firstName: String,
    val primaryLastName: String,
    val registeredNames: List<RegisteredName>,
)

data class RegisteredName(
    val lastName: String,
    val middleName: String? = null,
)

fun ManagerContactInput.normalize(): ManagerContactNormalization {
    val normalizedMobile = mobile.replace(" ", "")
    val normalizedEmail = email.split(";").joinToString(";") { it.trim() }
    val issues = listOfNotNull(
        normalizedMobile.validationIssueForMobile(),
        normalizedEmail.validationIssueForEmail(),
    )

    return if (issues.isEmpty()) {
        ManagerContactNormalization.Valid(
            NormalizedManagerContact(
                personIdent = personIdent,
                lastName = lastName,
                email = EmailAddress(normalizedEmail),
                mobile = PhoneNumber(normalizedMobile),
            ),
        )
    } else {
        ManagerContactNormalization.Invalid(issues)
    }
}

fun PersonNameDetails.matchesManagerLastName(lastName: String): Boolean {
    val normalizedName = lastName.normalizeName()
    val registeredLastNames = registeredNames.flatMap { registeredName ->
        listOf(registeredName.lastName) + listOfNotNull(
            registeredName.middleName
                ?.takeIf { it.isNotBlank() }
                ?.let { "$it ${registeredName.lastName}".normalizeName() },
        )
    }.map(String::normalizeName)

    return registeredLastNames.any { it == normalizedName } ||
        registeredLastNames.any {
            it.canonicalizeOrthographicVariants() == normalizedName.canonicalizeOrthographicVariants()
        } ||
        registeredLastNames.any { registeredLastName ->
            normalizedName.fuzzySimilarityTo(registeredLastName)?.let { it >= FUZZY_MATCH_THRESHOLD } == true
        }
}

@JvmInline
value class PhoneNumber private constructor(val value: String) {
    companion object {
        operator fun invoke(value: String): PhoneNumber {
            val normalizedValue = value.replace(" ", "")
            require(normalizedValue.isNotBlank() && PHONE_NUMBER_REGEX.matches(normalizedValue))
            return PhoneNumber(normalizedValue)
        }
    }
}

@JvmInline
value class EmailAddress private constructor(val value: String) {
    companion object {
        operator fun invoke(value: String): EmailAddress {
            val normalizedValue = value.split(";").joinToString(";") { it.trim() }
            require(normalizedValue.isNotBlank())
            require(normalizedValue.split(";").none(String::isBlank))
            require(normalizedValue.split(";").all(EMAIL_ADDRESS_REGEX::matches))
            return EmailAddress(normalizedValue)
        }
    }
}

private const val FUZZY_MATCH_THRESHOLD = 0.93
private const val MINIMUM_FUZZY_MATCH_LETTERS = 4
private val PHONE_NUMBER_REGEX = Regex("^\\+?\\d+$")
private val EMAIL_ADDRESS_REGEX = Regex(
    "^[A-Za-z0-9ÆØÅæøå._%+-]+@[A-Za-z0-9ÆØÅæøå](?:[A-Za-z0-9ÆØÅæøå-]{0,61}[A-Za-z0-9ÆØÅæøå])?(?:\\.[A-Za-z0-9ÆØÅæøå](?:[A-Za-z0-9ÆØÅæøå-]{0,61}[A-Za-z0-9ÆØÅæøå])?)+$",
)
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

private fun String.validationIssueForMobile(): ManagerContactValidationIssue? = when {
    isBlank() -> ManagerContactValidationIssue(
        ManagerContactField.MOBILE,
        ManagerContactValidationReason.PHONE_NUMBER_MUST_NOT_BE_BLANK,
    )

    !PHONE_NUMBER_REGEX.matches(this) -> ManagerContactValidationIssue(
        ManagerContactField.MOBILE,
        ManagerContactValidationReason.PHONE_NUMBER_MUST_CONTAIN_ONLY_DIGITS,
    )

    else -> null
}

private fun String.validationIssueForEmail(): ManagerContactValidationIssue? {
    val emailParts = split(";")
    val reason = when {
        isBlank() -> ManagerContactValidationReason.EMAIL_ADDRESS_MUST_NOT_BE_BLANK
        emailParts.any(String::isBlank) -> ManagerContactValidationReason.EMAIL_ADDRESS_MUST_NOT_CONTAIN_EMPTY_ENTRIES
        emailParts.any { it.any(Char::isWhitespace) } -> ManagerContactValidationReason.EMAIL_ADDRESS_MUST_NOT_CONTAIN_WHITESPACE
        emailParts.any { !EMAIL_ADDRESS_REGEX.matches(it) } -> ManagerContactValidationReason.EMAIL_ADDRESS_MUST_BE_VALID
        else -> return null
    }
    return ManagerContactValidationIssue(ManagerContactField.EMAIL, reason)
}

private const val APOSTROPHE_VARIANTS = "[\u2018\u2019\u201B\uFF07]"
private const val HYPHEN_VARIANTS = "[\u2010\u2011\u2012\u2013\u2014\u2015\u2212]"
