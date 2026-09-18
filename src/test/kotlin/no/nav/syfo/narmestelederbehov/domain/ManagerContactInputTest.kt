package no.nav.syfo.narmestelederbehov.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.ident.PersonIdent

class ManagerContactInputTest : FunSpec({
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

    test("accepts exact, middle name and parallel registered last names") {
        personWithNames(RegisteredName("Hansen")).matchesManagerLastName("hansen") shouldBe true
        personWithNames(RegisteredName("Hansen", middleName = "Berg")).matchesManagerLastName("Berg Hansen") shouldBe true
        personWithNames(RegisteredName("Hansen"), RegisteredName("Johansen")).matchesManagerLastName("Johansen") shouldBe true
    }

    test("accepts orthographic variants and sufficiently similar last names") {
        personWithNames(RegisteredName("Aasen")).matchesManagerLastName("Åsen") shouldBe true
        personWithNames(RegisteredName("Andersen")).matchesManagerLastName("Anderssen") shouldBe true
    }

    test("rejects unrelated and too-short fuzzy last names") {
        personWithNames(RegisteredName("Hansen")).matchesManagerLastName("Olsen") shouldBe false
        personWithNames(RegisteredName("Li")).matchesManagerLastName("Lu") shouldBe false
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
