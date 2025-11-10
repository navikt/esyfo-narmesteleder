package no.nav.syfo.narmesteleder.kafka.model

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import linemanager
import linemanagerRevoke
import no.nav.syfo.application.auth.SystemPrincipal
import no.nav.syfo.application.auth.UserPrincipal

class NlResponseSourceTest : DescribeSpec({

    describe("getSourceFrom principal and Linemanager") {
        it("Returns LPS when principal is OrganizationPrincipal") {
            // Arrange
            val linemanager = linemanager()
            val principal = SystemPrincipal("orgnummer", "token", "owner", "userId")

            // Act
            val source = NlResponseSource.getSourceFrom(principal, linemanager)

            // Assert
            source shouldBe NlResponseSource.LPS
        }

        it("Returns ARBEIDSTAGER when principal is UserPrincipal with employee ident") {
            // Arrange

            val linemanager = linemanager()
            val principal = UserPrincipal(linemanager.employeeIdentificationNumber, "token")

            // Act
            val source = NlResponseSource.getSourceFrom(principal, linemanager)

            // Assert
            source shouldBe NlResponseSource.ARBEIDSTAGER
        }
        it("Returns LEDER when principal is UserPrincipal with linemanager ident") {
            // Arrange

            val linemanager = linemanager()
            val principal = UserPrincipal(linemanager.manager.nationalIdentificationNumber, "token")

            // Act
            val source = NlResponseSource.getSourceFrom(principal, linemanager)

            // Assert
            source shouldBe NlResponseSource.NARMESTELEDER
        }
        it("Returns PERSONALLEDER when principal is UserPrincipal with other ident") {
            // Arrange

            val linemanager = linemanager()
            val principal = UserPrincipal("12345678901", "token")

            // Act
            val source = NlResponseSource.getSourceFrom(principal, linemanager)

            // Assert
            source shouldBe NlResponseSource.PERSONALLEDER
        }


    }
    describe("getSourceFrom principal and LinemanagerRevoke") {
        it("Returns LPS when principal is OrganizationPrincipal") {
            // Arrange
            val linemanager = linemanagerRevoke()
            val principal = SystemPrincipal("orgnummer", "token", "owner", "userId")

            // Act
            val source = NlResponseSource.getSourceFrom(principal, linemanager)

            // Assert
            source shouldBe NlResponseSource.LPS
        }

        it("Returns ARBEIDSTAGER when principal is UserPrincipal with employee ident") {
            // Arrange

            val linemanager = linemanagerRevoke()
            val principal = UserPrincipal(linemanager.employeeIdentificationNumber, "token")

            // Act
            val source = NlResponseSource.getSourceFrom(principal, linemanager)

            // Assert
            source shouldBe NlResponseSource.ARBEIDSTAGER
        }
        it("Returns PERSONALLEDER when principal is UserPrincipal with other ident") {
            // Arrange

            val linemanager = linemanagerRevoke()
            val principal = UserPrincipal("12345678901", "token")

            // Act
            val source = NlResponseSource.getSourceFrom(principal, linemanager)

            // Assert
            source shouldBe NlResponseSource.PERSONALLEDER
        }
    }
})
