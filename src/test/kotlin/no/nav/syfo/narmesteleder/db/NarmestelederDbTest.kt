package no.nav.syfo.narmesteleder.db

import faker
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAllIgnoringFields
import io.kotest.matchers.equality.shouldBeEqualUsingFields
import io.kotest.matchers.equality.shouldNotBeEqualUsingFields
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.Instant
import java.util.*
import nlBehovEntity
import no.nav.syfo.TestDB
import no.nav.syfo.TestDB.Companion.updateCreated
import no.nav.syfo.narmesteleder.domain.BehovStatus

class NarmestelederDbTest : DescribeSpec({
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
            updateCreated(nlBehovEntity1?.id!!, earlier)
            updateCreated(nlBehovEntity2?.id!!, earlier)
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
    }
})
