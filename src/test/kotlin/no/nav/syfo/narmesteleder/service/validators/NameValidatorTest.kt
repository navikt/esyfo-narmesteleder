package no.nav.syfo.narmesteleder.service.validators

import faker
import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import linemanager
import linemanagerRevoke
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.narmesteleder.domain.PersonalIdentificationNumber
import no.nav.syfo.pdl.Person
import no.nav.syfo.pdl.client.Navn

class NameValidatorTest :
    DescribeSpec({
        fun person(lastName: String, fnr: String): Person = Person(
            name = Navn(
                fornavn = faker.name().firstName(),
                mellomnavn = null,
                etternavn = lastName,
            ),
            nationalIdentificationNumber = PersonalIdentificationNumber(fnr),
        )

        describe("validateLinemanagerLastName") {
            it("should throw BadRequestException if lastname of PdlPerson and manager does not match") {
                val linemanager = linemanager()
                val manager = person(
                    lastName = linemanager.manager.lastName.reversed(),
                    fnr = linemanager.manager.nationalIdentificationNumber.value,
                )

                val exception = shouldThrow<ApiErrorException.BadRequestException> {
                    NameValidator.validateLinemanagerLastName(manager, linemanager)
                }

                exception.message shouldBe "Last name for linemanager does not correspond with registered value for the given national identification number"
                exception.type shouldBe ErrorType.LINEMANAGER_NAME_NATIONAL_IDENTIFICATION_NUMBER_MISMATCH
            }

            it("should not throw BadRequestException if lastname of PdlPerson and manager matches case insensitively") {
                val linemanager = linemanager()
                val manager = person(
                    lastName = linemanager.manager.lastName.lowercase(),
                    fnr = linemanager.manager.nationalIdentificationNumber.value,
                )

                shouldNotThrow<ApiErrorException.BadRequestException> {
                    NameValidator.validateLinemanagerLastName(manager, linemanager)
                }
            }
        }

        describe("validateEmployeeLastName with Linemanager") {
            it("should not throw when employee last name matches case insensitively") {
                val linemanager = linemanager()
                val employee = person(
                    lastName = linemanager.employeeLastName.lowercase(),
                    fnr = linemanager.employeeIdentificationNumber.value,
                )

                shouldNotThrow<ApiErrorException.BadRequestException> {
                    NameValidator.validateEmployeeLastName(employee, linemanager)
                }
            }

            it("should not throw when employee last name matches exactly") {
                val linemanager = linemanager()
                val employee = person(
                    lastName = linemanager.employeeLastName,
                    fnr = linemanager.employeeIdentificationNumber.value,
                )

                shouldNotThrow<ApiErrorException.BadRequestException> {
                    NameValidator.validateEmployeeLastName(employee, linemanager)
                }
            }

            it("should throw BadRequestException when employee last name does not match") {
                val linemanager = linemanager()
                val employee = person(
                    lastName = linemanager.employeeLastName.reversed(),
                    fnr = linemanager.employeeIdentificationNumber.value,
                )

                val exception = shouldThrow<ApiErrorException.BadRequestException> {
                    NameValidator.validateEmployeeLastName(employee, linemanager)
                }

                exception.message shouldBe "Last name for employee on sick leave does not correspond with registered value for the given national identification number"
                exception.type shouldBe ErrorType.EMPLOYEE_NAME_NATIONAL_IDENTIFICATION_NUMBER_MISMATCH
            }
        }

        describe("validateEmployeeLastName with LinemanagerRevoke") {
            it("should not throw when employee last name matches case insensitively") {
                val linemanagerRevoke = linemanagerRevoke()
                val employee = person(
                    lastName = linemanagerRevoke.lastName.lowercase(),
                    fnr = linemanagerRevoke.employeeIdentificationNumber.value,
                )

                shouldNotThrow<ApiErrorException.BadRequestException> {
                    NameValidator.validateEmployeeLastName(employee, linemanagerRevoke)
                }
            }

            it("should throw BadRequestException when employee last name does not match") {
                val linemanagerRevoke = linemanagerRevoke()
                val employee = person(
                    lastName = linemanagerRevoke.lastName.reversed(),
                    fnr = linemanagerRevoke.employeeIdentificationNumber.value,
                )

                val exception = shouldThrow<ApiErrorException.BadRequestException> {
                    NameValidator.validateEmployeeLastName(employee, linemanagerRevoke)
                }

                exception.message shouldBe "Last name for employee on sick leave does not correspond with registered value for the given national identification number"
                exception.type shouldBe ErrorType.EMPLOYEE_NAME_NATIONAL_IDENTIFICATION_NUMBER_MISMATCH
            }
        }
    })
