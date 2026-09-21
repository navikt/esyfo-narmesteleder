package no.nav.syfo.narmestelederbehov.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.ident.PersonIdent

class ManagerContactInputTest :
    FunSpec({
        test("normalizes whitespace in phone number and semicolon-separated email addresses") {
            managerContact(email = " first@example.test ; second@example.test ", mobile = "+47 99 99 99 99")
                .normalize() shouldBe ManagerContactNormalization.Valid(
                NormalizedManagerContact(
                    personIdent = managerIdent,
                    lastName = "Hansen",
                    email = EmailAddress("first@example.test;second@example.test"),
                    mobile = PhoneNumber("+4799999999"),
                ),
            )
        }

        test("reports all invalid contact fields and reasons without contact values") {
            managerContact(email = "invalid", mobile = "+47-99999999").normalize() shouldBe
                ManagerContactNormalization.Invalid(
                    listOf(
                        ManagerContactValidationIssue(
                            ManagerContactField.MOBILE,
                            ManagerContactValidationReason.PHONE_NUMBER_MUST_CONTAIN_ONLY_DIGITS,
                        ),
                        ManagerContactValidationIssue(
                            ManagerContactField.EMAIL,
                            ManagerContactValidationReason.EMAIL_ADDRESS_MUST_BE_VALID,
                        ),
                    ),
                )
            managerContact(email = "first@example.test; ;second@example.test").normalize() shouldBe
                ManagerContactNormalization.Invalid(
                    listOf(
                        ManagerContactValidationIssue(
                            ManagerContactField.EMAIL,
                            ManagerContactValidationReason.EMAIL_ADDRESS_MUST_NOT_CONTAIN_EMPTY_ENTRIES,
                        ),
                    ),
                )
        }

        test("value types validate already-normalized contact values") {
            shouldThrow<IllegalArgumentException> { EmailAddress(" person@example.test") }
            shouldThrow<IllegalArgumentException> { PhoneNumber("+47 99 99 99 99") }
        }

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
    })

private val managerIdent = PersonIdent("10987654321")

private fun managerContact(
    email: String = "manager@example.test",
    mobile: String = "+4799999999",
) = ManagerContactInput(managerIdent, "Hansen", email, mobile)

private fun personWithNames(vararg names: RegisteredName) = PersonNameDetails(
    firstName = "Manager",
    primaryLastName = names.first().lastName,
    registeredNames = names.toList(),
)
