package no.nav.syfo.narmesteleder.kafka.model

import createMockToken
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.equality.shouldBeEqualUsingFields
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import kotlinx.coroutines.Dispatchers
import no.nav.syfo.FakesWrapper
import no.nav.syfo.aareg.client.FakeAaregClient
import no.nav.syfo.application.auth.SystemPrincipal
import no.nav.syfo.narmesteleder.db.NarmestelederBehovEntity
import no.nav.syfo.narmesteleder.domain.BehovReason
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmesteleder.domain.Manager
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.*

class NLBehovLeesahHandlerTest :
    DescribeSpec({
        val fakeAaregClient = FakeAaregClient()
        val servicesWrapper = FakesWrapper(Dispatchers.Default)
        val defaultManagerFnr = fakeAaregClient.arbeidsForholdForIdent.keys.first()
        val defaultEmployeeFnr = fakeAaregClient.arbeidsForholdForIdent.keys.last()
        val defaultEmployeeFnrNotReceivedInLeesah = "92392392399"
        val arbeidsforholdEmployeeAareg = fakeAaregClient.arbeidsForholdForIdent[defaultEmployeeFnr]!!.first()
        val arbeidsforholdManagerAareg = fakeAaregClient.arbeidsForholdForIdent[defaultManagerFnr]!!.first()

        val defaultManager = Manager(
            nationalIdentificationNumber = defaultManagerFnr,
            lastName = "ManagerLastName",
            mobile = "99999999",
            email = "mail@manager.no",
        )
        val defaultRequirement = NarmestelederBehovEntity(
            id = UUID.randomUUID(),
            orgnummer = arbeidsforholdEmployeeAareg.first,
            hovedenhetOrgnummer = arbeidsforholdEmployeeAareg.second,
            sykmeldtFnr = defaultEmployeeFnr,
            narmestelederFnr = "123456789",
            behovReason = BehovReason.DEAKTIVERT_LEDER,
            avbruttNarmesteLederId = UUID.randomUUID(),
            behovStatus = BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION
        )
        val defaultRequirementNotReceivedInLeesah = NarmestelederBehovEntity(
            id = UUID.randomUUID(),
            orgnummer = arbeidsforholdEmployeeAareg.first,
            hovedenhetOrgnummer = arbeidsforholdEmployeeAareg.second,
            sykmeldtFnr = defaultEmployeeFnrNotReceivedInLeesah,
            narmestelederFnr = "123456789",
            behovReason = BehovReason.DEAKTIVERT_LEDER,
            avbruttNarmesteLederId = UUID.randomUUID(),
            behovStatus = BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION
        )
        val fullfilledRequirement = NarmestelederBehovEntity(
            id = UUID.randomUUID(),
            orgnummer = arbeidsforholdEmployeeAareg.first,
            hovedenhetOrgnummer = arbeidsforholdEmployeeAareg.second,
            sykmeldtFnr = defaultEmployeeFnr,
            narmestelederFnr = "123456789",
            behovReason = BehovReason.DEAKTIVERT_LEDER,
            avbruttNarmesteLederId = defaultRequirement.avbruttNarmesteLederId,
            behovStatus = BehovStatus.BEHOV_FULFILLED
        )

        beforeTest {
            clearAllMocks()
            servicesWrapper.fakeDbSpyk.clear()
        }

        describe("updateStatusForRequirement") {
            it("Should update status on NlBehov through NarmestelederService when getting via Kafka") {
                val handler = servicesWrapper.nlBehovLeesahHandlerSpyk
                val db = servicesWrapper.fakeDbSpyk
                db.insertNlBehov(defaultRequirement)

                handler.updateStatusForRequirement(
                    NarmestelederLeesahKafkaMessage(
                        narmesteLederId = UUID.randomUUID(),
                        fnr = defaultRequirement.sykmeldtFnr,
                        orgnummer = defaultRequirement.orgnummer,
                        narmesteLederFnr = defaultRequirement.narmestelederFnr.toString(),
                        narmesteLederTelefonnummer = defaultManager.mobile,
                        narmesteLederEpost = defaultManager.email,
                        aktivFom = LocalDate.now(),
                        aktivTom = null,
                        arbeidsgiverForskutterer = null,
                        timestamp = OffsetDateTime.now(),
                        status = LeesahStatus.NY_LEDER
                    )
                )
                val retrievedEntityFullfilledBehov = db.findBehovByParameters(
                    defaultRequirement.sykmeldtFnr,
                    defaultRequirement.orgnummer,
                    listOf(BehovStatus.BEHOV_FULFILLED)
                )
                retrievedEntityFullfilledBehov.size shouldBe 1
                retrievedEntityFullfilledBehov.firstOrNull()?.shouldBeEqualUsingFields {
                    excludedProperties = setOf(
                        NarmestelederBehovEntity::id,
                        NarmestelederBehovEntity::created,
                        NarmestelederBehovEntity::updated
                    )
                    fullfilledRequirement
                }
            }
        }

        it("Should NOT update status on NlBehov through NarmestelederService when getting other info via Kafka") {
            val handler = servicesWrapper.nlBehovLeesahHandlerSpyk
            val db = servicesWrapper.fakeDbSpyk
            db.insertNlBehov(defaultRequirement)
            db.insertNlBehov(fullfilledRequirement)
            db.insertNlBehov(defaultRequirementNotReceivedInLeesah)

            SystemPrincipal(
                ident = "0192:${arbeidsforholdManagerAareg.first}",
                token = createMockToken(
                    ident = "0192:${arbeidsforholdManagerAareg.first}",
                ),
                systemOwner = "0192:systemOwner",
                systemUserId = "systemUserId",
            )

            NarmestelederBehovEntity(
                orgnummer = defaultRequirement.orgnummer,
                sykmeldtFnr = defaultRequirement.sykmeldtFnr,
                narmestelederFnr = defaultRequirement.narmestelederFnr,
                hovedenhetOrgnummer = defaultRequirement.orgnummer,
                behovReason = defaultRequirement.behovReason
            )

            handler.updateStatusForRequirement(
                NarmestelederLeesahKafkaMessage(
                    narmesteLederId = UUID.randomUUID(),
                    fnr = defaultRequirement.sykmeldtFnr,
                    orgnummer = defaultRequirement.orgnummer,
                    narmesteLederFnr = defaultRequirement.narmestelederFnr.toString(),
                    narmesteLederTelefonnummer = defaultManager.mobile,
                    narmesteLederEpost = defaultManager.email,
                    aktivFom = LocalDate.now(),
                    aktivTom = LocalDate.now(),
                    arbeidsgiverForskutterer = null,
                    timestamp = OffsetDateTime.now(),
                    status = LeesahStatus.DEAKTIVERT_LEDER
                )
            )
            val retrievedEntityFullfilledBehov = db.findBehovByParameters(
                defaultRequirement.sykmeldtFnr,
                defaultRequirement.orgnummer,
                listOf(BehovStatus.BEHOV_FULFILLED)
            )
            retrievedEntityFullfilledBehov.size shouldBe 2
            retrievedEntityFullfilledBehov.firstOrNull()?.shouldBeEqualUsingFields {
                excludedProperties = setOf(
                    NarmestelederBehovEntity::id,
                    NarmestelederBehovEntity::created,
                    NarmestelederBehovEntity::updated
                )
                fullfilledRequirement
            }
        }
    })
