package no.nav.syfo.narmestelederrelasjon.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmesteleder.domain.PhoneNumber as LegacyPhoneNumber

class ManagerContactInputTest :
    FunSpec({
        test("normalizes whitespace in phone number and semicolon-separated email addresses") {
            val result = managerContact(email = " first@example.test ; second@example.test ", mobile = "+47 99 99 99 99")
                .normalize() as ManagerContactNormalization.Valid
            result.manager.personIdent shouldBe managerIdent
            result.manager.lastName shouldBe "Hansen"
            result.manager.email shouldBe EmailAddress("first@example.test;second@example.test")
            result.manager.mobile shouldBe PhoneNumber("+4799999999")
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

        test("reports the first invalid email entry before errors in later entries") {
            listOf("invalid;", "invalid;person @example.test", "invalid; ;valid@example.test").forEach { email ->
                managerContact(email = email).normalize() shouldBe ManagerContactNormalization.Invalid(
                    listOf(
                        ManagerContactValidationIssue(
                            ManagerContactField.EMAIL,
                            ManagerContactValidationReason.EMAIL_ADDRESS_MUST_BE_VALID,
                        ),
                    ),
                )
            }
        }

        listOf(
            "manager@example.test" to "manager@example.test",
            " first@example.test ; second@example.test " to "first@example.test;second@example.test",
            " leder+team@arbeids-plass.test " to "leder+team@arbeids-plass.test",
            "ærlig@blåbær.no" to "ærlig@blåbær.no",
            " manager@example.test\n" to "manager@example.test",
            "manager@${"a".repeat(63)}.test" to "manager@${"a".repeat(63)}.test",
        ).forEachIndexed { index, (email, expected) ->
            test("normalizes valid email input for case ${index + 1}") {
                val actual = managerContact(email = email).normalize() as ManagerContactNormalization.Valid
                actual.manager.email shouldBe EmailAddress(expected)
            }
        }

        listOf(
            "" to ManagerContactValidationReason.EMAIL_ADDRESS_MUST_NOT_BE_BLANK,
            " \t " to ManagerContactValidationReason.EMAIL_ADDRESS_MUST_NOT_BE_BLANK,
            ";" to ManagerContactValidationReason.EMAIL_ADDRESS_MUST_NOT_CONTAIN_EMPTY_ENTRIES,
            ";manager@example.test" to ManagerContactValidationReason.EMAIL_ADDRESS_MUST_NOT_CONTAIN_EMPTY_ENTRIES,
            "manager@example.test;" to ManagerContactValidationReason.EMAIL_ADDRESS_MUST_NOT_CONTAIN_EMPTY_ENTRIES,
            "manager@example.test; ;second@example.test" to ManagerContactValidationReason.EMAIL_ADDRESS_MUST_NOT_CONTAIN_EMPTY_ENTRIES,
            "manager @example.test;invalid" to ManagerContactValidationReason.EMAIL_ADDRESS_MUST_NOT_CONTAIN_WHITESPACE,
            "manager\t@example.test" to ManagerContactValidationReason.EMAIL_ADDRESS_MUST_NOT_CONTAIN_WHITESPACE,
            "manager@example.test,second@example.test" to ManagerContactValidationReason.EMAIL_ADDRESS_MUST_BE_VALID,
            "manager@${"a".repeat(64)}.test" to ManagerContactValidationReason.EMAIL_ADDRESS_MUST_BE_VALID,
            "manager@example" to ManagerContactValidationReason.EMAIL_ADDRESS_MUST_BE_VALID,
            "ola..nordmann@example.test" to ManagerContactValidationReason.EMAIL_ADDRESS_MUST_BE_VALID,
            ".ola@example.test" to ManagerContactValidationReason.EMAIL_ADDRESS_MUST_BE_VALID,
            "o'neill@example.test" to ManagerContactValidationReason.EMAIL_ADDRESS_MUST_BE_VALID,
            "björn@example.test" to ManagerContactValidationReason.EMAIL_ADDRESS_MUST_BE_VALID,
            "ærlig@blåbær.økonomi" to ManagerContactValidationReason.EMAIL_ADDRESS_MUST_BE_VALID,
        ).forEachIndexed { index, (email, reason) ->
            test("rejects invalid email input for case ${index + 1}") {
                managerContact(email = email).normalize() shouldBe ManagerContactNormalization.Invalid(
                    listOf(ManagerContactValidationIssue(ManagerContactField.EMAIL, reason)),
                )
                shouldThrow<IllegalArgumentException> { EmailAddress(email) }.message shouldBe reason.message
            }
        }

        listOf(
            "+47 99 99 99 99",
            " 99 99 99 99 ",
            "99999999",
            "+1",
            "",
            "   ",
            "\t",
            "+",
            "++4799999999",
            "+47-99999999",
            "+47\t99999999",
            "\n99999999",
            "99999999\n",
            "+47\u00a099999999",
            "(+47)99999999",
            "٩٩٩٩٩٩٩٩",
            "999A9999",
        ).forEachIndexed { index, mobile ->
            test("preserves legacy phone normalization and validation for case ${index + 1}") {
                val expected = LegacyPhoneNumber.parse(mobile)
                when (val actual = managerContact(mobile = mobile).normalize()) {
                    is ManagerContactNormalization.Valid -> actual.manager.mobile.value shouldBe expected.getOrThrow().value
                    is ManagerContactNormalization.Invalid -> {
                        actual.issues.single().field shouldBe ManagerContactField.MOBILE
                        actual.issues.single().reason.message shouldBe expected.exceptionOrNull()?.message
                    }
                }

                val expectedValue = runCatching { LegacyPhoneNumber(mobile) }
                val actualValue = runCatching { PhoneNumber(mobile) }
                actualValue.isSuccess shouldBe expectedValue.isSuccess
                actualValue.exceptionOrNull()?.message shouldBe expectedValue.exceptionOrNull()?.message
            }
        }
    })

private val managerIdent = PersonIdent("10987654321")

private fun managerContact(
    email: String = "manager@example.test",
    mobile: String = "+4799999999",
) = ManagerContactInput(managerIdent, "Hansen", email, mobile)
