package no.nav.syfo.narmesteleder.kafka.model

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import linemanager
import linemanagerRevoke
import no.nav.syfo.application.auth.SystemPrincipal
import no.nav.syfo.application.auth.UserPrincipal
import no.nav.syfo.narmesteleder.domain.RevokeInitiator

class NlResponseSourceTest :
    DescribeSpec({

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

            it("Returns PERSONALLEDER when principal is UserPrincipal") {
                // Arrange

                val linemanager = linemanager()
                val principal = UserPrincipal(linemanager.employeeIdentificationNumber.value, "token")

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
                source shouldBe NlResponseSource.LPS_REVOKE
            }

            it("Returns ARBEIDSTAGER when principal is UserPrincipal with employee ident") {
                // Arrange

                val linemanager = linemanagerRevoke()
                val principal = UserPrincipal(linemanager.employeeIdentificationNumber.value, "token")

                // Act
                val source = NlResponseSource.getSourceFrom(principal, linemanager)

                // Assert
                source shouldBe NlResponseSource.ARBEIDSTAGER_REVOKE
            }
            it("Returns PERSONALLEDER when principal is UserPrincipal with other ident") {
                // Arrange

                val linemanager = linemanagerRevoke()
                val principal = UserPrincipal("12345678901", "token")

                // Act
                val source = NlResponseSource.getSourceFrom(principal, linemanager)

                // Assert
                source shouldBe NlResponseSource.PERSONALLEDER_REVOKE
            }
        }
        describe("getRevokeSourceFrom RevokeInitiator") {
            it("Returns ARBEIDSTAGER_REVOKE when the employee revoked the relation") {
                NlResponseSource.getRevokeSourceFrom(RevokeInitiator.EMPLOYEE) shouldBe
                    NlResponseSource.ARBEIDSTAGER_REVOKE
            }

            it("Returns NARMESTELEDER_REVOKE when the line manager revoked the relation") {
                NlResponseSource.getRevokeSourceFrom(RevokeInitiator.LINEMANAGER) shouldBe
                    NlResponseSource.NARMESTELEDER_REVOKE
            }

            it("Returns PERSONALLEDER_REVOKE when a personnel manager revoked the relation") {
                NlResponseSource.getRevokeSourceFrom(RevokeInitiator.PERSONNEL_MANAGER) shouldBe
                    NlResponseSource.PERSONALLEDER_REVOKE
            }

            it("Returns LPS_REVOKE when an LPS revoked the relation") {
                NlResponseSource.getRevokeSourceFrom(RevokeInitiator.LPS) shouldBe NlResponseSource.LPS_REVOKE
            }
        }
    })
