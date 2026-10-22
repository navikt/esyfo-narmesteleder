package no.nav.syfo.narmestelederrelasjon.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmestelederrelasjon.domain.Narmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.domain.RelationPerson
import no.nav.syfo.organisasjonstilgang.application.AccessToken
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccess
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName
import java.util.UUID

class GetNarmestelederrelasjonUseCaseTest :
    FunSpec({
        val id = UUID.randomUUID()
        val employeeIdent = PersonalIdentificationNumber("12345678901")
        val orgNumber = OrganizationNumber("123456789")
        val caller = OrganizationAccessSubject.PersonnelManager(
            personIdent = PersonalIdentificationNumber("11111111111"),
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
            access: OrganizationAccessResult = OrganizationAccessResult.Granted(organizationName = null),
            activeSykmelding: Boolean = true,
            personIdentifiers: PersonIdentifiersLookupResult = PersonIdentifiersLookupResult.Found(PersonIdentifiers(employeeIdent, listOf(employeeIdent))),
            activeLookup: ((List<PersonalIdentificationNumber>, OrganizationNumber) -> ActiveSykmeldingResult)? = null,
            organizationName: OrganizationNameResult = OrganizationNameResult.Found("Organization"),
            effects: MutableList<String> = mutableListOf(),
        ) = GetNarmestelederrelasjonUseCase(
            repository = FakeNarmestelederrelasjonRepository(lookup, effects),
            organizationAccess = FakeGetOrganizationAccess(access, effects),
            personIdentifiersLookup = FakeGetPersonIdentifiersLookup(personIdentifiers, effects),
            activeSykmeldingLookup = FakeGetActiveSykmeldingLookup(
                activeLookup ?: { _, _ -> if (activeSykmelding) ActiveSykmeldingResult.Found else ActiveSykmeldingResult.NotFound },
                effects,
            ),
            organization = FakeGetOrganization(organizationName, effects),
        )

        test("returns the relation only to an organization-authorized caller") {
            val effects = mutableListOf<String>()
            query(effects = effects).execute(id, caller) shouldBe GetNarmestelederrelasjonResult.Found(
                Narmestelederrelasjon(
                    id = id,
                    orgNumber = orgNumber,
                    employee = RelationPerson(employeeIdent, "Employee", null, "Person"),
                ),
                "Organization",
            )
            effects shouldBe listOf("lookup", "access", "identifiers", "sykmelding", "organization")
        }

        test("masks missing organization access") {
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

        test("unavailable access stops before sykmelding and organization lookup") {
            val effects = mutableListOf<String>()
            val failure = UpstreamFailure(UpstreamName("arbeidsgiver-altinn-tilganger"), UpstreamFailureStage.RESPONSE, 503, IllegalStateException())
            val result = query(access = OrganizationAccessResult.Unavailable(failure), effects = effects).execute(id, caller)
            result shouldBe GetNarmestelederrelasjonResult.UpstreamUnavailable(failure)
            result.shouldBeInstanceOf<GetNarmestelederrelasjonResult.UpstreamUnavailable>().failure shouldBeSameInstanceAs failure
            effects shouldBe listOf("lookup", "access")
        }

        test("masks missing active sykmelding after authorization and before organization lookup") {
            val effects = mutableListOf<String>()
            query(activeSykmelding = false, effects = effects).execute(id, caller) shouldBe
                GetNarmestelederrelasjonResult.NotFound(GetNarmestelederrelasjonResult.NotFoundReason.NO_ACTIVE_SYKMELDING)
            effects shouldBe listOf("lookup", "access", "identifiers", "sykmelding")
        }

        test("checks active sykmelding across the current and historical identities") {
            val effects = mutableListOf<String>()
            val historicalIdent = PersonalIdentificationNumber("12345678912")
            var checkedIdents: List<PersonalIdentificationNumber>? = null

            query(
                personIdentifiers = PersonIdentifiersLookupResult.Found(PersonIdentifiers(employeeIdent, listOf(employeeIdent, historicalIdent))),
                activeLookup = { personIdents, _ ->
                    checkedIdents = personIdents
                    if (historicalIdent in personIdents) ActiveSykmeldingResult.Found else ActiveSykmeldingResult.NotFound
                },
                effects = effects,
            ).execute(id, caller) shouldBe GetNarmestelederrelasjonResult.Found(
                Narmestelederrelasjon(
                    id = id,
                    orgNumber = orgNumber,
                    employee = RelationPerson(employeeIdent, "Employee", null, "Person"),
                ),
                "Organization",
            )
            checkedIdents shouldBe listOf(employeeIdent, historicalIdent)
        }

        test("returns PersonNotFound when PDL cannot resolve the stored employee identities") {
            val effects = mutableListOf<String>()

            query(
                personIdentifiers = PersonIdentifiersLookupResult.NotFound,
                effects = effects,
            ).execute(id, caller) shouldBe GetNarmestelederrelasjonResult.NotFound(GetNarmestelederrelasjonResult.NotFoundReason.PERSON_NOT_FOUND)
            effects shouldBe listOf("lookup", "access", "identifiers")
        }

        test("returns UpstreamUnavailable when PDL identity resolution fails") {
            val effects = mutableListOf<String>()
            val failure = UpstreamFailure(UpstreamName("pdl"), UpstreamFailureStage.RESPONSE, 503, IllegalStateException())

            query(
                personIdentifiers = PersonIdentifiersLookupResult.Unavailable(failure),
                effects = effects,
            ).execute(id, caller) shouldBe GetNarmestelederrelasjonResult.UpstreamUnavailable(failure)
            effects shouldBe listOf("lookup", "access", "identifiers")
        }

        test("returns UpstreamUnavailable when active sick leave lookup fails") {
            val effects = mutableListOf<String>()
            val failure = UpstreamFailure(UpstreamName("dinesykmeldte"), UpstreamFailureStage.RESPONSE, 503, IllegalStateException())

            query(
                activeLookup = { _, _ -> ActiveSykmeldingResult.Unavailable(failure) },
                effects = effects,
            ).execute(id, caller) shouldBe GetNarmestelederrelasjonResult.UpstreamUnavailable(failure)
            effects shouldBe listOf("lookup", "access", "identifiers", "sykmelding")
        }

        test("masks an inactive relation before authorization lookup") {
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
            test("returns the relation with a nullable name for $description") {
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
                effects shouldBe listOf("lookup", "access", "identifiers", "sykmelding", "organization")
            }
        }

        test("returns the relation with a null organization name when the name is missing") {
            val effects = mutableListOf<String>()
            query(organizationName = OrganizationNameResult.Missing, effects = effects).execute(id, caller) shouldBe
                GetNarmestelederrelasjonResult.Found(
                    Narmestelederrelasjon(
                        id = id,
                        orgNumber = orgNumber,
                        employee = RelationPerson(employeeIdent, "Employee", null, "Person"),
                    ),
                    organizationName = null,
                )
            effects shouldBe listOf("lookup", "access", "identifiers", "sykmelding", "organization")
        }

        test("returns UpstreamUnavailable with the unchanged failure after organization lookup") {
            val effects = mutableListOf<String>()
            val failure = UpstreamFailure(UpstreamName("ereg"), UpstreamFailureStage.RESPONSE, 503, IllegalStateException())

            query(organizationName = OrganizationNameResult.Unavailable(failure), effects = effects)
                .execute(id, caller).shouldBeInstanceOf<GetNarmestelederrelasjonResult.UpstreamUnavailable>()
                .failure shouldBeSameInstanceAs failure
            effects shouldBe listOf("lookup", "access", "identifiers", "sykmelding", "organization")
        }

        test("does not perform authorization lookup when no relation exists") {
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
    private val result: (List<PersonalIdentificationNumber>, OrganizationNumber) -> ActiveSykmeldingResult,
    private val effects: MutableList<String>,
) : ActiveSykmeldingLookup {
    override suspend fun findActiveSykmelding(
        personIdents: List<PersonalIdentificationNumber>,
        organizationNumber: OrganizationNumber,
        operation: ActiveSykmeldingOperation,
    ): ActiveSykmeldingResult = result(personIdents, organizationNumber).also { effects += "sykmelding" }
}

private class FakeGetPersonIdentifiersLookup(
    private val result: PersonIdentifiersLookupResult,
    private val effects: MutableList<String>,
) : PersonIdentifiersLookup {
    override suspend fun find(
        personIdent: PersonalIdentificationNumber,
        operation: PersonIdentifiersLookupOperation,
    ): PersonIdentifiersLookupResult = result.also { effects += "identifiers" }
}

private class FakeGetOrganization(
    private val result: OrganizationNameResult,
    private val effects: MutableList<String>,
) : NarmestelederrelasjonOrganization {
    override suspend fun findName(orgNumber: OrganizationNumber) = result.also { effects += "organization" }
}
