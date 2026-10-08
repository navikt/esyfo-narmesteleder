package no.nav.syfo.narmestelederrelasjon.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.ident.OrganizationNumber
import java.time.LocalDate

class EmploymentEndedRuleTest :
    FunSpec({
        val organizationNumber = OrganizationNumber("910000001")
        val today = LocalDate.of(2026, 7, 31)

        test("revokes when there are no employments") {
            EmploymentEndedRule.evaluate(organizationNumber, emptyList(), today) shouldBe EmploymentEndedDecision.REVOKE
        }

        test("keeps employment ended exactly on the inclusive four month cutoff") {
            val employments = listOf(Employment(organizationNumber, LocalDate.of(2026, 3, 31)))
            EmploymentEndedRule.evaluate(organizationNumber, employments, today) shouldBe EmploymentEndedDecision.KEEP
        }

        test("revokes when employment is only in another organization") {
            val employments = listOf(Employment(OrganizationNumber("910000002"), null))
            EmploymentEndedRule.evaluate(organizationNumber, employments, today) shouldBe EmploymentEndedDecision.REVOKE
        }

        test("revokes for non-organization workplaces") {
            EmploymentEndedRule.evaluate(organizationNumber, listOf(Employment(null, null)), today) shouldBe EmploymentEndedDecision.REVOKE
        }

        test("keeps open-ended employment in the relation's organization") {
            EmploymentEndedRule.evaluate(organizationNumber, listOf(Employment(organizationNumber, null)), today) shouldBe EmploymentEndedDecision.KEEP
        }

        test("revokes employment ended one day before the cutoff") {
            val employments = listOf(Employment(organizationNumber, LocalDate.of(2026, 3, 30)))
            EmploymentEndedRule.evaluate(organizationNumber, employments, today) shouldBe EmploymentEndedDecision.REVOKE
        }

        test("keeps future employment without restricting its start date") {
            listOf(null, LocalDate.of(2027, 12, 31)).forEach { endDate ->
                val employments = listOf(Employment(organizationNumber, endDate, startDate = today.plusMonths(1)))
                EmploymentEndedRule.evaluate(organizationNumber, employments, today) shouldBe EmploymentEndedDecision.KEEP
            }
        }

        test("keeps when one of several employments qualifies regardless of order") {
            val employments = listOf(
                Employment(OrganizationNumber("910000002"), null),
                Employment(organizationNumber, LocalDate.of(2026, 3, 30)),
                Employment(organizationNumber, LocalDate.of(2026, 3, 31)),
                Employment(null, null),
            )
            listOf(employments, employments.reversed()).forEach {
                EmploymentEndedRule.evaluate(organizationNumber, it, today) shouldBe EmploymentEndedDecision.KEEP
            }
        }

        test("uses calendar months and clamps the June cutoff to February 28") {
            val juneToday = LocalDate.of(2026, 6, 30)
            EmploymentEndedRule.evaluate(
                organizationNumber,
                listOf(Employment(organizationNumber, LocalDate.of(2026, 2, 28))),
                juneToday,
            ) shouldBe EmploymentEndedDecision.KEEP
            EmploymentEndedRule.evaluate(
                organizationNumber,
                listOf(Employment(organizationNumber, LocalDate.of(2026, 2, 27))),
                juneToday,
            ) shouldBe EmploymentEndedDecision.REVOKE
        }
    })
