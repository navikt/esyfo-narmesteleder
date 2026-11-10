package no.nav.syfo

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.name

class ProductionArtifactTest :
    FunSpec({
        test("production classes contain no fake clients or local setup") {
            val classes = filesIn(Path.of(productionClass.protectionDomain.codeSource.location.toURI()))

            assertSoftly(classes) {
                shouldContain("no/nav/syfo/ApplicationKt.class")
                filter { isFakeOrLocal(it) }.shouldBeEmpty()
            }
        }

        test("production resources contain no fake client fixtures") {
            val anchor = Path.of(requireNotNull(productionClass.getResource("/$PRODUCTION_RESOURCE")).toURI())
            val resources = filesIn(generateSequence(anchor) { it.parent }.drop(PRODUCTION_RESOURCE.split('/').size).first())

            assertSoftly(resources) {
                shouldContain(PRODUCTION_RESOURCE)
                filter { it.startsWith("fake-clients/") }.shouldBeEmpty()
            }
        }
    })

private val productionClass: Class<*> = Class.forName("no.nav.syfo.ApplicationKt")

// Only exists in src/main/resources, so it locates the production resources output.
private const val PRODUCTION_RESOURCE = "openapi/documentation.yaml"

private fun filesIn(root: Path): List<String> = Files.walk(root).use { files ->
    files.filter(Files::isRegularFile).map { root.relativize(it).invariantSeparatorsPathString }.toList()
}

private fun isFakeOrLocal(classFile: String): Boolean = Path.of(classFile).name.let { it.startsWith("Fake") || it.startsWith("Local") } ||
    classFile.startsWith("no/nav/syfo/platform/local/")
