package no.nav.syfo.narmesteleder.db

import faker
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainAllIgnoringFields
import io.kotest.matchers.equality.shouldBeEqualUsingFields
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import nlBehovEntity
import no.nav.syfo.TestDB
import no.nav.syfo.TestDB.Companion.updateCreated
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.sykmelding.db.SendtSykmeldingEntity
import no.nav.syfo.sykmelding.db.SykmeldingDb
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.Period.ofDays
import java.util.UUID

class NarmestelederDbTest :
    DescribeSpec({
        val testDb = TestDB.database
        val db = NarmestelederDb(testDb)
        val sykmeldingDb = SykmeldingDb(testDb)

        beforeTest {
            TestDB.clearAllData()
            TestDB.clearSendtSykmeldingData()
        }
        suspend fun insertAndGetBehovWithId(entity: NarmestelederBehovEntity): NarmestelederBehovEntity? {
            val entity = db.insertNlBehov(entity)
            requireNotNull(entity.id)
            return db.findBehovById(entity.id)
        }

        describe("insertNlBehov") {
            it("should return a generated id") {
                // Arrange
                val nlBehovEntity = nlBehovEntity()
                // Act
                val id = db.insertNlBehov(nlBehovEntity)
                // Assert
                id shouldNotBe null
            }

            it("should persist the document with the correct fields") {
                // Arrange
                val nlBehovEntity = nlBehovEntity()
                // Act
                val entity = db.insertNlBehov(nlBehovEntity)
                // Assert
                entity.id shouldNotBe null

                val retrievedEntity = db.findBehovById(entity.id!!)
                retrievedEntity?.shouldBeEqualUsingFields({
                    excludedProperties = setOf(
                        NarmestelederBehovEntity::id,
                        NarmestelederBehovEntity::created,
                        NarmestelederBehovEntity::updated
                    )
                    nlBehovEntity
                })
            }
        }
        describe("updateNlBehov") {
            it("should update mutated fields") {
                // Arrange
                val nlBehovEntity = nlBehovEntity()
                val id = db.insertNlBehov(nlBehovEntity).id!!
                val retrievedEntity = db.findBehovById(id)
                val mutatedEntity = retrievedEntity!!.copy(
                    orgnummer = faker.numerify("#########"),
                    behovStatus = BehovStatus.BEHOV_FULFILLED,
                    dialogId = UUID.randomUUID(),
                    fornavn = faker.name().firstName(),
                    mellomnavn = faker.name().nameWithMiddle().split(" ")[1],
                    etternavn = faker.name().lastName(),
                )
                // Act
                db.updateNlBehov(mutatedEntity)

                // Assert
                val retrievedUpdatedEntity = db.findBehovById(id)
                retrievedUpdatedEntity?.shouldBeEqualUsingFields({
                    excludedProperties = setOf(
                        NarmestelederBehovEntity::id,
                        NarmestelederBehovEntity::created,
                        NarmestelederBehovEntity::updated
                    )
                    mutatedEntity
                })
            }
        }

        describe("getNlBehovByStatus") {
            it("should retrieve only entities with the matching status and created in the past") {
                // Arrange
                val nlBehovEntity1 =
                    insertAndGetBehovWithId(nlBehovEntity().copy(behovStatus = BehovStatus.BEHOV_CREATED))!!
                val nlBehovEntity2 =
                    insertAndGetBehovWithId(nlBehovEntity().copy(behovStatus = BehovStatus.BEHOV_CREATED))!!
                val nlBehovEntity3 =
                    insertAndGetBehovWithId(nlBehovEntity().copy(behovStatus = BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION))
                val earlier = Instant.now().minusSeconds(3 * 60L)
                updateCreated(nlBehovEntity1.id!!, earlier)
                updateCreated(nlBehovEntity2.id!!, earlier)
                updateCreated(nlBehovEntity3?.id!!, earlier)

                // Act
                val retrievedEntities = db.getNlBehovByStatus(BehovStatus.BEHOV_CREATED)

                // Assert
                retrievedEntities.size shouldBe 2

                retrievedEntities.shouldContainAllIgnoringFields(
                    setOf(nlBehovEntity1, nlBehovEntity2),
                    NarmestelederBehovEntity::created,
                    NarmestelederBehovEntity::updated
                )
            }

            it("should retrieve entities with any of the provided statuses and created in the past") {
                // Arrange
                val created1 = insertAndGetBehovWithId(nlBehovEntity().copy(behovStatus = BehovStatus.BEHOV_CREATED))!!
                val created2 =
                    insertAndGetBehovWithId(
                        nlBehovEntity().copy(behovStatus = BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION)
                    )!!
                val fulfilled = insertAndGetBehovWithId(nlBehovEntity().copy(behovStatus = BehovStatus.BEHOV_FULFILLED))!!

                val earlier = Instant.now().minusSeconds(3 * 60L)
                updateCreated(created1.id!!, earlier)
                updateCreated(created2.id!!, earlier)
                updateCreated(fulfilled.id!!, earlier)

                // Act
                val retrieved = db.getNlBehovByStatus(
                    listOf(BehovStatus.BEHOV_CREATED, BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION)
                )

                // Assert
                retrieved.size shouldBe 2
                retrieved.shouldContainAllIgnoringFields(
                    setOf(created1, created2),
                    NarmestelederBehovEntity::created,
                    NarmestelederBehovEntity::updated
                )
            }

            it("should not retrieve entities created within the last 10 seconds") {
                // Arrange
                val recent = insertAndGetBehovWithId(nlBehovEntity().copy(behovStatus = BehovStatus.BEHOV_CREATED))!!
                // Ensure it's 'recent' (created defaults to now)

                // Act
                val retrieved = db.getNlBehovByStatus(listOf(BehovStatus.BEHOV_CREATED))

                // Assert
                retrieved.size shouldBe 0

                // Sanity: if we move created back in time, it should be returned
                val earlier = Instant.now().minusSeconds(3 * 60L)
                updateCreated(recent.id!!, earlier)
                val retrievedAfterUpdate = db.getNlBehovByStatus(listOf(BehovStatus.BEHOV_CREATED))
                retrievedAfterUpdate.size shouldBe 1
                retrievedAfterUpdate.shouldContainAllIgnoringFields(
                    setOf(recent),
                    NarmestelederBehovEntity::created,
                    NarmestelederBehovEntity::updated
                )
            }
        }

        describe("getNlBehovByParameters") {
            describe("sykmeltFnr, orgnummer, behovStatus") {
                it("should retrieve only entities with the matching status") {
                    // Arrange
                    val defaultSykmledteFnr = faker.numerify("###########")
                    val defaultOrgnummer = faker.numerify("#########")
                    val nlBehovEntity1 =
                        insertAndGetBehovWithId(
                            nlBehovEntity().copy(
                                sykmeldtFnr = defaultSykmledteFnr,
                                orgnummer = defaultOrgnummer,
                                behovStatus = BehovStatus.BEHOV_CREATED
                            )
                        )!!
                    val nlBehovEntity2 =
                        insertAndGetBehovWithId(
                            nlBehovEntity().copy(
                                sykmeldtFnr = defaultSykmledteFnr,
                                orgnummer = defaultOrgnummer,
                                behovStatus = BehovStatus.BEHOV_FULFILLED
                            )
                        )!!
                    val nlBehovEntity3 =
                        insertAndGetBehovWithId(
                            nlBehovEntity().copy(
                                sykmeldtFnr = defaultSykmledteFnr,
                                orgnummer = defaultOrgnummer,
                                behovStatus = BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION
                            )
                        )
                    val earlier = Instant.now().minusSeconds(3 * 60L)
                    updateCreated(nlBehovEntity1.id!!, earlier)
                    updateCreated(nlBehovEntity2.id!!, earlier)
                    updateCreated(nlBehovEntity3?.id!!, earlier)

                    // Act
                    val retrievedEntities = db.findBehovByParameters(
                        defaultSykmledteFnr,
                        defaultOrgnummer,
                        listOf(BehovStatus.BEHOV_CREATED, BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION)
                    )

                    // Assert
                    retrievedEntities.size shouldBe 2

                    retrievedEntities.shouldContainAllIgnoringFields(
                        setOf(nlBehovEntity1, nlBehovEntity3),
                        NarmestelederBehovEntity::created,
                        NarmestelederBehovEntity::updated
                    )
                }

                it("should retrieve only entities with the matching sykmeldtFnr") {
                    // Arrange
                    val defaultSykmledteFnr = faker.numerify("###########")
                    val defaultSykmledteFnrNotInQuery = faker.numerify("###########")
                    val defaultOrgnummer = faker.numerify("#########")
                    insertAndGetBehovWithId(
                        nlBehovEntity().copy(
                            sykmeldtFnr = defaultSykmledteFnrNotInQuery,
                            orgnummer = defaultOrgnummer,
                            behovStatus = BehovStatus.BEHOV_CREATED
                        )
                    )!!
                    val nlBehovEntity2 =
                        insertAndGetBehovWithId(
                            nlBehovEntity().copy(
                                sykmeldtFnr = defaultSykmledteFnr,
                                orgnummer = defaultOrgnummer,
                                behovStatus = BehovStatus.BEHOV_CREATED
                            )
                        )!!

                    // Act
                    val retrievedEntities = db.findBehovByParameters(
                        defaultSykmledteFnr,
                        defaultOrgnummer,
                        listOf(BehovStatus.BEHOV_CREATED)
                    )

                    // Assert
                    retrievedEntities.size shouldBe 1

                    retrievedEntities.shouldContainAllIgnoringFields(
                        setOf(nlBehovEntity2),
                        NarmestelederBehovEntity::created,
                        NarmestelederBehovEntity::updated
                    )
                }

                it("should retrieve only entities with the matching orgnummer") {
                    // Arrange
                    val defaultSykmledteFnr = faker.numerify("###########")
                    val defaultOrgnummer = faker.numerify("#########")
                    val defaultOrgnummerNotInQuery = faker.numerify("#########")
                    val nlBehovEntity1 = insertAndGetBehovWithId(
                        nlBehovEntity().copy(
                            sykmeldtFnr = defaultSykmledteFnr,
                            orgnummer = defaultOrgnummer,
                            behovStatus = BehovStatus.BEHOV_CREATED
                        )
                    )!!
                    insertAndGetBehovWithId(
                        nlBehovEntity().copy(
                            sykmeldtFnr = defaultSykmledteFnr,
                            orgnummer = defaultOrgnummerNotInQuery,
                            behovStatus = BehovStatus.BEHOV_CREATED
                        )
                    )!!

                    // Act
                    val retrievedEntities = db.findBehovByParameters(
                        defaultSykmledteFnr,
                        defaultOrgnummer,
                        listOf(BehovStatus.BEHOV_CREATED)
                    )

                    // Assert
                    retrievedEntities.size shouldBe 1

                    retrievedEntities.shouldContainAllIgnoringFields(
                        setOf(nlBehovEntity1),
                        NarmestelederBehovEntity::created,
                        NarmestelederBehovEntity::updated
                    )
                }
            }
            describe("orgnummer, status, createdAfter, limit") {
                it("should retrieve only entities with the matching status, and orgNumber") {
                    // Arrange
                    val defaultSykmledteFnr = faker.numerify("###########")
                    val defaultOrgnummer = faker.numerify("#########")
                    val nlBehovEntity1 =
                        insertAndGetBehovWithId(
                            nlBehovEntity().copy(
                                sykmeldtFnr = defaultSykmledteFnr,
                                orgnummer = defaultOrgnummer,
                                behovStatus = BehovStatus.BEHOV_CREATED
                            )
                        )!!
                    insertAndGetBehovWithId(
                        nlBehovEntity().copy(
                            sykmeldtFnr = defaultSykmledteFnr,
                            orgnummer = defaultOrgnummer,
                            behovStatus = BehovStatus.BEHOV_FULFILLED
                        )
                    )!!
                    insertAndGetBehovWithId(
                        nlBehovEntity().copy(
                            sykmeldtFnr = defaultSykmledteFnr,
                            orgnummer = defaultOrgnummer.reversed(),
                            behovStatus = BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION
                        )
                    )!!
                    val nlBehovEntity3 =
                        insertAndGetBehovWithId(
                            nlBehovEntity().copy(
                                sykmeldtFnr = defaultSykmledteFnr,
                                orgnummer = defaultOrgnummer,
                                behovStatus = BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION
                            )
                        )!!

                    // Act
                    val retrievedEntities = db.findBehovByParameters(
                        orgNumber = defaultOrgnummer,
                        createdAfter = Instant.now().minusSeconds(3 * 60L),
                        status = listOf(BehovStatus.BEHOV_CREATED, BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION),
                        limit = 50
                    )

                    // Assert
                    retrievedEntities.size shouldBe 2

                    retrievedEntities.shouldContainAllIgnoringFields(
                        setOf(nlBehovEntity1, nlBehovEntity3),
                        NarmestelederBehovEntity::created,
                        NarmestelederBehovEntity::updated
                    )
                }

                it("should retrieve limit number of items") {
                    // Arrange
                    val defaultSykmledteFnr = faker.numerify("###########")
                    val defaultOrgnummer = faker.numerify("#########")
                    val nlBehovEntity1 =
                        insertAndGetBehovWithId(
                            nlBehovEntity().copy(
                                sykmeldtFnr = defaultSykmledteFnr,
                                orgnummer = defaultOrgnummer,
                                behovStatus = BehovStatus.BEHOV_CREATED
                            )
                        )!!

                    insertAndGetBehovWithId(
                        nlBehovEntity().copy(
                            sykmeldtFnr = defaultSykmledteFnr,
                            orgnummer = defaultOrgnummer,
                            behovStatus = BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION
                        )
                    )!!

                    // Act
                    val retrievedEntities = db.findBehovByParameters(
                        orgNumber = defaultOrgnummer,
                        createdAfter = Instant.now().minusSeconds(3 * 60L),
                        status = listOf(BehovStatus.BEHOV_CREATED, BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION),
                        limit = 1
                    )

                    // Assert
                    retrievedEntities.size shouldBe 1

                    retrievedEntities.shouldContainAllIgnoringFields(
                        setOf(nlBehovEntity1),
                        NarmestelederBehovEntity::created,
                        NarmestelederBehovEntity::updated
                    )
                }
            }
        }

        describe("setBehovStatusForSykmeldingWithTomBeforeAndStatus") {
            fun sykmeldingEntity(
                fnr: String,
                tom: LocalDate,
                orgnummer: String = faker.numerify("#########")
            ) = SendtSykmeldingEntity(
                sykmeldingId = UUID.randomUUID(),
                fnr = fnr,
                orgnummer = orgnummer,
                fom = tom.minusDays(14),
                tom = tom,
                revokedDate = null,
                syketilfelleStartDato = tom.minusDays(14),
                created = Instant.now(),
                updated = Instant.now(),
            )

            beforeTest {
                TestDB.clearAllData()
                TestDB.clearSendtSykmeldingData()
            }

            it("should update behovs where sykmelding tom is before cutoff") {
                // Arrange
                val fnr = faker.numerify("###########")
                val expiredTom = LocalDate.now().minusDays(10)

                sykmeldingDb.transaction {
                    insertOrUpdateSykmeldingBatch(listOf(sykmeldingEntity(fnr = fnr, tom = expiredTom)))
                }

                val behov = db.insertNlBehov(
                    nlBehovEntity().copy(
                        sykmeldtFnr = fnr,
                        behovStatus = BehovStatus.BEHOV_CREATED
                    )
                )

                // Act
                val updated = db.setBehovStatusForSykmeldingWithTomBeforeAndStatus(
                    tomBefore = LocalDate.now().atStartOfDay().toInstant(java.time.ZoneOffset.UTC),
                    fromStatus = listOf(BehovStatus.BEHOV_CREATED),
                    newStatus = BehovStatus.BEHOV_EXPIRED
                )

                // Assert
                updated shouldBe 1
                val retrieved = db.findBehovById(behov.id!!)
                retrieved?.behovStatus shouldBe BehovStatus.BEHOV_EXPIRED
            }

            it("should not update behovs where sykmelding tom is after cutoff") {
                // Arrange
                val fnr = faker.numerify("###########")
                val activeTom = LocalDate.now().plusDays(30)

                sykmeldingDb.transaction {
                    insertOrUpdateSykmeldingBatch(listOf(sykmeldingEntity(fnr = fnr, tom = activeTom)))
                }

                val behov = db.insertNlBehov(
                    nlBehovEntity().copy(
                        sykmeldtFnr = fnr,
                        behovStatus = BehovStatus.BEHOV_CREATED
                    )
                )

                // Act
                val updated = db.setBehovStatusForSykmeldingWithTomBeforeAndStatus(
                    tomBefore = LocalDate.now().atStartOfDay().toInstant(java.time.ZoneOffset.UTC),
                    fromStatus = listOf(BehovStatus.BEHOV_CREATED),
                    newStatus = BehovStatus.BEHOV_EXPIRED
                )

                // Assert
                updated shouldBe 0
                val retrieved = db.findBehovById(behov.id!!)
                retrieved?.behovStatus shouldBe BehovStatus.BEHOV_CREATED
            }

            it("should only update behovs with matching fromStatus") {
                // Arrange
                val fnr = faker.numerify("###########")
                val expiredTom = LocalDate.now().minusDays(10)

                sykmeldingDb.transaction {
                    insertOrUpdateSykmeldingBatch(listOf(sykmeldingEntity(fnr = fnr, tom = expiredTom)))
                }

                val behovCreated = db.insertNlBehov(
                    nlBehovEntity().copy(
                        sykmeldtFnr = fnr,
                        behovStatus = BehovStatus.BEHOV_CREATED
                    )
                )
                val behovFulfilled = db.insertNlBehov(
                    nlBehovEntity().copy(
                        sykmeldtFnr = fnr,
                        behovStatus = BehovStatus.BEHOV_FULFILLED
                    )
                )

                // Act
                val updated = db.setBehovStatusForSykmeldingWithTomBeforeAndStatus(
                    tomBefore = LocalDate.now().atStartOfDay().toInstant(java.time.ZoneOffset.UTC),
                    fromStatus = listOf(BehovStatus.BEHOV_CREATED),
                    newStatus = BehovStatus.BEHOV_EXPIRED
                )

                // Assert
                updated shouldBe 1
                db.findBehovById(behovCreated.id!!)?.behovStatus shouldBe BehovStatus.BEHOV_EXPIRED
                db.findBehovById(behovFulfilled.id!!)?.behovStatus shouldBe BehovStatus.BEHOV_FULFILLED
            }

            it("should respect limit parameter") {
                // Arrange
                val fnr1 = faker.numerify("###########")
                val fnr2 = faker.numerify("###########")
                val expiredTom = LocalDate.now().minusDays(10)

                sykmeldingDb.transaction {
                    insertOrUpdateSykmeldingBatch(
                        listOf(
                            sykmeldingEntity(fnr = fnr1, tom = expiredTom),
                            sykmeldingEntity(fnr = fnr2, tom = expiredTom)
                        )
                    )
                }

                val behov1 = db.insertNlBehov(
                    nlBehovEntity().copy(sykmeldtFnr = fnr1, behovStatus = BehovStatus.BEHOV_CREATED)
                )
                val behov2 = db.insertNlBehov(
                    nlBehovEntity().copy(sykmeldtFnr = fnr2, behovStatus = BehovStatus.BEHOV_CREATED)
                )

                // Act
                val updated = db.setBehovStatusForSykmeldingWithTomBeforeAndStatus(
                    tomBefore = LocalDate.now().atStartOfDay().toInstant(java.time.ZoneOffset.UTC),
                    fromStatus = listOf(BehovStatus.BEHOV_CREATED),
                    newStatus = BehovStatus.BEHOV_EXPIRED,
                    limit = 1
                )

                // Assert
                updated shouldBe 1
            }

            it("should return 0 when fromStatus list is empty") {
                // Arrange
                val fnr = faker.numerify("###########")
                val expiredTom = LocalDate.now().minusDays(10)

                sykmeldingDb.transaction {
                    insertOrUpdateSykmeldingBatch(listOf(sykmeldingEntity(fnr = fnr, tom = expiredTom)))
                }

                db.insertNlBehov(
                    nlBehovEntity().copy(sykmeldtFnr = fnr, behovStatus = BehovStatus.BEHOV_CREATED)
                )

                // Act
                val updated = db.setBehovStatusForSykmeldingWithTomBeforeAndStatus(
                    tomBefore = LocalDate.now().atStartOfDay().toInstant(java.time.ZoneOffset.UTC),
                    fromStatus = emptyList(),
                    newStatus = BehovStatus.BEHOV_EXPIRED
                )

                // Assert
                updated shouldBe 0
            }

            it("should not update behovs without matching sykmelding") {
                // Arrange
                val fnrWithSykmelding = faker.numerify("###########")
                val fnrWithoutSykmelding = faker.numerify("###########")
                val expiredTom = LocalDate.now().minusDays(17)

                sykmeldingDb.transaction {
                    insertOrUpdateSykmeldingBatch(listOf(sykmeldingEntity(fnr = fnrWithSykmelding, tom = expiredTom)))
                }

                val behovWithSykmelding = db.insertNlBehov(
                    nlBehovEntity().copy(sykmeldtFnr = fnrWithSykmelding, behovStatus = BehovStatus.BEHOV_CREATED)
                )
                val behovWithoutSykmelding = db.insertNlBehov(
                    nlBehovEntity().copy(sykmeldtFnr = fnrWithoutSykmelding, behovStatus = BehovStatus.BEHOV_CREATED)
                )

                // Act
                val updated = db.setBehovStatusForSykmeldingWithTomBeforeAndStatus(
                    tomBefore = LocalDate.now().atStartOfDay().toInstant(java.time.ZoneOffset.UTC),
                    fromStatus = listOf(BehovStatus.BEHOV_CREATED),
                    newStatus = BehovStatus.BEHOV_EXPIRED
                )

                // Assert
                updated shouldBe 1
                db.findBehovById(behovWithSykmelding.id!!)?.behovStatus shouldBe BehovStatus.BEHOV_EXPIRED
                db.findBehovById(behovWithoutSykmelding.id!!)?.behovStatus shouldBe BehovStatus.BEHOV_CREATED
            }
        }
    })
