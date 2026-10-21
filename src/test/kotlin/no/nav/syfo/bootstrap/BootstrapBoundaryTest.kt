package no.nav.syfo.bootstrap

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import java.lang.classfile.ClassFile
import java.lang.classfile.constantpool.ClassEntry
import java.lang.classfile.constantpool.Utf8Entry
import java.nio.file.Files
import java.nio.file.Path

class BootstrapBoundaryTest :
    StringSpec({
        "business modules receive small settings and never reference bootstrap" {
            val classes = Path.of(Environment::class.java.protectionDomain.codeSource.location.toURI())
            val violations = businessPackages.flatMap { pkg ->
                Files.walk(classes.resolve("no/nav/syfo/$pkg")).use { files ->
                    files.filter { it.toString().endsWith(".class") }
                        .toList()
                        .filter { file -> referencesBootstrap(Files.readAllBytes(file)) }
                        .map { it.toString() }
                }
            }
            violations.shouldBeEmpty()
        }
    })

private val businessPackages = listOf(
    "narmesteleder",
    "narmestelederbehov",
    "narmestelederrelasjon",
    "narmestelederstatistikk",
    "organisasjonstilgang",
    "sykmelding",
    "integration",
)

private fun referencesBootstrap(bytes: ByteArray): Boolean = ClassFile.of().parse(bytes).constantPool().any { entry ->
    val value = when (entry) {
        is ClassEntry -> entry.asInternalName()
        is Utf8Entry -> entry.stringValue()
        else -> ""
    }
    value.contains("no/nav/syfo/bootstrap/")
}
