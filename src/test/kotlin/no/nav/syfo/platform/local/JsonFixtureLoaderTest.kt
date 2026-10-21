package no.nav.syfo.platform.local

import com.fasterxml.jackson.core.JsonParseException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File
import java.io.FileNotFoundException

class JsonFixtureLoaderTest :
    FunSpec({

        // Test data class
        data class Person(
            val name: String = "",
            val age: Int = 0,
            val active: Boolean = false
        )

        context("JsonFixtureLoader with classpath resources") {
            val loader = JsonFixtureLoader("classpath:fixtures")

            context("load()") {
                test("should load and parse a valid JSON file") {
                    val person = loader.load<Person>("valid-person.json")

                    person.name shouldBe "Test Person"
                    person.age shouldBe 30
                    person.active shouldBe true
                }

                test("should throw FileNotFoundException for missing file") {
                    shouldThrow<FileNotFoundException> {
                        loader.load<Person>("non-existent.json")
                    }
                }

                test("should throw JsonParseException for malformed JSON") {
                    shouldThrow<JsonParseException> {
                        loader.load<Person>("malformed.json")
                    }
                }
            }

            context("loadOrNull()") {
                test("should load and parse a valid JSON file") {
                    val person = loader.loadOrNull<Person>("valid-person.json")

                    person shouldNotBe null
                    person!!.name shouldBe "Test Person"
                }

                test("should return null for missing file") {
                    val result = loader.loadOrNull<Person>("non-existent.json")

                    result shouldBe null
                }

                test("should throw JsonParseException for malformed JSON") {
                    shouldThrow<JsonParseException> {
                        loader.loadOrNull<Person>("malformed.json")
                    }
                }
            }

            context("exists()") {
                test("should return true for existing file") {
                    loader.exists("valid-person.json") shouldBe true
                }

                test("should return false for non-existent file") {
                    loader.exists("non-existent.json") shouldBe false
                }
            }
        }

        context("JsonFixtureLoader with filesystem path") {
            fun withTempDir(block: (File, JsonFixtureLoader) -> Unit) {
                val tempDir = File(System.getProperty("java.io.tmpdir"), "json-fixture-test-${System.currentTimeMillis()}")
                try {
                    tempDir.mkdirs()
                    File(tempDir, "fs-person.json").writeText("""{"name": "FS Person", "age": 25, "active": false}""")
                    File(tempDir, "fs-malformed.json").writeText("{ not valid }")
                    val loader = JsonFixtureLoader(tempDir.absolutePath)
                    block(tempDir, loader)
                } finally {
                    tempDir.deleteRecursively()
                }
            }

            context("load()") {
                test("should load and parse a valid JSON file from filesystem") {
                    withTempDir { _, loader ->
                        val person = loader.load<Person>("fs-person.json")

                        person.name shouldBe "FS Person"
                        person.age shouldBe 25
                        person.active shouldBe false
                    }
                }

                test("should throw FileNotFoundException for missing file") {
                    withTempDir { _, loader ->
                        shouldThrow<FileNotFoundException> {
                            loader.load<Person>("non-existent.json")
                        }
                    }
                }
            }

            context("loadOrNull()") {
                test("should load and parse a valid JSON file from filesystem") {
                    withTempDir { _, loader ->
                        val person = loader.loadOrNull<Person>("fs-person.json")

                        person shouldNotBe null
                        person!!.name shouldBe "FS Person"
                    }
                }

                test("should return null for missing file") {
                    withTempDir { _, loader ->
                        val result = loader.loadOrNull<Person>("non-existent.json")

                        result shouldBe null
                    }
                }
            }

            context("exists()") {
                test("should return true for existing file") {
                    withTempDir { _, loader ->
                        loader.exists("fs-person.json") shouldBe true
                    }
                }

                test("should return false for non-existent file") {
                    withTempDir { _, loader ->
                        loader.exists("non-existent.json") shouldBe false
                    }
                }
            }
        }

        context("Path normalization") {
            test("should handle basePath without trailing slash") {
                val loader = JsonFixtureLoader("classpath:fixtures")
                loader.exists("valid-person.json") shouldBe true
            }

            test("should handle basePath with trailing slash") {
                val loader = JsonFixtureLoader("classpath:fixtures/")
                loader.exists("valid-person.json") shouldBe true
            }
        }
    })
