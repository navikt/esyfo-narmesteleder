package no.nav.syfo.bootstrap

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import java.lang.classfile.ClassFile
import java.lang.classfile.constantpool.ClassEntry
import java.lang.classfile.constantpool.Utf8Entry
import java.nio.file.Files
import java.nio.file.Path

class BootstrapBoundaryTest :
    FunSpec({
        test("business modules receive small settings and never reference bootstrap") {
            val classes = Path.of(Environment::class.java.protectionDomain.codeSource.location.toURI())
            val violations = (businessPackages + "integration").flatMap { pkg ->
                Files.walk(classes.resolve("no/nav/syfo/$pkg")).use { files ->
                    files.filter { it.toString().endsWith(".class") }
                        .toList()
                        .filter { file -> referencesBootstrap(Files.readAllBytes(file)) }
                        .map { it.toString() }
                }
            }
            violations.shouldBeEmpty()
        }

        test("root packages are only business modules and supporting packages") {
            val classes = Path.of(Environment::class.java.protectionDomain.codeSource.location.toURI())
            val rootPackages = Files.list(classes.resolve("no/nav/syfo")).use { entries ->
                entries.filter { Files.isDirectory(it) }.map { it.fileName.toString() }.toList()
            }
            rootPackages shouldContainExactlyInAnyOrder businessPackages + supportingPackages
        }
    })

private val businessPackages = listOf(
    "narmestelederbehov",
    "narmestelederrelasjon",
    "narmestelederstatistikk",
    "organisasjonstilgang",
    "sykmelding",
)

private val supportingPackages = listOf(
    "bootstrap",
    "ident",
    "integration",
    "platform",
)

private fun referencesBootstrap(bytes: ByteArray): Boolean = ClassFile.of().parse(bytes).constantPool().any { entry ->
    val value = when (entry) {
        is ClassEntry -> entry.asInternalName()
        is Utf8Entry -> entry.stringValue()
        else -> ""
    }
    value.contains("no/nav/syfo/bootstrap/")
}
