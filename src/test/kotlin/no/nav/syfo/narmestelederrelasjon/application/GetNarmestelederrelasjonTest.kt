package no.nav.syfo.narmestelederrelasjon.application

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederrelasjon.domain.Narmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.domain.RelationPerson
import no.nav.syfo.organisasjonstilgang.application.AccessToken
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccess
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject
import java.util.UUID

class GetNarmestelederrelasjonTest :
    DescribeSpec({
        val id = UUID.randomUUID()
        val employeeIdent = PersonIdent("12345678901")
        val orgNumber = OrganizationNumber("123456789")
        val caller = OrganizationAccessSubject.PersonnelManager(
            personIdent = PersonIdent("11111111111"),
            accessToken = AccessToken("token"),
        )

        fun lookup() = NarmestelederrelasjonLookup(
            id = id,
            organizationNumber = orgNumber,
            employeeIdent = employeeIdent,
            employeeFirstName = "Employee",
            employeeMiddleName = null,
            employeeLastName = "Person",
            isActive = true,
        )

        fun query(
            lookup: NarmestelederrelasjonLookup? = lookup(),
            access: OrganizationAccessResult = OrganizationAccessResult.Granted,
            activeSykmelding: Boolean = true,
            organizationName: String? = "Organization",
            effects: MutableList<String> = mutableListOf(),
        ) = GetNarmestelederrelasjon(
            FakeNarmestelederrelasjonRepository(lookup, effects),
            FakeGetOrganizationAccess(access, effects),
            FakeGetActiveSykmeldingLookup(activeSykmelding, effects),
            FakeGetOrganization(organizationName, effects),
        )

        it("returns the relation only to an organization-authorized caller") {
            val effects = mutableListOf<String>()
            query(effects = effects).execute(id, caller) shouldBe GetNarmestelederrelasjonResult.Found(
                Narmestelederrelasjon(
                    id = id,
                    orgNumber = orgNumber,
                    employee = RelationPerson(employeeIdent, "Employee", null, "Person"),
                ),
                "Organization",
            )
            effects shouldBe listOf("lookup", "access", "sykmelding", "organization")
        }

        it("masks missing organization access") {
            val effects = mutableListOf<String>()
            query(
                access = OrganizationAccessResult.Denied(DenialReason.MISSING_ORGANIZATION_ACCESS),
                effects = effects,
            ).execute(id, caller) shouldBe GetNarmestelederrelasjonResult.NotFound(
                GetNarmestelederrelasjonResult.NotFoundReason.ACCESS_DENIED,
                DenialReason.MISSING_ORGANIZATION_ACCESS,
            )
            effects shouldBe listOf("lookup", "access")
        }

        it("masks missing active sykmelding after authorization and before organization lookup") {
            val effects = mutableListOf<String>()
            query(activeSykmelding = false, effects = effects).execute(id, caller) shouldBe
                GetNarmestelederrelasjonResult.NotFound(GetNarmestelederrelasjonResult.NotFoundReason.NO_ACTIVE_SYKMELDING)
            effects shouldBe listOf("lookup", "access", "sykmelding")
        }

        it("masks an inactive relation before authorization lookup") {
            val effects = mutableListOf<String>()
            query(lookup = lookup().copy(isActive = false), effects = effects).execute(id, caller) shouldBe
                GetNarmestelederrelasjonResult.NotFound(GetNarmestelederrelasjonResult.NotFoundReason.RELATION_INACTIVE)
            effects shouldBe listOf("lookup")
        }

        listOf(
            "missing first name" to lookup().copy(employeeFirstName = null),
            "blank first name" to lookup().copy(employeeFirstName = " "),
            "missing last name" to lookup().copy(employeeLastName = null),
            "blank last name" to lookup().copy(employeeLastName = ""),
        ).forEach { (description, incompleteLookup) ->
            it("returns the relation with a nullable name for $description") {
                val effects = mutableListOf<String>()
                query(lookup = incompleteLookup, effects = effects).execute(id, caller) shouldBe
                    GetNarmestelederrelasjonResult.Found(
                        Narmestelederrelasjon(
                            id = id,
                            orgNumber = orgNumber,
                            employee = RelationPerson(
                                employeeIdent,
                                incompleteLookup.employeeFirstName?.takeIf(String::isNotBlank),
                                null,
                                incompleteLookup.employeeLastName?.takeIf(String::isNotBlank),
                            ),
                        ),
                        "Organization",
                    )
                effects shouldBe listOf("lookup", "access", "sykmelding", "organization")
            }
        }

        it("returns unavailable when the organization name is missing") {
            val effects = mutableListOf<String>()
            query(organizationName = null, effects = effects).execute(id, caller) shouldBe
                GetNarmestelederrelasjonResult.Unavailable
            effects shouldBe listOf("lookup", "access", "sykmelding", "organization")
        }

        it("does not perform authorization lookup when no relation exists") {
            val effects = mutableListOf<String>()
            query(lookup = null, effects = effects).execute(id, caller) shouldBe
                GetNarmestelederrelasjonResult.NotFound(GetNarmestelederrelasjonResult.NotFoundReason.RELATION_NOT_FOUND)
            effects shouldBe listOf("lookup")
        }
    })

private class FakeNarmestelederrelasjonRepository(
    private val lookup: NarmestelederrelasjonLookup?,
    private val effects: MutableList<String>,
) : NarmestelederrelasjonRepository {
    override suspend fun findById(id: UUID) = lookup.also { effects += "lookup" }
    override suspend fun findRevocableById(id: UUID): RevocableNarmestelederrelasjon? = error("Not used by GET")
}

private class FakeGetOrganizationAccess(
    private val result: OrganizationAccessResult,
    private val effects: MutableList<String>,
) : OrganizationAccess {
    override suspend fun evaluate(subject: OrganizationAccessSubject, organizationNumber: OrganizationNumber) = result.also { effects += "access" }
}

private class FakeGetActiveSykmeldingLookup(
    private val active: Boolean,
    private val effects: MutableList<String>,
) : ActiveSykmeldingLookup {
    override suspend fun hasActiveSykmelding(personIdent: PersonIdent, organizationNumber: OrganizationNumber) = active.also { effects += "sykmelding" }
}

private class FakeGetOrganization(
    private val name: String?,
    private val effects: MutableList<String>,
) : NarmestelederrelasjonOrganization {
    override suspend fun findName(orgNumber: OrganizationNumber) = name.also { effects += "organization" }
}
