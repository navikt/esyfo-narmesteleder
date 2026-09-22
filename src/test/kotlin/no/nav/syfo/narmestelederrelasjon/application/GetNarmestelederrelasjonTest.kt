package no.nav.syfo.narmestelederrelasjon.application

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import no.nav.syfo.application.auth.UserPrincipal
import no.nav.syfo.narmestelederrelasjon.domain.Narmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.domain.RelationPerson
import no.nav.syfo.narmestelederrelasjon.domain.RelationPersonName
import java.util.UUID

class GetNarmestelederrelasjonTest :
    DescribeSpec({
        val repository = mockk<NarmestelederrelasjonRepository>()
        val organizationAccess = mockk<NarmestelederrelasjonOrganizationAccess>()
        val organization = mockk<NarmestelederrelasjonOrganization>()
        val query = GetNarmestelederrelasjon(repository, organizationAccess, organization)
        val id = UUID.randomUUID()
        val employeeIdent = "12345678901"
        val orgNumber = "123456789"

        fun relation() = Narmestelederrelasjon(
            id = id,
            orgNumber = orgNumber,
            employee = RelationPerson(employeeIdent, RelationPersonName("Employee", null, "Person")),
        )

        beforeTest {
            clearMocks(repository, organizationAccess, organization)
            coEvery { repository.findActiveById(any()) } returns relation()
            coEvery { organizationAccess.hasAccess(any(), orgNumber) } returns true
            coEvery { organization.findName(orgNumber) } returns "Organization"
        }

        it("returns the relation only to an organization-authorized caller") {
            val caller = UserPrincipal("11111111111", "token")
            coEvery { organizationAccess.hasAccess(caller, orgNumber) } returns true

            query.execute(id, caller) shouldBe GetNarmestelederrelasjonResult.Found(
                relation(),
                RelationPersonName("Employee", null, "Person"),
                "Organization",
            )
        }

        it("masks missing organization access") {
            val caller = UserPrincipal("11111111111", "token")
            coEvery { organizationAccess.hasAccess(caller, orgNumber) } returns false

            query.execute(id, caller) shouldBe GetNarmestelederrelasjonResult.NotFound
            coVerify(exactly = 0) { organization.findName(any()) }
        }

        it("masks incomplete required projections") {
            coEvery { repository.findActiveById(id) } returns relation().copy(
                employee = RelationPerson(employeeIdent, null),
            )

            query.execute(id, UserPrincipal("11111111111", "token")) shouldBe GetNarmestelederrelasjonResult.NotFound
        }

        it("does not perform authorization lookup when no active relation exists") {
            coEvery { repository.findActiveById(id) } returns null

            query.execute(id, UserPrincipal("11111111111", "token")) shouldBe GetNarmestelederrelasjonResult.NotFound

            coVerify(exactly = 0) { organizationAccess.hasAccess(any(), any()) }
        }
    })
