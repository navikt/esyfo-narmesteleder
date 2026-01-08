package no.nav.syfo.narmesteleder.db

import faker
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainAllIgnoringFields
import io.kotest.matchers.equality.shouldBeEqualUsingFields
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import nlBehovEntity
import no.nav.syfo.TestDB
import no.nav.syfo.TestDB.Companion.updateCreated
import no.nav.syfo.narmesteleder.domain.BehovStatus
import java.time.Instant
import java.util.UUID

class NarmestelederDbTest :
    DescribeSpec({
        val testDb = TestDB.database
        val db = NarmestelederDb(testDb)

        beforeTest {
            TestDB.clearAllData()
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
                        behovStatus = listOf(BehovStatus.BEHOV_CREATED, BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION),
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
                        behovStatus = listOf(BehovStatus.BEHOV_CREATED, BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION),
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
    })
