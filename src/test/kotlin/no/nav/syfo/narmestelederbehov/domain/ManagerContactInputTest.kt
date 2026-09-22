package no.nav.syfo.narmestelederbehov.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmesteleder.domain.EmailAddress as LegacyEmailAddress
import no.nav.syfo.narmesteleder.domain.PhoneNumber as LegacyPhoneNumber

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
            "manager@example.test",
            " first@example.test ; second@example.test ",
            " leder+team@arbeids-plass.test ",
            "ærlig@blåbær.økonomi",
            " manager@example.test\n",
            "manager@${"a".repeat(63)}.test",
            "manager@${"a".repeat(64)}.test",
            "",
            " \t ",
            ";",
            ";manager@example.test",
            "manager@example.test;",
            "manager@example.test; ;second@example.test",
            "invalid;",
            "invalid;manager @example.test",
            "manager @example.test;invalid",
            "manager\t@example.test",
            "manager@example.test,second@example.test",
            "manager@example",
            "manager@-example.test",
            "manager@example-.test",
            "manager@exam_ple.test",
            "manager@example..test",
        ).forEachIndexed { index, email ->
            test("preserves legacy email normalization and validation for case ${index + 1}") {
                val expected = LegacyEmailAddress.parse(email)
                when (val actual = managerContact(email = email).normalize()) {
                    is ManagerContactNormalization.Valid -> actual.manager.email.value shouldBe expected.getOrThrow().value
                    is ManagerContactNormalization.Invalid -> {
                        actual.issues.single().field shouldBe ManagerContactField.EMAIL
                        actual.issues.single().reason.message shouldBe expected.exceptionOrNull()?.message
                    }
                }

                val expectedValue = runCatching { LegacyEmailAddress(email) }
                val actualValue = runCatching { EmailAddress(email) }
                actualValue.isSuccess shouldBe expectedValue.isSuccess
                actualValue.exceptionOrNull()?.message shouldBe expectedValue.exceptionOrNull()?.message
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
